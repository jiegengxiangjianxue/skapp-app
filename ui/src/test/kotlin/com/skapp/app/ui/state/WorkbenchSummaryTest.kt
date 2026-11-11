package com.skapp.app.ui.state

import com.skapp.app.data.LocalScreener
import com.skapp.app.data.MarketPulseRow
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.ui.UiState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WorkbenchSummaryTest {
    private val now = Instant.parse("2026-11-09T02:00:00Z").toEpochMilli()
    private val stamp = "2026-11-09T02:00:00Z"
    private fun text(state: UiState) = WorkbenchSummary.sections(state, now).flatMap { it.entries }.joinToString { it.text }
    private fun board(time: String = stamp) = mapOf("source" to "local", "status" to "ok", "ts" to time, "coverageVerified" to true,
        "market" to mapOf("涨" to 1234, "跌" to 4321, "主力净额(亿)" to -12.5),
        "sectors" to listOf(mapOf("name" to "电力", "chg_pct" to 1.8, "n" to 30)))

    @Test fun applyingBoardPublishesMatchingBoardAndSummaryAtomically() {
        val initial = UiState(summary = mapOf("text" to "旧摘要"))
        val result = WorkbenchSummary.applyBoard(initial, board(), now)
        assertEquals(board(), result.board)
        assertEquals(WorkbenchSummary.asMap(result, now), result.summary)
        assertTrue(result.summary["text"].toString().contains("1234"))
        val missing = WorkbenchSummary.applyBoard(result, emptyMap(), now)
        assertEquals(WorkbenchSummary.asMap(missing, now), missing.summary)
        assertEquals("insufficient", missing.summary["status"])
        assertFalse(missing.summary["text"].toString().contains("1234"))
        val stale = WorkbenchSummary.applyBoard(result, board("2026-11-08T02:00:00Z"), now)
        assertFalse(stale.summary["text"].toString().contains("1234"))
    }

    @Test fun onlyWholeMarketUsesWholeMarketCountsAndMissingIsNotZero() {
        val state = UiState(board = board())
        assertTrue(text(state).contains("1234"))
        assertTrue(text(state).contains("-12.50"))
        assertTrue(text(state).contains("电力"))
        assertTrue(text(state).contains("成交额 暂无数据"))
    }

    @Test fun pulseSubsetIsNeverMarketBreadth() {
        val row = MarketPulseRow("600519", "茅台", 100.0, 1.0, null, volRatio = null, turnover = null, mainNet = null)
        val state = UiState(marketPulse = listOf(row))
        assertTrue(text(state).contains("只是候选样本"))
        assertFalse(text(state).contains("上涨 1"))
    }

    @Test fun expiredAndUnprovenBoardNumbersAreNotPublished() {
        assertFalse(text(UiState(board = board("2026-11-08T02:00:00Z"))).contains("1234"))
        assertFalse(text(UiState(board = board() + ("source" to "cloud"))).contains("1234"))
        assertFalse(text(UiState(board = board("2026-11-09T03:00:00Z"))).contains("1234"))
    }

    @Test fun onlyFreshSourcedNewsIsCountedAndNoDirectionIsInvented() {
        val state = UiState(news = NewsSnapshot(items = listOf(
            mapOf("title" to "有效利好", "source" to "公开源", "time" to stamp, "direction" to "pos"),
            mapOf("title" to "未定性消息", "source" to "公开源", "time" to stamp),
            mapOf("title" to "旧利空", "source" to "公开源", "time" to "2026-11-01T02:00:00Z", "direction" to "neg"),
            mapOf("title" to "无来源", "time" to stamp),
        )))
        assertTrue(text(state).contains("利好 1 / 利空 0"))
        assertTrue(text(state).contains("未定性 1"))
        assertFalse(text(state).contains("旧利空"))
    }

    @Test fun localNewsAnalysisIsCountedOnlyForSameSourceAndTime() {
        val item = mapOf("title" to "政策消息", "source" to "公开源", "time" to stamp)
        val calc = com.skapp.app.data.NewsCalc("政策消息", source = "公开源", ts = stamp, direction = "pos")
        val state = UiState(news = NewsSnapshot(items = listOf(item)), newsCalcs = listOf(calc))
        assertTrue(text(state).contains("利好 1 / 利空 0"))
        assertTrue(text(state.copy(newsCalcs = listOf(calc.copy(source = "其他源")))).contains("未定性 1"))
        assertTrue(text(state.copy(newsCalcs = listOf(calc.copy(ts = "2026-11-08T02:00:00Z")))).contains("未定性 1"))
        assertTrue(text(state.copy(news = NewsSnapshot(items = listOf(item + ("direction" to "neg"))))).contains("利好 0 / 利空 1"))
    }

    @Test fun technicalCandidateWithMissingDpsDoesNotBecomeRecommendation() {
        val row = LocalScreener.Screened(code = "600519", name = "测试", price = 10.0, pctChg = 1.0,
            volRatio = null, turnover = null, pe = null, mv = null, engine = null,
            detailStatus = "insufficient", canonicalScore = 80.0, reason = "DPS缺失", snapshotAtMs = now)
        val state = UiState(screened = listOf(row), screenedAt = stamp)
        assertTrue(text(state).contains("有效推荐 0"))
        assertTrue(text(state).contains("观察 · 测试"))
        assertTrue(text(state).contains("DPS缺失"))
        assertFalse(text(state.copy(screened = listOf(row.copy(snapshotAtMs = now - 600_000)))).contains("观察 · 测试"))
    }

    @Test fun incompletePagesCannotPublishMarketTotals() {
        val state = UiState(board = board() + mapOf("coverageVerified" to false, "sampleSize" to 100, "expectedTotal" to 5900))
        assertFalse(text(state).contains("1234"))
        assertTrue(text(state).contains("分页不完整"))
        assertTrue(text(state).contains("电力"))
    }

    @Test fun currentCalculationCannotRenewOldQuotesOrExpiredFinancialEvidence() {
        val snapshot = mapOf("atMs" to now, "source" to "LocalScreener/LiveMarket.Row")
        val evidence = mapOf("eps" to 1.0, "dps" to 0.1, "reportPeriod" to "2026-06-30",
            "adjustmentEvidence" to "cnfin:hfq_factor:test", "source" to "fixture",
            "snapshotAtMs" to now - 1000, "expiresAtMs" to now + 1000, "barsBound" to true)
        val pick = com.skapp.app.data.PickSignal("600519", "有效候选", pctChg = 1.0, buyable = true, snapshotStatus = "live")
        fun state(snap: Map<String, Any>, e: Map<String, Any>) = UiState(localRealtimePicks =
            com.skapp.app.data.StructuredPickArtifact(calculationTime = stamp, date = "2026-11-09", status = "ok", picks = listOf(pick),
                metadata = mapOf("snapshotByCode" to mapOf("600519" to snap), "evidenceByCode" to mapOf("600519" to e))))
        assertTrue(text(state(snapshot, evidence)).contains("有效推荐 1"))
        assertTrue(text(state(snapshot + ("atMs" to now - 300_001), evidence)).contains("有效推荐 0"))
        assertTrue(text(state(snapshot, evidence + ("expiresAtMs" to now - 1))).contains("有效推荐 0"))
        assertTrue(text(state(snapshot, evidence + ("barsBound" to false))).contains("有效推荐 0"))
        assertTrue(text(state(snapshot, emptyMap())).contains("有效推荐 0"))
        assertTrue(text(state(emptyMap(), evidence)).contains("有效推荐 0"))
    }

    private val closedNow = Instant.parse("2026-11-09T16:10:00Z").toEpochMilli()
    private val closeStamp = "2026-11-09T07:00:00Z"
    private val closeTs = Instant.parse(closeStamp).toEpochMilli()
    private fun historical() = board(closeStamp) + mapOf(
        "status" to "historical", "snapshotMode" to "historical", "sourceDate" to "2026-11-09",
        "minTs" to closeTs, "maxTs" to closeTs, "sampleSize" to 5900,
        "reason" to "2026-11-09 历史行情复盘，非实时、不用于当前推荐",
    )

    @Test fun completeSameDayHistoryIsArchiveWithoutRenewingSourceTimeOrRecommendations() {
        val board = historical()
        val state = WorkbenchSummary.applyBoard(UiState(), board, closedNow)
        val sections = WorkbenchSummary.sections(state, closedNow)
        val market = sections.first { it.id == "market" }
        assertEquals("historical", market.status)
        assertTrue(market.source.contains("2026-11-09"))
        assertTrue(market.source.contains("非实时"))
        assertTrue(market.entries.any { it.text.contains("1234") })
        assertTrue(market.entries.any { it.text.contains("成交额 暂无数据") })
        assertEquals("historical", sections.first { it.id == "sectors" }.status)
        assertEquals(closeStamp, market.timestamp)
        assertEquals(closeStamp, state.summary["ts"])
        assertEquals(closeTs, state.board["minTs"])
        assertEquals("historical", state.summary["status"])
        assertEquals("local_historical", state.summary["source"])
        assertEquals("2026-11-09", state.summary["sourceDate"])
        assertTrue(sections.first { it.id == "candidates" }.entries.any { it.text.contains("有效推荐 0") })
    }

    @Test fun unknownFutureAndMixedSourceTimesCannotProduceHistoricalDate() {
        assertEquals("2026-11-09", WorkbenchSummary.historicalSourceDate(List(3000) { closeTs }, closedNow))
        for (times in listOf(emptyList(), listOf(0L, closeTs), listOf(-1L), listOf(closedNow + 1),
            listOf(closeTs, Instant.parse("2026-11-08T07:00:00Z").toEpochMilli()))) {
            assertNull(WorkbenchSummary.historicalSourceDate(times, closedNow))
        }
    }

    @Test fun invalidHistoryProofDoesNotExposeMarketOrSectors() {
        val invalid = listOf(
            mapOf("minTs" to 0L), mapOf("ts" to null), mapOf("ts" to Instant.ofEpochMilli(closedNow).toString()),
            mapOf("sourceDate" to null), mapOf("sourceDate" to "2026-11-11"),
            mapOf("maxTs" to Instant.parse("2026-11-08T07:00:00Z").toEpochMilli()),
            mapOf("maxTs" to closedNow + 1), mapOf("coverageVerified" to false),
            mapOf("sampleSize" to 2999), mapOf("snapshotMode" to "live"), mapOf("source" to "cloud"),
        )
        for (change in invalid) {
            val sections = WorkbenchSummary.sections(UiState(board = historical() + change), closedNow)
            for (id in listOf("market", "sectors")) assertEquals("insufficient", sections.first { it.id == id }.status)
            assertFalse(sections.flatMap { it.entries }.any { it.text.contains("1234") || it.text.contains("电力") })
        }
    }

    @Test fun scopedHistoricalSampleNeverClaimsWholeMarketOrProducesRecommendations() {
        val sample = historical() + mapOf("status" to "historical_sample", "snapshotMode" to "historical_sample",
            "coverageVerified" to false, "sampleSize" to 5105)
        val sections = WorkbenchSummary.sections(UiState(board = sample), closedNow)
        val market = sections.first { it.id == "market" }
        assertEquals("historical_sample", market.status)
        assertTrue(market.source.contains("5105"))
        assertTrue(market.source.contains("不代表全市场"))
        assertTrue(market.entries.any { it.text.contains("1234") })
        assertTrue(sections.first { it.id == "candidates" }.entries.any { it.text.contains("有效推荐 0") })
        assertFalse(WorkbenchSummary.historicalBoard(sample + ("coverageVerified" to true), closedNow))
        assertFalse(WorkbenchSummary.historicalBoard(sample + ("sampleSize" to 2999), closedNow))
    }

    @Test fun historyWithoutMappingKeepsSectorInsufficientDespiteMarketArchive() {
        val state = UiState(board = historical() - "sectors")
        val sections = WorkbenchSummary.sections(state, closedNow)
        assertEquals("historical", sections.first { it.id == "market" }.status)
        assertEquals("insufficient", sections.first { it.id == "sectors" }.status)
    }

    @Test fun oldHistoryDuringTradingOrAuctionNeverBecomesCurrentBoard() {
        for (time in listOf("2026-11-10T02:00:00Z", "2026-11-10T01:20:00Z")) {
            val current = Instant.parse(time).toEpochMilli()
            assertFalse(WorkbenchSummary.historicalBoard(historical(), current))
            assertEquals("insufficient", WorkbenchSummary.sections(UiState(board = historical()), current).first { it.id == "market" }.status)
        }
    }

    @Test fun historicalBoardDoesNotRenewPreviousClosePickQuotes() {
        val pick = com.skapp.app.data.PickSignal("600519", "历史候选", pctChg = 1.0, buyable = true, snapshotStatus = "live")
        val evidence = mapOf("eps" to 1.0, "dps" to 0.1, "reportPeriod" to "2026-06-30",
            "adjustmentEvidence" to "cnfin:hfq_factor:test", "source" to "fixture",
            "snapshotAtMs" to closeTs, "expiresAtMs" to closedNow + 1000, "barsBound" to true)
        val artifact = com.skapp.app.data.StructuredPickArtifact(
            calculationTime = Instant.ofEpochMilli(closedNow).toString(), date = "2026-11-10", status = "ok", picks = listOf(pick),
            metadata = mapOf("snapshotByCode" to mapOf("600519" to mapOf("atMs" to closeTs, "source" to "LocalScreener/LiveMarket.Row")),
                "evidenceByCode" to mapOf("600519" to evidence)))
        val sections = WorkbenchSummary.sections(UiState(board = historical(), localRealtimePicks = artifact), closedNow)
        assertTrue(sections.first { it.id == "candidates" }.entries.any { it.text.contains("有效推荐 0") })
        assertFalse(sections.flatMap { it.entries }.any { it.text.contains("推荐 · 历史候选") })
    }

    @Test fun emptyStateRemainsInsufficientWithoutManufacturedTimestamp() {
        val result = WorkbenchSummary.asMap(UiState(summary = mapOf("source" to "cloud", "status" to "ok")), now)
        assertEquals("insufficient", result["status"])
        assertNull(result["ts"])
        assertEquals("local_realtime", result["source"])
    }
}
