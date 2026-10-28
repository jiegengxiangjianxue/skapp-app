package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 共振口径 + 停牌口径。两个都是「数字看着正常、含义是错的」的典型。
 */
class ResonanceAndPulseTest {

    @Test
    fun `全中性不能算成百分百共振`() {
        assertEquals(0.0, LocalEngine.resonanceOf(bull = 0, bear = 0, valid = 12))
    }

    @Test
    fun `共振取偏多与偏空的较大者`() {
        assertEquals(10.0 / 12, LocalEngine.resonanceOf(10, 1, 12)!!, 1e-9)
        assertEquals(0.5, LocalEngine.resonanceOf(6, 6, 12))
        assertEquals(1.0, LocalEngine.resonanceOf(12, 0, 12))
    }

    @Test
    fun `没有有效引擎时共振不可得`() {
        assertNull(LocalEngine.resonanceOf(0, 0, 0))
    }

    @Test
    fun `同族重复表态只算一票`() {
        // boll 与 keltner 都在答「价格站在通道哪一侧」，两者同时偏多不等于两份独立证据。
        // 旧口径逐引擎计票：4 个引擎里 2 个偏多 → 共振 50%；族级口径只有通道族一票 → 1/3。
        val engines = listOf(
            EngineScore("boll", 70.0, "bullish"),
            EngineScore("keltner", 70.0, "bullish"),
            EngineScore("trend", 50.0, "neutral"),
            EngineScore("choppiness", 50.0, "neutral"),
        )
        val families = LocalEngine.familiesOf(engines)
        assertEquals(3, families.size, "通道 / 趋势 / 波动结构 三族")
        assertEquals(1, families.count { it.dir > 0 }, "通道族只算一票")
        assertEquals(
            1.0 / 3,
            LocalEngine.resonanceOf(
                bull = families.count { it.dir > 0 },
                bear = families.count { it.dir < 0 },
                valid = families.size,
            )!!,
            1e-9,
        )
    }

    @Test
    fun `族内多空分歧时该族不表态`() {
        // trend 68（多头）与 vortex 32（空头）同属趋势族，净额 0 → 中性，而不是两边各算一票。
        val families = LocalEngine.familiesOf(
            listOf(
                EngineScore("trend", 68.0, "bullish"),
                EngineScore("vortex", 32.0, "bearish"),
            )
        )
        assertEquals(1, families.size)
        assertEquals(0, families.first().dir)
        assertEquals(0.0, LocalEngine.resonanceOf(0, 0, families.size))
    }

    @Test
    fun `停牌票不进涨跌平统计`() {
        fun row(code: String, pct: Double, price: Double, vol: Double) = LiveMarket.Row(
            code = code, name = "股$code", price = price, pctChg = pct, volumeLots = vol,
            amount = 1.0e8, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0, volRatio = 1.0,
            mv = 1.0e9, mainNet = 0.0, ts = 1_700_000_000_000L,
        )
        val rows = listOf(
            row("600001", 2.0, 10.2, 1000.0),
            row("600002", -2.0, 9.8, 1000.0),
            // 停牌：有行、有涨跌幅 0，但没有价、没有量
            row("600003", 0.0, 0.0, 0.0),
            row("600004", 0.0, 0.0, 0.0),
        )
        val p = LivePulse.of(rows)
        assertEquals(4, p.total)
        assertEquals(2, p.suspended, "停牌要单独计数")
        assertEquals(1, p.up)
        assertEquals(1, p.down)
        assertEquals(0, p.flat, "停牌不能算平盘，否则平盘数虚增、中位涨幅被拉向 0")
        assertEquals(0.5, p.breadth)
    }

    @Test
    fun `全部停牌时判无数据而不是平衡`() {
        val rows = (0 until 5).map { i ->
            LiveMarket.Row(
                code = "60001$i", name = "股$i", price = 0.0, pctChg = 0.0, volumeLots = 0.0,
                amount = 0.0, amplitude = 0.0, turnoverRate = 0.0, pe = 0.0, volRatio = 0.0,
                mv = 0.0, mainNet = 0.0, ts = 1_700_000_000_000L,
            )
        }
        val p = LivePulse.of(rows)
        assertEquals("无数据", p.label)
        assertEquals(0.0, p.phaseScore)
    }
}

/**
 * 板块周期分（cycle_v2）：以前恒为 0 的「假中性」现在必须有方向。
 */
class CycleScoreTest {

    private fun sectorRow(name: String, chg: Double, up: Int, down: Int, heat: Double) =
        LocalSectors.Row(
            name = name, n = up + down, chg1d = chg, heat = heat,
            up = up, down = down, limitUp = 0, limitDown = 0,
            leader = null, leaderPct = null,
        )

    @Test
    fun `板块普涨要给正分`() {
        val res = LocalSectors.Result(
            rows = listOf(
                sectorRow("半导体", 3.0, 40, 5, 5.0e10),
                sectorRow("算力", 2.2, 30, 6, 4.0e10),
            ),
            mapped = 81, total = 100, unmapped = 19, ts = "2026-10-27 10:00:00",
        )
        val missing = mutableListOf<String>()
        val score = MarketStateEngine.cycleScore(res, missing)!!
        assertTrue(score > 20, "板块普涨却给出 $score")
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `板块普跌要给负分`() {
        val res = LocalSectors.Result(
            rows = listOf(sectorRow("地产", -2.5, 5, 40, 3.0e10)),
            mapped = 45, total = 100, unmapped = 55, ts = "2026-10-27 10:00:00",
        )
        assertTrue(MarketStateEngine.cycleScore(res, mutableListOf())!! < -20)
    }

    @Test
    fun `没有板块数据时返回不可得而不是零`() {
        val missing = mutableListOf<String>()
        assertNull(MarketStateEngine.cycleScore(null, missing))
        assertTrue(missing.any { it.contains("cycle_v2") })
    }

    @Test
    fun `成交额大的板块更有话语权`() {
        val big = LocalSectors.Result(
            rows = listOf(
                sectorRow("大板块", 3.0, 40, 5, 9.0e10),
                sectorRow("小板块", -3.0, 2, 20, 1.0e9),
            ),
            mapped = 67, total = 100, unmapped = 33, ts = "",
        )
        val small = LocalSectors.Result(
            rows = listOf(
                sectorRow("大板块", -3.0, 2, 20, 9.0e10),
                sectorRow("小板块", 3.0, 40, 5, 1.0e9),
            ),
            mapped = 67, total = 100, unmapped = 33, ts = "",
        )
        val a = MarketStateEngine.cycleScore(big, mutableListOf())!!
        val b = MarketStateEngine.cycleScore(small, mutableListOf())!!
        assertTrue(a > b, "资金在哪个板块，分数就该跟到哪：$a vs $b")
    }
}
