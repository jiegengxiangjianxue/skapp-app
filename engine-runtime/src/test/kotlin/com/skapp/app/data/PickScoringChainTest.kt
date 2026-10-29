package com.skapp.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L3 链路级测试：跨引擎集成 → gate 折价 → 池内分位 → 达标判定。
 *
 * 这里测的是**整条链的先后顺序**，不是单个函数：
 *  - gate 乘在「对 50 的偏离」上，不是乘总分（否则中性票会被凭空扣分）；
 *  - 权重为空时不做集成（否则等权平均会冒充云端结论，悄悄改达标名单）；
 *  - 达标用 gated，排序用 rankScore（含板块偏移的 effective 也折价）。
 */
class PickScoringChainTest {

    private fun rows() = listOf(
        // A：中枢 62，板块加权后 64，两个引擎有分
        PickScoring.Row("A", 62.0, true, 2.0, mapOf("supertrend" to 70.0, "macd" to 60.0), effective = 64.0),
        // B：中枢 58，刚过 55 线
        PickScoring.Row("B", 58.0, true, 1.0, mapOf("supertrend" to 50.0, "macd" to 55.0), effective = 58.0),
        // C：分高但不可买（涨停/停牌口径由 buyable 表达）
        PickScoring.Row("C", 70.0, false, 9.9, emptyMap(), effective = 70.0),
    )

    @Test
    fun `gate=1 且无权重时回落到旧行为`() {
        val out = PickScoring.score(rows(), minScore = 55.0, maxChgPct = 9.0)
        // 排序分 = effective：C(70) > A(64) > B(58)；C 分最高但 buyable=false 不达标
        assertEquals(listOf("C", "A", "B"), out.map { it.code }, "按 effective 排序")
        assertEquals(listOf(false, true, true), out.map { it.qualified })
        assertTrue(out.all { it.integrated == null }, "无权重时不得产出集成分")
        assertEquals(62.0, out.first { it.code == "A" }.gated, "gate=1 时 gated 等于中枢分")
    }

    @Test
    fun `gate 折价作用在偏离量上而非总分`() {
        val out = PickScoring.score(rows(), gate = 0.5, minScore = 55.0, maxChgPct = 9.0)
        val a = out.first { it.code == "A" }
        val b = out.first { it.code == "B" }
        assertTrue(abs(56.0 - (a.gated ?: 0.0)) < 1e-9, "A 62 → 56（50+12×0.5），实际 ${a.gated}")
        assertTrue(abs(54.0 - (b.gated ?: 0.0)) < 1e-9, "B 58 → 54，实际 ${b.gated}")
        assertTrue(a.qualified, "A 56 ≥ 55 仍达标")
        assertFalse(b.qualified, "B 54 < 55 在弱市被挡掉——这是 gate 想要的摩擦")
        assertEquals(64.0 - 14.0 * 0.5, a.rankScore, "排序分也折价（50+14×0.5）")
    }

    @Test
    fun `云端权重驱动跨引擎集成`() {
        val weights = mapOf("supertrend" to 2.0, "macd" to 0.5)
        val out = PickScoring.score(rows(), weights = weights, gate = 0.8, minScore = 55.0, maxChgPct = 9.0)
        val a = out.first { it.code == "A" }
        // (2×70 + 0.5×60) / 2.5 = 68
        assertTrue(abs(68.0 - (a.integrated ?: 0.0)) < 1e-9, "集成分 ${a.integrated}")
        // gated 用集成分：50 + 18×0.8 = 64.4
        assertTrue(abs(64.4 - (a.gated ?: 0.0)) < 1e-9, "gated ${a.gated}")
        assertTrue(a.qualified)
        // C 没有引擎分 → integrated 为 null，仍用中枢分 70 折价
        val c = out.first { it.code == "C" }
        assertEquals(null, c.integrated)
        assertTrue(abs(66.0 - (c.gated ?: 0.0)) < 1e-9, "C gated ${c.gated}")
    }

    @Test
    fun `池内分位与分档`() {
        val out = PickScoring.score(rows(), minScore = 55.0, maxChgPct = 9.0)
        val pcts = out.associate { it.code to it.percentile }
        assertEquals(1.0, pcts["C"], "C 分最高 → 分位 1.0")
        assertEquals(0.0, pcts["B"], "B 分最低 → 分位 0.0")
        assertEquals("极强", out.first { it.code == "C" }.tier)
        assertEquals("偏强", out.first { it.code == "A" }.tier)
        assertEquals("弱", out.first { it.code == "B" }.tier)
    }

    @Test
    fun `权重空时不集成 避免等权平均冒充云端`() {
        val out = PickScoring.score(rows(), weights = emptyMap(), minScore = 55.0, maxChgPct = 9.0)
        assertTrue(out.all { it.integrated == null })
        assertEquals(62.0, out.first { it.code == "A" }.gated)
    }

    @Test
    fun `权重与端上引擎对齐 未对应项如实列出`() {
        val (aligned, unmapped) = PickScoring.alignWeights(
            mapOf("supertrend" to 1.2, "rsi" to 1.0, "vwma" to 1.0, "boll_keltner" to 0.6),
        )
        assertEquals(mapOf("supertrend" to 1.2, "boll" to 0.6), aligned)
        assertEquals(setOf("rsi", "vwma"), unmapped.toSet())
    }

    @Test
    fun `涨幅超限不达标`() {
        val rows = listOf(PickScoring.Row("D", 80.0, true, 9.5, emptyMap(), effective = 80.0))
        val out = PickScoring.score(rows, minScore = 55.0, maxChgPct = 9.0)
        assertFalse(out.first().qualified, "涨幅 9.5 ≥ 9.0 不该进推荐")
    }
}
