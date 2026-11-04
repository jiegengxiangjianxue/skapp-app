package com.skapp.app.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortfolioRiskEngineTest {

    private fun holding(
        code: String = "600000",
        closes: List<Double> = List(29) { 100.0 } + 80.0,
    ) = PortfolioRiskEngine.Holding(
        code = code,
        bars = closes.mapIndexed { i, close ->
            KBar(
                date = LocalDate.of(2026, 1, 1).plusDays(i.toLong()).toString(),
                open = close, close = close, high = close, low = close, vol = 10000.0,
            )
        },
    )

    @Test
    fun twentyPercentDrawdownTriggersExistingFifteenPercentWarning() {
        val result = PortfolioRiskEngine.assess(listOf(holding()))
        assertEquals(20.0, assertNotNull(result.maxDrawdownPct), 1e-9)
        assertTrue(result.advices.any { it.contains("历史上曾回撤") })
    }

    @Test
    fun fifteenPercentDrawdownTriggersWarningAtBoundary() {
        val result = PortfolioRiskEngine.assess(listOf(holding(closes = List(29) { 100.0 } + 85.0)))
        assertEquals(15.0, assertNotNull(result.maxDrawdownPct), 1e-9)
        assertTrue(result.advices.any { it.contains("历史上曾回撤") })
    }

    @Test
    fun drawdownBelowFifteenPercentDoesNotTriggerDrawdownWarning() {
        val result = PortfolioRiskEngine.assess(listOf(holding(closes = List(29) { 100.0 } + 86.0)))
        assertEquals(14.0, assertNotNull(result.maxDrawdownPct), 1e-9)
        assertTrue(result.advices.none { it.contains("历史上曾回撤") })
    }

    @Test
    fun invalidClosingPricesCannotProduceNumericRiskOrHealthyAdvice() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            val result = PortfolioRiskEngine.assess(listOf(holding(closes = List(29) { 100.0 } + invalid)))
            assertNull(result.volPct, "invalid close=$invalid")
            assertNull(result.maxDrawdownPct, "invalid close=$invalid")
            assertNull(result.avgCorr)
            assertTrue(result.missing.any { it.contains("600000") }, "invalid close=$invalid")
            assertEquals("未知", result.level, "invalid close=$invalid")
            assertTrue(result.advices.none { it.contains("未触发风险阈值") })
        }
    }

    @Test
    fun invalidIntermediateCloseIsNotSilentlySkipped() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            val closes = List(40) { 100.0 }.toMutableList().apply { this[15] = invalid }
            val result = PortfolioRiskEngine.assess(listOf(holding(closes = closes)))
            assertNull(result.volPct, "invalid close=$invalid")
            assertNull(result.maxDrawdownPct, "invalid close=$invalid")
            assertTrue(result.missing.any { it.contains("600000") })
            assertEquals("未知", result.level)
            assertTrue(result.advices.none { it.contains("未触发风险阈值") })
        }
    }

    @Test
    fun oneInvalidHoldingDoesNotTurnRemainingHoldingsIntoCompletePortfolio() {
        val result = PortfolioRiskEngine.assess(listOf(
            holding(),
            holding(code = "600001", closes = List(29) { 100.0 } + Double.NaN),
        ))
        assertNull(result.volPct)
        assertNull(result.maxDrawdownPct)
        assertNull(result.maxPair)
        assertTrue(result.missing.any { it.contains("600001") })
        assertEquals("未知", result.level)
        assertTrue(result.advices.none { it.contains("未触发风险阈值") })
    }

    @Test
    fun invalidPricesOutsideLookbackDoNotInvalidateCurrentWindow() {
        val result = PortfolioRiskEngine.assess(listOf(holding(closes = listOf(Double.NaN) + List(70) { 100.0 } + 80.0)))
        assertEquals(20.0, assertNotNull(result.maxDrawdownPct), 1e-9)
        assertNotNull(result.volPct)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun validPricesStillProduceFiniteRisk() {
        val closes = List(60) { i -> 100.0 + i + (i % 3) * 0.5 }
        val result = PortfolioRiskEngine.assess(listOf(holding(closes = closes)))
        assertTrue(assertNotNull(result.volPct).isFinite())
        assertTrue(assertNotNull(result.maxDrawdownPct).isFinite())
        assertTrue(result.missing.isEmpty())
    }
}
