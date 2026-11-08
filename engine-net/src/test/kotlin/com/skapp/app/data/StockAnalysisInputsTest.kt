package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockAnalysisInputsTest {
    private val now = java.time.Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
    private val bars = listOf(KBar("2026-11-06", 10.0, 11.0, 12.0, 9.0, 100.0))

    @Test fun `approved original benchmarks keep volume and anchor prices to raw last close`() {
        for (code in listOf("sh600519", "sz002242")) {
            val text = java.io.File(System.getProperty("skapp.fixtures"), "research/${code}_benchmark.csv").readText()
            val benchmark = requireNotNull(StockAnalysisInputs.benchmark(code.drop(2), text, now))
            val lines = text.trim().removePrefix("\uFEFF").lines()
            val header = lines.first().split(',')
            val last = lines.last().split(',')
            val close = last[header.indexOf("close_price")].toDouble()
            assertEquals(close, benchmark.bars.last().close, 1e-7)
            assertEquals(last[header.indexOf("volume")].toDouble(), benchmark.bars.last().vol)
            assertEquals(lines.size - 1, benchmark.bars.size)
            assertTrue(benchmark.barSources.values.contains("cnfin"))
        }
    }

    @Test fun `stale approved benchmark never overwrites current bars`() = runBlocking {
        val text = java.io.File(System.getProperty("skapp.fixtures"), "research/sh600519_benchmark.csv").readText()
        val input = Db.stockAnalysisInputs("600519", bars, now, StockInputTransport { url ->
            if (url.endsWith("_benchmark.csv")) text else null
        })
        assertEquals(bars, input.bars)
        assertNull(input.adjustmentEvidence)
        assertTrue(input.missing.contains("benchmark_stale_or_date_mismatch"))
    }

    @Test fun `real nested financial does not invent EPS or DPS`() = runBlocking {
        val input = Db.stockAnalysisInputs("600519", bars, now, StockInputTransport { url ->
            if (url.contains("api/stocks")) """{"code":"600519","detail":{"financial_latest":{"report_date":"2003-12-31","eps":null}}}"""
            else if (url.endsWith("finance.json")) """{"symbol":"sh600519","reports":[{"report_date":"2026-06-30","eps":35.57}],"ts":"2026-10-14T09:30:17"}"""
            else null
        })
        assertEquals(35.57, input.eps)
        assertNull(input.dps)
        assertEquals("2026-06-30", input.reportPeriod)
        assertTrue("DPS" in input.gaps(now))
        assertTrue("source_stale" in input.gaps(now))
        assertNull(input.adjustmentEvidence)
    }

    @Test fun `latest legal report rejects future and malformed dates`() {
        val input = StockAnalysisInputs.financial("600519", null, JSONObject("""{"symbol":"sh600519","ts":"2026-11-08T09:00:00Z","reports":[{"report_date":"2026-6-30","eps":99},{"report_date":"2027-01-01","eps":88},{"report_date":"2026-06-30","eps":35.57},{"report_date":"2026-03-31","eps":12}]}"""), now)
        assertEquals(35.57, input.eps)
        assertEquals("2026-06-30", input.reportPeriod)
    }

    @Test fun `different report periods never mix EPS and DPS`() {
        val api = JSONObject("""{"code":"600519","ts":"2026-11-08T09:00:00Z","detail":{"financial_latest":{"report_date":"2026-03-31","dps":5}}}""")
        val holo = JSONObject("""{"symbol":"sh600519","ts":"2026-11-08T09:00:00Z","reports":[{"report_date":"2026-06-30","eps":35.57}]}""")
        assertNull(StockAnalysisInputs.financial("600519", api, holo, now).dps)
        assertEquals(5.0, StockAnalysisInputs.financial("600519", JSONObject(api.toString().replace("2026-03-31", "2026-06-30")), holo, now).dps)
    }

    @Test fun `missing timestamps and mismatched symbols cannot gain provenance`() {
        val input = StockAnalysisInputs.financial("600519", null, JSONObject("""{"symbol":"sz002242","reports":[{"report_date":"2026-06-30","eps":1}]}"""), now)
        assertNull(input.eps)
        assertNull(input.snapshotAtMs)
        assertTrue("source_time" in input.gaps(now))
    }

    @Test fun `all OHLC and volume fields affect bar binding`() {
        val original = StockAnalysisInputs.barsFingerprint(bars)
        for (changed in listOf(bars[0].copy(open = 9.5), bars[0].copy(high = 13.0), bars[0].copy(low = 8.0), bars[0].copy(close = 10.5), bars[0].copy(vol = 101.0))) {
            assertFalse(original == StockAnalysisInputs.barsFingerprint(listOf(changed)))
        }
    }

    @Test fun `real detail date timestamp is retained and never refreshed`() {
        val root = JSONObject("""{"code":"600519","detail":{"ts":"2026-10-16","financial_latest":{"symbol":"sh600519","report_date":"2003-09-30","announce_date":"2004-10-20","eps":"","roe":"12"}}}""")
        val input = StockAnalysisInputs.financial("600519", root, null, now)
        assertEquals("2003-09-30", input.reportPeriod)
        assertNull(input.eps)
        assertEquals(java.time.LocalDate.of(2026, 10, 16).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), input.snapshotAtMs)
        assertEquals("12", input.financialFields["roe"])
        assertTrue("source_stale" in input.gaps(now))
    }

    @Test fun `API without identity or with conflicting detail identity is rejected`() {
        for (body in listOf(
            """{"detail":{"financial_latest":{"report_date":"2026-06-30","eps":1}}}""",
            """{"code":"600519","detail":{"code":"002242","financial_latest":{"report_date":"2026-06-30","eps":1}}}"""
        )) assertNull(StockAnalysisInputs.financial("600519", JSONObject(body), null, now).eps)
    }

    @Test fun `unapproved CSV or another code cannot claim cnfin`() {
        val csv = "date,open_price,hfq_factor\n2026-11-06,10,1\n"
        assertNull(StockAnalysisInputs.benchmark("600519", csv, now))
        assertNull(StockAnalysisInputs.benchmark("600000", csv, now))
    }

    @Test fun `future announcement and invalid calendar day are rejected`() {
        for (report in listOf("\"report_date\":\"2026-02-30\"", "\"report_date\":\"2026-06-30\",\"announce_date\":\"2027-01-01\"")) {
            val root = JSONObject("{\"symbol\":\"sh600519\",\"reports\":[{$report,\"eps\":1}]}")
            assertNull(StockAnalysisInputs.financial("600519", null, root, now).eps)
        }
    }

    @Test fun `transport cancellation propagates`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            Db.stockAnalysisInputs("600519", bars, now, StockInputTransport { throw CancellationException("fixture") })
        }
    }
}
