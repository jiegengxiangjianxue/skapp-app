package com.skapp.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrendForecastTest {

    private fun bars(closes: List<Double>): List<KBar> = closes.mapIndexed { i, c ->
        KBar(
            date = "2026-01-${(i % 28 + 1).toString().padStart(2, '0')}",
            open = c * 0.995,
            close = c,
            high = c * 1.01,
            low = c * 0.99,
            vol = 10000.0 + i * 10,
        )
    }

    @Test
    fun risingSeriesIsBullish() {
        val rising = (1..120).map { 10.0 + it * 0.1 }
        val r = TrendForecast.compute(bars(rising))
        assertNotNull(r.macd)
        assertTrue(r.macd!!.hist > 0, "MACD 柱应为正，实际 ${r.macd!!.hist}")
        assertEquals("偏多", r.horizons.first().direction)
        r.horizons.forEach { h ->
            assertTrue(h.lowPct < 0.0 && h.highPct > 0.0, "区间应跨现价：${h.lowPct}~${h.highPct}")
            assertTrue(h.confidence in 0.0..0.70, "置信度越界：${h.confidence}")
        }
        assertTrue(r.horizons.first().confidence >= r.horizons.last().confidence, "期数越长置信度不应升高")
    }

    @Test
    fun fallingSeriesIsBearish() {
        val falling = (1..120).map { 30.0 - it * 0.1 }
        val r = TrendForecast.compute(bars(falling))
        assertTrue(r.macd!!.hist < 0, "MACD 柱应为负，实际 ${r.macd!!.hist}")
        assertEquals("偏空", r.horizons.first().direction)
    }

    @Test
    fun shortHistoryExplainsInsteadOfGuessing() {
        val r = TrendForecast.compute(bars((1..30).map { 10.0 + it * 0.1 }))
        assertTrue(r.horizons.isEmpty(), "不足 60 根不应给方向")
        assertTrue(r.notes.first().contains("60"))
    }

    @Test
    fun macdOnFlatSeriesIsZero() {
        val flat = List(80) { 5.0 }
        val m = TrendForecast.macd(flat)
        assertNotNull(m)
        assertTrue(abs(m!!.dif) < 1e-9, "DIF 应为 0，实际 ${m.dif}")
        assertTrue(abs(m.dea) < 1e-9, "DEA 应为 0，实际 ${m.dea}")
        assertTrue(abs(m.hist) < 1e-9, "柱应为 0，实际 ${m.hist}")
    }
}
