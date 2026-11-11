package com.skapp.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradingDecisionTest {
    private val now = 1_794_187_200_000L
    private val bars = listOf(KBar("2026-11-09", 10.0, 10.0, 11.0, 9.0, 100.0))
    private val evidence = LocalScreener.DataEvidence(1.0, 0.1, "2026-06-30", "cnfin:hfq_factor:fixture",
        "fixture", now - 1000, now + 60_000, "600519", StockAnalysisInputs.barsFingerprint(bars))
    private val levels = KeyLevels.Levels(12.0, 11.0, 11.0, 10.5, 10.0, 10.0, 10.0, 9.5, 9.0,
        "fixture", 10.0, emptyList(), emptyList())
    private val exit = ExitPlan.Result(21, 9.0, 10.0, false, 12.0, 10.0, 10.5, 0.5,
        0.1, 0.5, ExitPlan.ACT_HOLD, listOf("fixture"))
    private val canonical = CanonicalEngine.Result("600519", 80.0, 0.6, 0.8, 8, 1, 1,
        emptyList(), "bullish", "fixture")
    private val flow = FlowEngine.fromMainRatio("600519", 0.1, "fixture")!!
    private val tape = TapeEngine.Result(75.0, "bullish", null, null, null, null, null, null, null,
        1.0, 38, "ok", emptyList())
    private val chain = EngineChain.ChainResult(80.0, canonical, flow, tape,
        RegimeGate.Result(RegimeGate.Regime.BULL, 3.0, 0.8, emptyList()), emptyList(), "fixture", false,
        emptyList(), null, "ok")

    private fun decide(code: String = "600519", price: Double? = 10.0, pct: Double? = 1.0,
                       stamp: Long? = now, proof: LocalScreener.DataEvidence? = evidence,
                       c: EngineChain.ChainResult? = chain, l: KeyLevels.Levels? = levels,
                       e: ExitPlan.Result? = exit, position: TradingDecision.Position? = null) =
        TradingDecision.evaluate(c, code, price, pct, stamp, "fixture", bars, proof, l, e, now, position)

    @Test fun completeInputsUseExistingBullishSignalAndActualLevelsNotScoreAsProbability() {
        val result = decide()
        assertEquals(TradingDecision.Action.BUY, result.withoutPosition)
        assertEquals(9.5, result.entryLow)
        assertEquals(10.0, result.entryHigh)
        assertEquals(9.0, result.stop)
        assertEquals(11.0, result.target)
        assertEquals(now + 60_000, result.validUntilMs)
        assertFalse(result.summary().contains("胜率"))
    }

    @Test fun coldDetailLoadsFinancialThroughFacadeWithoutAnyScreenedRows() = runBlocking {
        val detailBars = (0 until 250).map { i -> bars.single().copy(date = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong()).toString()) }
        val requests = mutableListOf<String>()
        val input = DbTables.stockAnalysisInputs("600519", detailBars, now, StockInputTransport { url ->
            requests += url
            if (url.endsWith("finance.json")) """{"symbol":"sh600519","ts":"${java.time.Instant.ofEpochMilli(now - 1000)}","reports":[{"report_date":"2026-06-30","eps":1.0,"dps":0.1}]}""" else null
        })
        val proof = TradingDecision.evidenceOf(input, now)
        assertEquals(1.0, proof.eps)
        assertEquals(0.1, proof.dps)
        assertEquals(StockAnalysisInputs.barsFingerprint(detailBars), proof.barsFingerprint)
        assertTrue(requests.any { it.endsWith("finance.json") })
        assertFalse(proof.missing.contains("DPS"))
        assertFalse(proof.validFor("600519", detailBars, now)) // no same-source factor was supplied
    }

    @Test fun coldDetailUsesItsOwn250VerifiedBarsNotPrior120BarScreening() {
        val detailBars = (0 until 250).map { i -> bars.single().copy(date = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong()).toString()) }
        val input = StockAnalysisInputs("600519", 1.0, 0.1, "2026-06-30", "fixture", now - 1000,
            now + 60_000, detailBars, StockAnalysisInputs.barsFingerprint(detailBars), "cnfin:hfq_factor:fixture")
        val proof = TradingDecision.evidenceOf(input, now)
        assertTrue(proof.validFor("600519", detailBars, now))
        assertFalse(proof.validFor("600519", detailBars.takeLast(120), now))
        val result = TradingDecision.evaluate(chain, "600519", 10.0, 1.0, now, "fixture",
            input.bars, proof, levels, exit, now)
        assertEquals(TradingDecision.Action.BUY, result.withoutPosition)
        assertTrue(result.missing.isEmpty())
    }

    @Test fun freshQuoteBelowVerifiedStopCannotKeepHistoricalHoldOrBuy() {
        val result = decide(price = 8.0, e = exit.copy(action = ExitPlan.ACT_HOLD, stopLine = 9.0))
        assertEquals(TradingDecision.Action.WAIT, result.withPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, result.withoutPosition)
        assertTrue(result.reasons.any { it.contains("止损触发") && it.contains("重算") })
        assertTrue(result.conditions.any { it.contains("可卖") })
        assertNull(result.sellQuantity)
    }

    @Test fun summarySeparatesUnspecifiedHoldingPeriodFromExitWindowAndQuoteTtl() {
        val text = decide().summary()
        assertTrue(text.contains("策略周期未指定"))
        assertTrue(text.contains("20日退出观察窗不等于持有期"))
        assertTrue(text.contains("5分钟仅报价有效期"))
    }

    @Test fun computedLevelsAndExitProvideStrictGeometryWithoutHandBuiltLevels() {
        val actualBars = (0 until 100).map { i ->
            val date = java.time.LocalDate.of(2026, 8, 1).plusDays(i.toLong()).toString()
            when {
                i < 80 -> KBar(date, 11.5, 11.5, 12.0, 11.0, 100.0)
                i == 82 -> KBar(date, 10.0, 9.0, 12.0, 8.5, 100.0)
                else -> KBar(date, 11.0, 11.0, 12.0, 10.0, 100.0)
            }
        }
        val computedLevels = requireNotNull(KeyLevels.compute(actualBars))
        val computedExit = ExitPlan.compute(actualBars)
        assertEquals(ExitPlan.ACT_HOLD, computedExit.action)
        assertTrue(requireNotNull(computedExit.stopLine) < computedLevels.support)
        assertTrue(computedLevels.support <= computedLevels.pullback)
        assertTrue(computedLevels.pullback < computedLevels.pressure)
        val proof = evidence.copy(barsFingerprint = StockAnalysisInputs.barsFingerprint(actualBars))
        val result = TradingDecision.evaluate(chain, "600519", 11.0, 1.0, now, "fixture",
            actualBars, proof, computedLevels, computedExit, now)
        assertEquals(TradingDecision.Action.BUY, result.withoutPosition)
        assertEquals(computedLevels.support, result.entryLow)
        assertEquals(computedLevels.pullback, result.entryHigh)
        assertEquals(computedExit.stopLine, result.stop)
        assertEquals(computedLevels.pressure, result.target)
        val brokenQuote = TradingDecision.evaluate(chain, "600519", 8.0, -5.0, now, "fixture",
            actualBars, proof, computedLevels, computedExit, now)
        assertEquals(TradingDecision.Action.WAIT, brokenQuote.withPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, brokenQuote.withoutPosition)
    }

    @Test fun financialAndBindingGapsPreventNewPosition() {
        assertTrue(decide(proof = evidence.copy(dps = null)).missing.contains("DPS"))
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(proof = null).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(proof = evidence.copy(expiresAtMs = now - 1)).withoutPosition)
        assertNull(decide(proof = evidence.copy(expiresAtMs = now - 1)).validUntilMs)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(proof = evidence.copy(barsFingerprint = "wrong")).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(proof = evidence.copy(adjustmentEvidence = "other:factor")).withoutPosition)
    }

    @Test fun oldUnknownOrInvalidQuotesCannotBeRenewedByCalculationTime() {
        assertEquals(TradingDecision.Action.WAIT, decide(stamp = null).withoutPosition)
        assertEquals(TradingDecision.Action.WAIT, decide(stamp = now - 300_001).withoutPosition)
        assertEquals(TradingDecision.Action.WAIT, decide(stamp = now + 1).withoutPosition)
        for (p in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(TradingDecision.Action.WAIT, decide(price = p).withoutPosition)
        }
        assertNull(decide(stamp = null).validUntilMs)
    }

    @Test fun limitUpNonMainboardAndMissingChangeCannotBuy() {
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(pct = 9.6).withoutPosition)
        for (code in listOf("688001", "300001", "301001")) {
            assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(code = code).withoutPosition)
        }
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(pct = null).withoutPosition)
    }

    @Test fun clearAndTrimApplyToHoldingsNeverBecomeABuy() {
        for ((action, expected) in listOf(ExitPlan.ACT_CLEAR to TradingDecision.Action.EXIT,
            ExitPlan.ACT_TRIM to TradingDecision.Action.TRIM)) {
            val result = decide(e = exit.copy(action = action))
            assertEquals(TradingDecision.Action.DO_NOT_BUY, result.withoutPosition)
            assertEquals(expected, result.withPosition)
            assertTrue(result.conditions.any { it.contains("T+1") })
            assertNull(result.sellQuantity)
        }
        val locked = decide(e = exit.copy(action = ExitPlan.ACT_CLEAR),
            position = TradingDecision.Position(100, 0, null))
        assertEquals(0, locked.sellQuantity)
        assertTrue(locked.conditions.any { it.contains("成本") })
        assertEquals(TradingDecision.Action.WAIT, decide(stamp = null, e = exit.copy(action = ExitPlan.ACT_CLEAR)).withPosition)
    }

    @Test fun missingLevelsIncompleteChainAndNoExistingSignalCannotBuy() {
        val missing = decide(l = null)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, missing.withoutPosition)
        assertNull(missing.entryLow)
        assertNull(missing.target)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(e = null).withoutPosition)
        assertNull(decide(e = null).stop)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(c = chain.copy(flow = null)).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(c = chain.copy(specDrift = true)).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(c = chain.copy(degraded = listOf("flow"))).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(c = chain.copy(canonical = canonical.copy(signal = "neutral"))).withoutPosition)
        assertEquals(TradingDecision.Action.DO_NOT_BUY, decide(c = null).withoutPosition)
    }
}
