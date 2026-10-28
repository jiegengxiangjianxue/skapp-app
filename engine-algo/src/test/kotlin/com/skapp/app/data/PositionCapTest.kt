package com.skapp.app.data

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 仓位上限的闸门测试。
 *
 * 核心诉求：市场烂到一定程度必须能给出「空仓/轻仓」，而不是永远留 2 成。
 */
class PositionCapTest {

    private fun ok(message: String, condition: Boolean) = assertTrue(condition, message)

    @Test
    fun `宽度塌且跌停多于涨停 - 命中闸门压到2成`() {
        val r = PositionCap.compute(
            breadth = 0.22, phaseScore = -20.0, limitUp = 8, limitDown = 21,
        )
        assertTrue(r.gates.isNotEmpty())
        ok("cap=${r.cap}", r.cap <= PositionCap.GATE_CAP + 1e-9)
        assertEquals(PositionCap.LABEL_LIGHT, r.label)
    }

    @Test
    fun `连板高度掉档且晋级率崩 - 命中退潮闸门`() {
        val r = PositionCap.compute(
            breadth = 0.48, phaseScore = 5.0, limitUp = 30, limitDown = 4,
            heightToday = 2, heightPrev = 5, promo = 0.18,
        )
        assertTrue(r.gates.any { it.contains("连板高度") })
        assertTrue(r.cap <= PositionCap.GATE_CAP + 1e-9)
    }

    @Test
    fun `健康的强势市场 - 上限放开但不给满仓`() {
        val r = PositionCap.compute(
            breadth = 0.68, phaseScore = 45.0, limitUp = 65, limitDown = 2,
            heightToday = 6, heightPrev = 5, promo = 0.45,
        )
        assertTrue(r.gates.isEmpty())
        ok("cap=${r.cap}", r.cap > 0.5)
        ok("永不满仓", r.cap <= PositionCap.MAX_CAP + 1e-9)
        assertEquals(PositionCap.LABEL_HEAVY, r.label)
    }

    @Test
    fun `数据不可得时不猜 - 趋势项取中性且写明`() {
        val r = PositionCap.compute(
            breadth = null, phaseScore = 0.0, limitUp = 20, limitDown = 5,
        )
        assertTrue(r.gates.isEmpty())
        assertTrue(r.reasons.any { it.contains("不可得") })
        assertTrue(r.cap in 0.0..PositionCap.MAX_CAP)
    }

    @Test
    fun `理由必须带数值 - 每条闸门都能被追问`() {
        val r = PositionCap.compute(
            breadth = 0.3, phaseScore = -70.0, limitUp = 10, limitDown = 40,
        )
        assertTrue(r.gates.size >= 2)
        r.gates.forEach { g ->
            ok("理由缺数值：$g", g.any { it.isDigit() })
        }
        assertTrue(r.reasons.any { it.contains("→ 上限压到") })
    }
}
