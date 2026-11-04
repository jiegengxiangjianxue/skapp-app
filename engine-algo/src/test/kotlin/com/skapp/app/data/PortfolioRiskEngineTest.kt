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
    fun disjointDatesCannotProduceCompletePortfolioRisk() {
        val first = holding(closes = List(40) { 100.0 + it + (it % 3) * 3.0 })
        val second = first.copy(code = "600001", bars = first.bars.map {
            it.copy(date = LocalDate.parse(it.date).plusDays(100).toString())
        })
        val result = PortfolioRiskEngine.assess(listOf(first, second))
        assertNull(result.volPct)
        assertNull(result.maxDrawdownPct)
        assertNull(result.avgCorr)
        assertEquals("未知", result.level)
        assertTrue(result.missing.any { it.contains("共同收益区间") })
    }

    @Test
    fun sufficientCommonIntervalsMatchTheirOwnPortfolio() {
        val closes = List(55) { 100.0 + it + (it % 3) * 3.0 }
        val all = holding(closes = closes)
        val first = all.copy(bars = all.bars.take(45))
        val second = all.copy(code = "600001", bars = all.bars.drop(10).map {
            it.copy(close = it.close * 2.0)
        })
        val expected = PortfolioRiskEngine.assess(listOf(all.copy(bars = all.bars.subList(10, 45))))
        val result = PortfolioRiskEngine.assess(listOf(first, second))
        assertEquals(1.0, assertNotNull(result.avgCorr), 1e-9)
        assertEquals(assertNotNull(expected.volPct), assertNotNull(result.volPct), 1e-9)
        assertEquals(assertNotNull(expected.maxDrawdownPct), assertNotNull(result.maxDrawdownPct), 1e-9)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun missingDateDoesNotMatchCrossPeriodReturnToSingleDayReturn() {
        val first = holding(closes = List(31) { 100.0 + it + (it % 3) * 3.0 })
        val second = first.copy(code = "600001", bars = first.bars.filterIndexed { i, _ -> i != 15 })
        val result = PortfolioRiskEngine.assess(listOf(first, second))
        assertNull(result.volPct)
        assertNull(result.maxDrawdownPct)
        assertNull(result.avgCorr)
        assertEquals("未知", result.level)
        assertTrue(result.missing.any { it.contains("共同收益区间") })
    }

    @Test
    fun duplicateDescendingAndInvalidDatesInvalidateWholePortfolio() {
        val valid = holding(closes = List(40) { 100.0 + it + (it % 3) * 3.0 })
        for (date in listOf(valid.bars[19].date, valid.bars[18].date, "2026-02-30", "2026-1-21", "")) {
            val invalid = valid.copy(code = "600001", bars = valid.bars.mapIndexed { i, bar ->
                if (i == 20) bar.copy(date = date) else bar
            })
            val result = PortfolioRiskEngine.assess(listOf(valid, invalid))
            assertNull(result.volPct, "date=$date")
            assertNull(result.maxDrawdownPct, "date=$date")
            assertNull(result.avgCorr, "date=$date")
            assertNull(result.maxPair, "date=$date")
            assertEquals("未知", result.level, "date=$date")
            assertTrue(result.missing.any { it.contains("600001") && it.contains("日期") })
            assertTrue(result.advices.none { it.contains("未触发风险阈值") })
        }
    }

    @Test
    fun identicalDatesPreserveExistingPortfolioMath() {
        val result = PortfolioRiskEngine.assess(listOf(holding(), holding(code = "600001")))
        assertEquals(1.0, assertNotNull(result.avgCorr), 1e-9)
        assertEquals(20.0, assertNotNull(result.maxDrawdownPct), 1e-9)
        assertEquals(0.2 / kotlin.math.sqrt(29.0) * kotlin.math.sqrt(250.0) * 100.0,
            assertNotNull(result.volPct), 1e-9)
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
