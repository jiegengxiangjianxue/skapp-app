package com.skapp.app.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CentralAnalysisTest {
    private val now = OffsetDateTime.parse("2026-11-02T10:15:00+08:00").toInstant().toEpochMilli()
    private val spec = EngineSpecLoader.loadBundled().spec
    private val bars = (0..249).map { i ->
        val price = 10.0 + i * 0.01
        KBar(LocalDate.parse("2026-11-02").minusDays((249-i).toLong()).toString(), price,
            price+0.03, price+0.08, price-0.05, 1000.0)
    }
    private fun row(ts: Long = now) = LiveMarket.Row("600001", "fixture", 12.52, 3.0,
        10000.0, 1e8, 3.0, 2.0, 12.0, 1.5, 1e10, 1e7, ts)
    private fun event(code: String = "600001", time: Long? = now-1000, source: String? = "fixture") =
        EngineChain.NewsEvidence(code, "event-1", "fixture negative event", "neg", null,
            "verified fixture evidence", time, source)

    @Test fun compactQuoteTimesAreCalendarDatesNotEpochMilliseconds() {
        val expected = OffsetDateTime.parse("2026-11-12T16:14:52+08:00").toInstant().toEpochMilli()
        assertEquals(expected, CentralAnalysis.timeOf("20261112161452"))
        assertEquals(expected, CentralAnalysis.timeOf(expected.toString()))
        assertEquals(expected, CentralAnalysis.timeOf((expected / 1000).toString()))
        assertNull(CentralAnalysis.timeOf("20260230161452"))
        assertNull(CentralAnalysis.timeOf("20261112241452"))
    }

    @Test fun allPanelsCanSupplyFlowNewsAndIndexToSameReview() {
        val result = CentralAnalysis.compute("600001", bars, listOf(row()),
            news = listOf(event()), newsAvailable = true,
            index = IndexDaily.Result("sh000001", bars, "fixture index"), nowMs = now)
        assertNotNull(result.flow?.score)
        assertTrue(result.votes.any { it.indicator == "flow" })
        assertNotNull(result.regime)
        assertTrue(result.provenance.any { it.contains("fixture index") })
        assertTrue(result.degraded.none { it.contains("已校验") })
        assertEquals(1, result.news?.usable)
        assertTrue(result.riskFlag.orEmpty().contains("消息风险"))
        assertNotNull(result.canonicalScore)
    }

    @Test fun staleRowAndOtherStockNewsCannotLeakIntoCurrentStock() {
        val result = CentralAnalysis.compute("600001", bars, listOf(row(now-600_000)),
            news = listOf(event(code = "600002")), newsAvailable = true, nowMs = now)
        assertNull(result.flow)
        assertEquals(0, result.news?.matched)
        assertNull(result.riskFlag)
    }

    @Test fun newsMissingSourceFutureOrStaleIsNotUsedForRisk() {
        for (news in listOf(event(source = null), event(time = now+1), event(time = now-86_400_001))) {
            val result = CentralAnalysis.compute("600001", bars, emptyList(), news = listOf(news),
                newsAvailable = true, nowMs = now)
            assertEquals(0, result.news?.usable)
            assertEquals(1, result.news?.rejected)
            assertNull(result.riskFlag)
        }
    }

    @Test fun historicalMoneyFlowIsNotInventedCrossSectionOrFiveDayData() {
        val flow = CentralAnalysis.flowOf("600001", null,
            listOf(mapOf("date" to "2026-11-02", "main_ratio" to 0.03, "source" to "fixture")), bars, spec, now)
        assertNotNull(flow?.score)
        assertEquals("observed_main", flow?.tier)
        assertEquals(0.03, flow?.pctMain)
        val live = listOf(mapOf("time" to "2026-11-02 10:15", "main_net" to 3_000_000.0))
        val minute = listOf(mapOf("time" to "1015", "amount" to 100_000_000.0))
        assertNull(CentralAnalysis.flowOf("600001", null,
            listOf(mapOf("date" to "2026-11-02", "main_ratio" to 0.03)), bars, spec, now))
        assertNull(flow?.fsiPct)
        assertNull(flow?.m5Ratio)
        assertNull(FlowEngine.fromMainRatio("600001", 5.0, "invalid"))
    }

    @Test fun cloudWeightZeroReallyDisablesVoteAndKeepsActualSource() {
        val document = JSONObject("""{"ts":"2026-11-01T10:00:00+08:00","weights":{"flow":0,"SuperTrend":1.2}}""")
        val parameters = CentralAnalysis.parametersOf(spec, document)
        assertEquals(0.0, parameters.spec.weights["flow"])
        assertEquals(1.2, parameters.spec.weights["SuperTrend"])
        assertTrue(parameters.spec.weightsSource.contains("2026-11-01"))
        val result = CentralAnalysis.compute("600001", bars, listOf(row()), params = parameters, nowMs = now)
        assertTrue(result.votes.none { it.indicator == "flow" })
    }

    @Test fun invalidCloudWeightsAreRejectedAsAWhole() {
        val parameters = CentralAnalysis.parametersOf(spec,
            JSONObject("""{"ts":"2026-11-01T10:00:00+08:00","weights":{"SuperTrend":-1}}"""))
        assertEquals(spec.weights, parameters.spec.weights)
        assertTrue(parameters.notes.any { it.contains("拒收") })
    }
    @Test fun liveMoneyAndTurnoverMustHaveSameDateAndMinute() {
        val live = listOf(mapOf("time" to "2026-11-02 10:15", "main_net" to 3_000_000.0,
            "code" to "600001", "source" to "fixture"))
        val minute = listOf(mapOf("time" to "1015", "amount" to 100_000_000.0))
        val flow = CentralAnalysis.intradayFlowOf("600001", live, minute, "20261102", now, spec)
        assertEquals(0.03, flow?.pctMain)
        assertNull(CentralAnalysis.intradayFlowOf("600002", live, minute, "20261102", now, spec))
        assertNull(CentralAnalysis.intradayFlowOf("600001", live, minute, "20261101", now, spec))
        assertNull(CentralAnalysis.intradayFlowOf("600001", live,
            listOf(mapOf("time" to "1014", "amount" to 100_000_000.0)), "20261102", now, spec))
        assertNull(CentralAnalysis.intradayFlowOf("600001", live, minute, "20261102", now+600_000, spec))
    }

    @Test fun sectorMembersCannotBecomeDirectAdverseCompanyEvidence() {
        fun stock(code: String) = NewsStockCalc(code, null, null, null, null, null, signal = null)
        val news = NewsCalc("600001 negative event in sector", source = "fixture",
            ts = "2026-11-02T10:00:00+08:00", stocks = listOf(stock("600001"), stock("600002")),
            impactCodes = listOf("600002"), direction = "neg", negativeReason = "fixture",
            directCodes = listOf("600001"))
        assertEquals(listOf("600001"), CentralAnalysis.newsOf(listOf(news)).map { it.code })
    }

    @Test fun publishedAtAndBearAliasReachRawNewsReview() {
        val snapshot = NewsSnapshot(items = listOf(mapOf("title" to "600001 company announcement",
            "source" to "fixture", "direction" to "bear", "negative_reason" to "verified regulatory filing",
            "published_at" to "2026-11-02T10:14:00+08:00")))
        val events = CentralAnalysis.newsFromSnapshot(snapshot, emptyList())
        val result = CentralAnalysis.compute("600001", bars, emptyList(), news = events,
            newsAvailable = true, nowMs = now)
        assertEquals(1, result.news?.usable)
        assertTrue(result.riskFlag.orEmpty().contains("消息风险"))
    }

    @Test fun unknownReasonCannotTriggerNegativeNewsVeto() {
        val result = CentralAnalysis.compute("600001", bars, emptyList(),
            news = listOf(event().copy(negativeReason = "unknown: no verifiable evidence")),
            newsAvailable = true, nowMs = now)
        assertNull(result.riskFlag)
    }

    @Test fun rawNewsCanReachReviewBeforeAnyStockDeepCalculation() {
        val snapshot = NewsSnapshot(items = listOf(mapOf("title" to "600001 遭立案调查",
            "source" to "fixture", "direction" to "pos", "time" to "2026-11-02 10:14:00")))
        val events = CentralAnalysis.newsFromSnapshot(snapshot, emptyList())
        assertEquals(listOf("600001"), events.map { it.code })
        assertEquals("neg", events.single().direction)
        val chain = CentralAnalysis.compute("600001", bars, emptyList(), news = events,
            newsAvailable = true, nowMs = now)
        assertEquals(1, chain.news?.usable)
        assertTrue(chain.riskFlag.orEmpty().contains("消息风险"))
    }

}
