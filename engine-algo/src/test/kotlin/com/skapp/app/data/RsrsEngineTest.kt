package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RsrsEngineTest {

    @Test
    fun insufficientDataYieldsAllNull() {
        val highs = List(10) { 10.0 }
        val lows = List(10) { 9.0 }
        val z = RsrsEngine.series(highs, lows)
        assertEquals(10, z.size)
        assertTrue(z.all { it == null })
    }

    @Test
    fun flatHighsHaveNoSlope() {
        // high 恒定 → sxx = 0，β 算不出，整列为 null（不拿 0 冒充）
        val highs = List(60) { 10.0 }
        val lows = List(60) { 9.0 + (it % 3) }
        assertTrue(RsrsEngine.series(highs, lows).all { it == null })
    }

    @Test
    fun constantSlopeHasZeroVariance() {
        // 完全线性 high = 2*low + 1：β 恒为 2，z-score 分母为 0 → 仍为 null
        val lows = (0 until 60).map { 10.0 + it }
        val highs = lows.map { 2 * it + 1 }
        assertTrue(RsrsEngine.series(highs, lows, n = 18, m = 20).all { it == null })
    }

    @Test
    fun varyingSlopeProducesStandardScore() {
        val lows = (0 until 80).map { 10.0 + (it % 7) }
        val highs = lows.mapIndexed { i, l -> l * (1.0 + 0.01 * (i % 5)) + 3 }
        val z = RsrsEngine.series(highs, lows, n = 18, m = 20)
        assertEquals(80, z.size)
        assertTrue(z.drop(18 + 20).any { it != null }, "斜率有波动时应有标准分")
        assertEquals(z.last(), RsrsEngine.current(highs, lows, n = 18, m = 20))
    }

    @Test
    fun mismatchedLengthsYieldNull() {
        assertTrue(RsrsEngine.series(List(40) { 10.0 }, List(39) { 9.0 }).all { it == null })
    }
}
