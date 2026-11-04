package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CentralInputContractTest {
    private val spec = EngineSpecLoader.loadBundled().spec

    @Test fun stateEngineParametersAreReadFromPublishedRegistry() {
        val text = EngineSpecLoader::class.java.getResourceAsStream(EngineSpecLoader.RESOURCE)!!.bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(text)
        val state = root.getJSONArray("state_engines")
        for (i in 0 until state.length()) {
            val engine = state.getJSONObject(i)
            if (engine.getString("id") == "flow") engine.getJSONObject("params").put("mainUnit", 0.12).put("minPeerN", 7)
            if (engine.getString("id") == "tape") engine.getJSONObject("params").put("wBuy", 19.0).put("posBars", 8)
        }
        val changed = EngineSpecLoader.parse(root)
        assertEquals(0.12, EngineChain.flowParams(changed).mainUnit)
        assertEquals(7, EngineChain.flowParams(changed).minPeerN)
        assertEquals(19.0, EngineChain.tapeParams(changed).wBuy)
        assertEquals(8, EngineChain.tapeParams(changed).posBars)
        assertEquals(62.0, EngineChain.tapeParams(spec).bullThreshold)
    }

    @Test fun rawChainRejectsStaleFutureAndOtherStockFlowInputs() {
        val now = java.time.OffsetDateTime.parse("2026-11-02T10:15:00+08:00").toInstant().toEpochMilli()
        val bars = (0..249).map { i -> KBar("2026-11-02", 10.0+i*.01, 10.05+i*.01,
            10.1+i*.01, 9.9+i*.01, 1000.0) }
        fun row(ts: Long) = LiveMarket.Row("600001", "fixture", 10.0, 3.0, 10000.0,
            1e8, 4.0, 3.0, 15.0, 1.5, 1e10, 1e7, ts)
        for (ts in listOf(now-spec.runtime.snapshotStaleMs-1, now+1)) {
            val result = EngineChain.analyze(EngineChain.Inputs(bars, row = row(ts), code = "600001", nowMs = now), spec)
            assertEquals(null, result.flow)
            assertTrue(result.votes.none { it.indicator == "flow" })
        }
        val other = FlowEngine.fromMainRatio("600002", .1, "fixture")
        val result = EngineChain.analyze(EngineChain.Inputs(bars, code = "600001", nowMs = now,
            precomputedFlow = other), spec)
        assertEquals(null, result.flow)
        assertTrue(result.votes.none { it.indicator == "flow" })
    }

    @Test fun publishedFlowAndTapeWeightsAdmitVotesWithoutDeviceId() {
        val row = LiveMarket.Row("600001", "fixture", 10.0, 3.0, 10000.0, 1e8,
            4.0, 3.0, 15.0, 1.5, 1e10, 1e7, 1L)
        val flow = FlowEngine.compute(row)
        assertNotNull(flow.score)
        val result = EngineChain.externalVotes(flow, null, null, spec.copy(deviceEngines = emptyList()))
        assertEquals(listOf("flow"), result.map { it.indicator })
    }

    @Test fun disabledFlowCannotVoteEvenWhenDeviceEnabled() {
        val row = LiveMarket.Row("600001", "fixture", 10.0, 3.0, 10000.0, 1e8,
            4.0, 3.0, 15.0, 1.5, 1e10, 1e7, 1L)
        val off = spec.copy(deviceEngines = spec.deviceEngines + "flow", weights = spec.weights + ("flow" to 0.0))
        assertTrue(EngineChain.externalVotes(FlowEngine.compute(row), null, null, off).isEmpty())
    }

    @Test fun zeroWeightIsNotAnActiveDirectionalVote() {
        val bars = (0..249).map { i -> KBar("2026-01-01", 10.0+i*.01, 10.05+i*.01,
            10.1+i*.01, 9.9+i*.01, 1000.0) }
        val disabled = spec.copy(weights = spec.weights.mapValues { 0.0 })
        assertEquals(null, CanonicalEngine.score("600001", bars, disabled))
    }
    @Test fun sameFlowCannotBeWeightedInsideAndOutsideCentralVote() {
        val bars = (0..249).map { i -> KBar("2026-01-01", 10.0+i*.01, 10.05+i*.01,
            10.1+i*.01, 9.9+i*.01, 1000.0) }
        val bad = spec.copy(blend = EngineSpec.BlendWeights(0.88, 0.07, 0.05))
        val result = EngineChain.analyze(EngineChain.Inputs(bars), bad)
        assertEquals(null, result.canonicalScore)
        assertEquals("insufficient", result.status)
        assertTrue(result.degraded.any { it.contains("重复计权") })
    }

}
