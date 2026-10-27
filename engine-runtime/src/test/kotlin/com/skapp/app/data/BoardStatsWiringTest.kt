package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「存档 → 装配」这一段是否真的把不可得字段填上了。
 *
 * 这是本轮补全的关键接线：如果 `assemble` 没把 [LimitBoardCalc] 的结果写进
 * `market_phase.daily`，界面上连板高度会永远显示「不可得」——而数据其实已经攒好了。
 */
class BoardStatsWiringTest {

    private fun day(date: String, limitUp: List<String>, sectors: List<DayMarketSnapshot.SectorDay> = emptyList()) =
        DayMarketSnapshot(
            date = date, ts = 0, final = true,
            entries = limitUp.map { DayMarketSnapshot.Entry(it, 10.0, 1e8, true, 11.0) },
            sectors = sectors,
        )

    private fun pulse() = LivePulse(
        ts = 1_700_000_000_000L, total = 5000, up = 3000, down = 1800, flat = 200,
        limitUp = 2, limitDown = 3, medianPct = 0.8, breadth = 3000.0 / 4800,
        totalAmount = 8.0e11, mainNetTotal = 1.0e10, phaseScore = 25.0, label = "偏强",
        inflow = emptyList(), outflow = emptyList(),
    )

    private fun snapshot() = (0 until 120).map { i ->
        LiveMarket.Row(
            code = "60%04d".format(i), name = "股$i", price = 10.0 + i * 0.01, pctChg = 1.0,
            volumeLots = 1000.0, amount = 1e8, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0,
            volRatio = 1.2, mv = 1e9, mainNet = 1e6, ts = 1_700_000_000_000L,
            high = 10.1, prevClose = 9.9, chg60d = 5.0 + i * 0.1,
        )
    }

    private fun sectors() = LocalSectors.Result(
        rows = listOf(
            LocalSectors.Row("半导体", 40, 2.5, 5e10, 35, 5, 3, 2, "某龙头", 9.9),
            LocalSectors.Row("地产", 60, -1.5, 3e10, 10, 50, 0, 3, "某房企", 1.0),
        ),
        mapped = 100, total = 120, unmapped = 20, ts = "2026-10-27 10:00:00",
    )

    @Test
    fun `有两天存档时连板高度与晋级率要真的填上`() {
        val history = listOf(
            day("2026-10-24", listOf("600001", "600002")),
            day("2026-10-27", listOf("600001", "600003")),
        )
        val feeds = EngineFeedLive.assemble(
            pulse = pulse(),
            screened = emptyList(),
            globalIndices = emptyList(),
            snapshot = snapshot(),
            sectors = sectors(),
            history = history,
        )
        val d = feeds.marketPhase!!.daily.first()
        assertEquals(2, d.height, "600001 昨日+今日连板 → 高度 2")
        assertEquals(1, d.firstBoard, "600003 是首板")
        assertEquals(1, d.ge2)
        assertEquals(0.5, d.promo, 1e-9, "昨日涨停 2 只，今日连板 1 只 → 50%")
        assertTrue(feeds.marketPhase!!.labels["连板高度"]!!.startsWith("2 板"))
        assertFalse(
            feeds.missing.any { it.startsWith("market_phase.firstBoard") },
            "已经能算了就不该再报不可得：${feeds.missing}",
        )
    }

    @Test
    fun `没有存档时保持不可得而不是零`() {
        val feeds = EngineFeedLive.assemble(
            pulse = pulse(),
            screened = emptyList(),
            globalIndices = emptyList(),
            snapshot = snapshot(),
            sectors = sectors(),
            history = emptyList(),
        )
        val d = feeds.marketPhase!!.daily.first()
        assertEquals(-1, d.firstBoard)
        assertEquals(-1, d.height)
        assertEquals(-1.0, d.promo)
        assertTrue(feeds.missing.any { it.startsWith("market_phase.firstBoard") })
        assertTrue(feeds.marketPhase!!.labels["连板高度"]!!.contains("不可得"))
    }

    @Test
    fun `板块行要带上 5 日动量与连续走强天数`() {
        val history = (0 until 6).map { i ->
            day(
                "2026-10-2$i",
                listOf("600001"),
                sectors = listOf(DayMarketSnapshot.SectorDay("半导体", 1.0, 30, 5, 5e10)),
            )
        }
        val feeds = EngineFeedLive.assemble(
            pulse = pulse(),
            screened = emptyList(),
            globalIndices = emptyList(),
            snapshot = snapshot(),
            sectors = sectors(),
            history = history,
        )
        val row = feeds.cyclePool!!.rows.first { it.name == "半导体" }
        assertNotNull(row.ret5d)
        assertEquals(6, row.upStreak, "6 天全红 → 连续走强 6 天")
        assertTrue(row.ret5d!! > 5.0, "5 天各涨 1% 复利约 5.1%，实际 ${row.ret5d}")
        // 地产没有历史数据 → 留空而不是 0
        assertEquals(null, feeds.cyclePool!!.rows.first { it.name == "地产" }.ret5d)
    }
}
