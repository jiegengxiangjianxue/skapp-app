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
        val d = feeds.marketPhase!!.daily.last()   // 升序，最后一行是「今天」
        assertEquals("2026-10-27", d.date)
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
        val d = feeds.marketPhase!!.daily.last()
        assertEquals(-1, d.firstBoard)
        assertEquals(-1, d.height)
        assertEquals(-1.0, d.promo)
        assertTrue(feeds.missing.any { it.startsWith("market_phase.firstBoard") })
        assertTrue(feeds.marketPhase!!.labels["连板高度"]!!.contains("不可得"))
    }

    @Test
    fun `多日存档要给出逐日情绪序列而不是只有今天`() {
        val history = listOf(
            day("2026-10-22", listOf("600001")),
            day("2026-10-23", listOf("600001", "600002")),
            day("2026-10-24", listOf("600001", "600002", "600003")),
            day("2026-10-27", listOf("600001")),
        )
        val feeds = EngineFeedLive.assemble(
            pulse = pulse(), screened = emptyList(), globalIndices = emptyList(),
            snapshot = snapshot(), sectors = sectors(), history = history,
        )
        val daily = feeds.marketPhase!!.daily
        assertEquals(4, daily.size, "4 天存档应出 4 行走势")
        assertEquals(listOf("2026-10-22", "2026-10-23", "2026-10-24", "2026-10-27"), daily.map { it.date })
        // 高度逐日长起来再掉下去：1 → 2 → 3 → 4
        assertEquals(listOf(1, 2, 3, 4), daily.map { it.height })
    }

    @Test
    fun `实时快照要覆盖成今天的最后一行`() {
        val ts = 1_760_000_000_000L               // 2025-10-09 前后的一个交易日时点
        val today = CstTime.day(ts)
        val rows = (0 until 120).map { i ->
            val limitUp = i >= 2                   // 前 2 只封住，其余不涨停
            LiveMarket.Row(
                code = "60%04d".format(i), name = "股$i",
                price = if (limitUp) 11.0 else 10.5,
                pctChg = if (limitUp) 10.0 else 5.0,
                volumeLots = 1000.0, amount = 1e8, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0,
                volRatio = 1.2, mv = 1e9, mainNet = 1e6, ts = ts,
                high = 10.5, prevClose = 10.0, chg60d = 5.0,
            )
        }
        val feeds = EngineFeedLive.assemble(
            pulse = pulse(), screened = emptyList(), globalIndices = emptyList(),
            ts = ts, snapshot = rows, sectors = sectors(),
            history = listOf(day("2025-10-08", listOf("600002"))),
        )
        val last = feeds.marketPhase!!.daily.last()
        assertEquals(today, last.date)
        assertEquals(118, last.ge2 + last.firstBoard, "实时快照里 118 只涨停")
        assertEquals(1, last.ge2, "600002 昨天也涨停 → 连板 1 只")
        assertEquals(117, last.firstBoard)
    }

    @Test
    fun `封板率历史要用存档的摸过涨停标记算`() {
        val d = DayMarketSnapshot(
            date = "2026-10-27", ts = 0, final = true,
            entries = listOf(
                DayMarketSnapshot.Entry("600001", 10.0, 1e8, true, 11.0, touchedLimit = true),   // 封住
                DayMarketSnapshot.Entry("600002", 10.0, 1e8, true, 11.0, touchedLimit = true),   // 封住
                DayMarketSnapshot.Entry("600003", 6.0, 1e8, false, 10.6, touchedLimit = true),   // 摸过炸板
                DayMarketSnapshot.Entry("600004", 3.0, 1e8, false, 10.3, touchedLimit = false),
            ),
        )
        val s = LimitBoardCalc.compute(listOf(d))!!
        assertEquals(3, s.touched)
        assertEquals(1, s.broken)
        assertEquals(2.0 / 3.0, s.seal!!, 1e-9, "封板率 = 涨停 2 /（涨停 2 + 炸板 1）")
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
