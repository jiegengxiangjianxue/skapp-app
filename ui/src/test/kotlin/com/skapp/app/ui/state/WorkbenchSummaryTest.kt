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

    @Test fun emptyStateRemainsInsufficientWithoutManufacturedTimestamp() {
        val result = WorkbenchSummary.asMap(UiState(summary = mapOf("source" to "cloud", "status" to "ok")), now)
        assertEquals("insufficient", result["status"])
        assertNull(result["ts"])
        assertEquals("local_realtime", result["source"])
    }
}
