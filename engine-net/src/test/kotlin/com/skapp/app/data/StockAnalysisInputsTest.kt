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
            if (code == "sz002242") assertEquals("2008-05-28", benchmark.bars.first().date)
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

    @Test fun `legacy detail root timestamp cannot renew a report row`() {
        val root = JSONObject("""{"code":"600519","detail":{"ts":"2026-10-16","financial_latest":{"symbol":"sh600519","report_date":"2003-09-30","announce_date":"2004-10-20","eps":"","roe":"12"}}}""")
        val input = StockAnalysisInputs.financial("600519", root, null, now)
        assertEquals("2003-09-30", input.reportPeriod)
        assertNull(input.eps)
        assertNull(input.snapshotAtMs)
        assertTrue("financial_row_time_missing" in input.gaps(now))
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

    private fun collected(period: String = "2026-09-30", rowTime: String = "2026-11-08T09:00:00Z"): JSONObject = JSONObject("""
        {"symbol":"600519","code":"sh600519","ts":"2026-11-08T09:00:00Z","reports":[
          {"symbol":"600519","report_date":"$period","announce_date":"2026-10-30","update_date":"2026-10-30",
           "eps":42.5,"dps":null,"dps_reason":"no_implemented_dividend","ts":"$rowTime",
           "data_evidence":{"eps":{"source":"https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_LICO_FN_CPD","field":"BASIC_EPS","report_date":"$period","unit":"CNY/share"}}}
        ]}
    """)

    @Test fun `collected report cold starts with latest EPS and real provenance but no invented DPS or factor`() = runBlocking {
        val doc = collected().toString()
        val input = Db.stockAnalysisInputs("600519", bars, now, StockInputTransport { url ->
            if (url.endsWith("/fin/sh600519.json")) doc else null
        })
        assertEquals(42.5, input.eps)
        assertEquals("2026-09-30", input.reportPeriod)
        assertNull(input.dps)
        assertTrue(input.source!!.contains("datacenter-web.eastmoney.com"))
        assertTrue(input.financialFields.containsKey("data_evidence"))
        assertTrue(input.gaps(now).any { it.contains("cnfin_benchmark_unverified") })
    }

    @Test fun `collected row timestamp is never renewed from root and identity is checked twice`() {
        val stale = StockAnalysisInputs.financial("600519", null, null, now, collected(rowTime = "2026-10-01T09:00:00Z"))
        assertTrue("source_stale" in stale.gaps(now))
        val noTime = StockAnalysisInputs.financial("600519", null, null, now, collected(rowTime = ""))
        assertNull(noTime.snapshotAtMs)
        for (doc in listOf(collected().put("symbol", "002242"), collected().also {
            it.getJSONArray("reports").getJSONObject(0).put("symbol", "002242")
        })) assertNull(StockAnalysisInputs.financial("600519", null, null, now, doc).eps)
    }

    @Test fun `collected latest period never borrows historical dividend and same period conflicts reject`() {
        val old = JSONObject("""{"symbol":"sh600519","ts":"2026-11-08T09:00:00Z","reports":[{"report_date":"2025-12-31","dps":20}]}""")
        assertNull(StockAnalysisInputs.financial("600519", null, old, now, collected()).dps)
        val conflict = JSONObject("""{"symbol":"sh600519","ts":"2026-11-08T09:00:00Z","reports":[{"report_date":"2026-09-30","eps":99}]}""")
        val input = StockAnalysisInputs.financial("600519", null, conflict, now, collected())
        assertNull(input.eps)
        assertTrue(input.missing.contains("eps_conflict"))
        val explicitConflict = collected().also {
            it.getJSONArray("reports").getJSONObject(0).put("dps_reason", "dividend_conflict")
        }
        assertTrue(StockAnalysisInputs.financial("600519", null, null, now, explicitConflict).missing.any { it.contains("conflict") })
    }

    @Test fun `collected explicit zero and same period merge preserve field provenance`() {
        val doc = collected()
        val row = doc.getJSONArray("reports").getJSONObject(0)
        row.put("dps", 0).put("dps_reason", JSONObject.NULL)
        row.getJSONObject("data_evidence").put("dps", JSONObject("""{"symbol":"600519","report_date":"2026-09-30","source":"https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_LICO_FN_CPD","field":"ASSIGNDSCRPT","unit":"CNY/share","confidence":"verified_public","snapshot_ts":"2026-11-08T09:00:00Z"}"""))
        val input = StockAnalysisInputs.financial("600519", null, null, now, doc)
        assertEquals(0.0, input.dps)
        assertEquals(42.5, input.eps)
        assertTrue(input.missing.isEmpty())
        assertTrue("DPS" !in input.gaps(now))
        val duplicate = JSONObject(row.toString()).put("eps", 43.0)
        doc.getJSONArray("reports").put(duplicate)
        assertNull(StockAnalysisInputs.financial("600519", null, null, now, doc).eps)
    }

    @Test fun `unverified boolean future source and cross period evidence fail closed`() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("eps", true) },
            { it.put("announce_date", "2027-01-01") },
            { it.put("update_date", "2027-01-01") },
            { it.put("ts", "2027-01-01T00:00:00Z") },
            { it.getJSONObject("data_evidence").getJSONObject("eps").put("report_date", "2026-06-30") },
            { it.getJSONObject("data_evidence").getJSONObject("eps").put("symbol", "002242") },
            { it.getJSONObject("data_evidence").getJSONObject("eps").put("source", "https://example.invalid/financial") }
        )) {
            val doc = collected()
            mutate(doc.getJSONArray("reports").getJSONObject(0))
            assertNull(StockAnalysisInputs.financial("600519", null, null, now, doc).eps)
        }
    }

    @Test fun `legacy root clocks and missing field evidence cannot pass financial gates`() {
        val row = JSONObject("""{"report_date":"2026-09-30","eps":42.5,"dps":0}""")
        val holo = JSONObject().put("symbol", "600519").put("ts", "2026-11-08T09:00:00Z")
            .put("reports", org.json.JSONArray().put(row))
        val api = JSONObject().put("code", "600519").put("ts", "2026-11-08T09:00:00Z")
            .put("detail", JSONObject().put("financial_latest", row))
        for (input in listOf(StockAnalysisInputs.financial("600519", api, null, now),
            StockAnalysisInputs.financial("600519", null, holo, now))) {
            assertEquals(42.5, input.eps)
            assertTrue("financial_row_identity_missing" in input.gaps(now))
            assertTrue("financial_row_time_missing" in input.gaps(now))
            assertTrue("eps_source_evidence_missing" in input.gaps(now))
            assertTrue("dps_source_evidence_missing" in input.gaps(now))
        }
    }

    @Test fun `transport cancellation propagates`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            Db.stockAnalysisInputs("600519", bars, now, StockInputTransport { throw CancellationException("fixture") })
        }
    }
}
