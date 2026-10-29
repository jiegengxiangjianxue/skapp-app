package com.skapp.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 新三引擎链路的不变量测试。
 *
 * 每个断言对着一个具体的坑：缺输入被当成 0 分、一字板被当成横盘、开盘前编造缺口、
 * 板块太少还硬排名、缺分的引擎却产出一张中性票把综合分拉平。
 */
class EngineChainSelfCheckTest {

    private val spec: EngineSpec = EngineSpecLoader.loadBundled().spec

    @Test
    fun `链路自检八条不变量`() {
        val r = EngineChain.selfCheck()
        assertTrue(r.checks >= 8, "自检项少于 8 条：${r.checks}")
        assertTrue(r.ok, "不变量被破坏：" + r.violations.joinToString(" | "))
    }

    @Test
    fun `增强分缺项按剩余权重归一`() {
        val onlyCentral = EngineChain.blend(60.0, null, null)
        assertTrue(onlyCentral != null && abs(onlyCentral!! - 60.0) < 1e-9)
        val full = EngineChain.blend(60.0, 40.0, 40.0)
        assertTrue(full != null && abs(full!! - 57.6) < 1e-9, "0.88*60+0.07*40+0.05*40=57.6，实际 $full")
        assertTrue(EngineChain.blend(null, null, null) == null, "全缺必须 null，不许回 0")
    }

    @Test
    fun `族映射覆盖新引擎`() {
        val engines = listOf(
            EngineScore("volume", 60.0, "neutral"),
            EngineScore("flow", 70.0, "bullish"),
            EngineScore("choppiness", 45.0, "neutral"),
            EngineScore("tape", 30.0, "bearish"),
        )
        val fams = LocalEngine.familiesOf(engines)
        assertTrue(fams.any { it.family == EngineFamily.VOLUME && it.members == 2 }, "flow 未并入 VOLUME 族：$fams")
        assertTrue(fams.any { it.family == EngineFamily.STRUCTURE && it.members == 2 }, "tape 未并入 STRUCTURE 族：$fams")
    }

    @Test
    fun `规格参数能覆盖引擎默认值`() {
        val d = FlowEngine.Params()
        val same = EngineChain.flowParams(spec)
        assertEquals(d.pctSd, same.pctSd, "规格未写 pct_sd 时必须回落默认 ${d.pctSd}")
        val p = EngineChain.flowParams(spec.copy(engines = spec.engines + EngineSpec.VoteEngine(
            id = "flow", family = "volume", weightKey = "flow",
            availability = listOf("device"), params = mapOf("pct_sd" to 2.5, "min_peer_n" to 50.0),
            voteRule = "score>=signal_bullish", strengthRule = "(score-center)/scale", note = "test",
        )))
        assertEquals(2.5, p.pctSd, "规格里的 pct_sd 未生效")
        assertEquals(50, p.minPeerN, "规格里的 min_peer_n 未生效")
        assertEquals(spec.aggregation.signalBullish, p.bullThreshold, "上涨门必须跟规格走")
    }

    @Test
    fun `缺分引擎不产票且强势票转正票`() {
        val empty = EngineChain.externalVotes(null, null, spec)
        assertTrue(empty.isEmpty(), "缺分必须 drop，实际 $empty")
        val flow = flowResult(88.0)
        val votes = EngineChain.externalVotes(flow, null, spec.copy(deviceEngines = spec.deviceEngines + "flow"))
        assertEquals(1, votes.size)
        assertEquals("flow", votes[0].indicator)
        assertEquals(1, votes[0].vote)
        assertTrue(votes[0].strength > 0.5, "88 分对应强度应 >0.5，实际 ${votes[0].strength}")
    }

    private fun flowResult(score: Double) = FlowEngine.Result(
        code = "600000", score = score, signal = FlowEngine.signalOf(score), tier = "local",
        netProxyYuan = 1.0, fsiPct = 90.0, fsiAbs = 85.0, pctMain = null, m5Ratio = null,
        scoreDelta = null, liquidityWeight = 1.0, volRatioFactor = 1.0, confidence = 0.7,
        reasons = listOf("test"),
    )

    // ── riskFlag 与 sectors 透传：对齐调用方契约的回归 ─────────────

    private fun bars(up: Boolean, n: Int = 40): List<KBar> = (0 until n).map { i ->
        val c = if (up) 10.0 + i * 0.1 else 14.0 - i * 0.1
        KBar(
            date = "2026-01-01",
            open = if (up) c - 0.05 else c + 0.15,
            close = c,
            high = c + if (up) 0.1 else 0.5,
            low = c - if (up) 0.1 else 0.05,
            vol = if (up) 1.0e6 else 2.0e5,
        )
    }

    private fun row(pctChg: Double = 1.2, volRatio: Double = 1.1) = LiveMarket.Row(
        code = "600000", name = null, price = null, pctChg = pctChg, volumeLots = 5.0e4,
        amount = 5.0e7, amplitude = 2.0, turnoverRate = 1.0, pe = null, volRatio = volRatio,
        mv = null, mainNet = null, ts = 0L,
    )

    @Test
    fun `sectors 只透传不参与链内计算`() {
        val sectors = LocalSectors.Result(
            rows = listOf(
                LocalSectors.Row(
                    name = "板块00", n = 10, chg1d = 1.0, heat = 1.0e9,
                    up = 5, down = 5, limitUp = 1, leader = null, leaderPct = null, strength = 60.0,
                ),
            ),
            mapped = 90, total = 100, unmapped = 10, ts = "test",
        )
        val now = 1_760_000_000_000L
        val plain = EngineChain.analyze(EngineChain.Inputs(bars = bars(up = true), row = row(), nowMs = now))
        val withSectors = EngineChain.analyze(
            EngineChain.Inputs(bars = bars(up = true), row = row(), sectors = sectors, nowMs = now)
        )
        assertEquals(plain, withSectors, "sectors 当前必须只透传，传与不传的链结果应完全相同")
    }

    @Test
    fun `riskFlag 只在资金与盘口双弱时置位`() {
        val now = 1_760_000_000_000L
        var vetoCovered = false
        for (up in listOf(true, false)) {
            val b = bars(up = up)
            val r = if (up) row() else row(pctChg = -2.5, volRatio = 2.5)
            val strong = EngineChain.analyze(
                EngineChain.Inputs(bars = b, row = r, precomputedFlow = flowResult(80.0), nowMs = now)
            )
            assertEquals(null, strong.riskFlag, "资金流强时不得置否决（非 null 会被 screener 直接剔除）")

            val weak = EngineChain.analyze(
                EngineChain.Inputs(bars = b, row = r, precomputedFlow = flowResult(20.0), nowMs = now)
            )
            val f = weak.flow?.score
            val t = weak.tape?.score
            val expectVeto = f != null && t != null && f < 35.0 && t < 35.0
            assertEquals(expectVeto, weak.riskFlag != null, "riskFlag 必须按双弱判定：flow=$f tape=$t")
            if (expectVeto) {
                vetoCovered = true
                assertTrue(weak.riskFlag!!.contains("双弱"), "否决文案须说明原因：${weak.riskFlag}")
                assertTrue(!weak.riskFlag!!.contains("大盘"), "大盘强弱走 regime 字段：${weak.riskFlag}")
            }
        }
        assertTrue(vetoCovered, "测试数据未覆盖双弱分支，需调 bars 构造")
    }
}
