package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class StockInputWiringTest {
    private val now = java.time.Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
    private val bars = (1..40).map { KBar(java.time.LocalDate.of(2026, 9, 1).plusDays(it.toLong()).toString(), 10.0, 10.1, 11.0, 9.0, 1000.0) }
    private val row = LiveMarket.Row("600519", "fixture", 10.0, 2.0, null, null, null, null, null, null, null, null, now)

    @Test fun `production candidate reads typed provider once and preserves observation scores`() = runBlocking {
        var calls = 0
        val result = LocalScreener.analyzeCandidate(row, LocalScreener.Criteria(), false,
            barsProvider = { _, _ -> bars }, inputProvider = { code, actual ->
                calls++
                Db.stockAnalysisInputs(code, actual, now, StockInputTransport { null })
            }, nowMs = now)
        assertEquals(1, calls)
        assertNotNull(result.engine)
        assertEquals("insufficient", result.detailStatus)
        assertTrue(result.reason!!.contains("DPS"))
        assertEquals(bars, result.bars)
        assertFalse(result.safetyInputsReady)
    }

    @Test fun `default production provider executes Db transport without network`() = runBlocking {
        val urls = mutableListOf<String>()
        val result = LocalScreener.analyzeCandidate(row, LocalScreener.Criteria(), false,
            barsProvider = { _, _ -> bars }, nowMs = now, inputTransport = StockInputTransport { url ->
                urls += url
                if (url.endsWith("finance.json")) """{"symbol":"sh600519","reports":[{"report_date":"2026-06-30","eps":35.57}],"ts":"2026-10-14T09:30:17"}""" else null
            })
        assertEquals(1, urls.count { it.endsWith("finance.json") })
        assertEquals(1, urls.count { it.contains("api/stocks") })
        assertEquals(35.57, result.dataEvidence!!.eps)
        assertEquals("insufficient", result.detailStatus)
        assertTrue(result.reason!!.contains("DPS"))
    }

    @Test fun `complete matching evidence validates but mismatched bars do not`() {
        val evidence = LocalScreener.DataEvidence(1.0, 0.1, "2026-06-30", "cnfin:hfq_factor:fixture", "fixture", now - 1, now + 1000,
            code = "600519", barsFingerprint = StockAnalysisInputs.barsFingerprint(bars))
        assertTrue(evidence.validFor("600519", bars, now))
        assertFalse(evidence.validFor("002242", bars, now))
        assertFalse(evidence.validFor("600519", bars.mapIndexed { i, b -> if (i == 5) b.copy(high = 12.0) else b }, now))
        assertFalse(evidence.copy(barsFingerprint = null).validFor("600519", bars, now))
    }

    @Test fun `production candidate consumes complete matching inputs but rejects changed middle bar`() = runBlocking {
        val flow = FlowEngine.Result("600519", 70.0, "bullish", "fixture", null, null, null, null, null, null, 1.0, 1.0, 1.0, emptyList())
        val tape = TapeEngine.Result(70.0, "bullish", null, null, null, null, null, null, null, 1.0, 38, "ok", emptyList())
        val chain = EngineChain.ChainResult(70.0, null, flow, tape, null, emptyList(), "fixture", false, emptyList(), null, "ok")
        val input = StockAnalysisInputs("600519", 1.0, 0.1, "2026-06-30", "fixture", now - 1, now + 1000,
            bars, StockAnalysisInputs.barsFingerprint(bars), "cnfin:hfq_factor:fixture")
        var reads = 0
        suspend fun analyze(value: StockAnalysisInputs) = LocalScreener.analyzeCandidate(row, LocalScreener.Criteria(), false,
            barsProvider = { _, _ -> bars }, inputProvider = { _, _ -> reads++; value }, nowMs = now, evaluate = { _, _ -> chain })
        assertEquals("ok", analyze(input).detailStatus)
        assertEquals(1, reads)
        val changed = bars.mapIndexed { i, b -> if (i == 5) b.copy(open = 9.5) else b }
        assertEquals("insufficient", analyze(input.copy(bars = changed)).detailStatus)
        assertEquals("insufficient", analyze(input.copy(expiresAtMs = now - 1)).detailStatus)
        assertEquals("insufficient", analyze(input.copy(code = "002242")).detailStatus)
    }

    @Test fun `candidate cancellation is never converted to missing data`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            LocalScreener.analyzeCandidate(row, LocalScreener.Criteria(), false,
                barsProvider = { _, _ -> bars }, inputProvider = { _, _ -> throw CancellationException("fixture") }, nowMs = now)
        }
    }

    @Test fun `middle OHLC revision invalidates actual engine cache`() {
        DataCache.clear()
        val before = DataCache.engine("600519", "qfq", bars)
        val after = DataCache.engine("600519", "qfq", bars.mapIndexed { i, b -> if (i == 5) b.copy(high = 12.0) else b })
        assertNotSame(before, after)
    }
}
