package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChipDistributionEngineTest {

    private fun bars(n: Int, from: Double = 10.0, step: Double = 0.02, vol: Double = 1_000.0): List<ChipDistributionEngine.Bar> =
        (0 until n).map { i ->
            val c = from + i * step
            ChipDistributionEngine.Bar(
                date = "2026-01-${(i % 28 + 1).toString().padStart(2, '0')}",
                open = c - 0.01,
                high = c + 0.05,
                low = c - 0.05,
                close = c,
                volume = vol,
            )
        }

    @Test
    fun insufficientBarsYieldNull() {
        assertNull(ChipDistributionEngine.compute(emptyList()))
        assertNull(ChipDistributionEngine.compute(bars(1)))
    }

    @Test
    fun flatPriceRangeYieldsNull() {
        // 全部同高同低 → highAll == lowAll，没有可摊的价位区间
        val flat = List(10) { ChipDistributionEngine.Bar("2026-01-01", 10.0, 10.0, 10.0, 10.0, 100.0) }
        assertNull(ChipDistributionEngine.compute(flat))
    }

    @Test
    fun fallingMarketHasLowProfitRatio() {
        // 单边下跌 + 每日 5% 换手：老筹码逐步衰减但仍在，现价处于区间底部
        val falling = (0 until 60).map { i ->
            val c = 20.0 - i * 0.1
            ChipDistributionEngine.Bar("d$i", c + 0.02, c + 0.05, c - 0.05, c, 1_000.0, turnoverRate = 0.05)
        }
        val r = ChipDistributionEngine.compute(falling)!!
        assertTrue(r.profitRatio < 0.2, "下跌末端获利盘应偏低，实际 ${r.profitRatio}")
        assertTrue(r.conc90 > 0.0)
        assertTrue(r.costBias < 0.0, "现价低于平均成本应得到负乖离")
    }

    @Test
    fun risingMarketHasHighProfitRatio() {
        val rising = (0 until 60).map { i ->
            val c = 10.0 + i * 0.1
            ChipDistributionEngine.Bar("d$i", c - 0.02, c + 0.05, c - 0.05, c, 1_000.0, turnoverRate = 0.05)
        }
        val r = ChipDistributionEngine.compute(rising)!!
        assertTrue(r.profitRatio > 0.8, "上涨末端获利盘应偏高，实际 ${r.profitRatio}")
        assertTrue(r.costBias > 0.0)
    }

    @Test
    fun missingTurnoverRateIsFlagged() {
        val r = ChipDistributionEngine.compute(bars(30))!!
        assertEquals(30, r.fallbackDays, "全部缺换手率时每一天都应记入退化天数")
    }

    @Test
    fun realTurnoverRateSuppressesFallback() {
        val withTr = bars(30).map { it.copy(turnoverRate = 0.02) }
        val r = ChipDistributionEngine.compute(withTr)!!
        assertEquals(0, r.fallbackDays)
    }

    @Test
    fun limitUpBarWithHighEqualsLowStaysFinite() {
        val list = bars(20) + ChipDistributionEngine.Bar("d20", 12.0, 12.0, 12.0, 12.0, 5_000.0, turnoverRate = 0.5)
        val r = ChipDistributionEngine.compute(list)!!
        assertTrue(r.profitRatio in 0.0..1.0)
        assertTrue(r.conc90.isFinite() && r.costBias.isFinite())
    }
}
