package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守住 [EngineWeights] 这张登记表：新增引擎漏登记最坏的情况是「不报错但静默少算」，
 * 这里把三处（权重 / 族 / 中枢清单）的一致性变成会红的测试。
 */
class EngineWeightsTest {

    @Test
    fun `权重与族覆盖同一批引擎`() {
        assertEquals(EngineWeights.weights().keys, EngineWeights.familyOf().keys)
    }

    @Test
    fun `中枢十二引擎全部登记`() {
        assertEquals(12, LocalEngineId.CENTRAL.size)
        val weights = EngineWeights.weights()
        for (id in LocalEngineId.CENTRAL) {
            assertTrue(id.key in weights, "中枢引擎缺权重登记: ${id.key}")
        }
    }

    @Test
    fun `资金与盘口引擎也登记了族`() {
        // EngineChain 会产出 flow / tape，漏登记会被 familiesOf 的 mapNotNull 静默丢掉
        assertTrue("flow" in EngineWeights.familyOf(), "flow 缺族登记")
        assertTrue("tape" in EngineWeights.familyOf(), "tape 缺族登记")
    }

    @Test
    fun `未登记引擎按 1_0 兜底不改变既有加权`() {
        assertEquals(EngineWeights.FALLBACK, EngineWeights.weightOf("not-registered"), 0.0)
        assertEquals(EngineWeights.FALLBACK, EngineWeights.weightOf(null), 0.0)
    }

    @Test
    fun `中枢引擎默认权重与站点口径一致`() {
        assertEquals(1.2, EngineWeights.weightOf("trend"), 1e-9)
        assertEquals(0.9, EngineWeights.weightOf("choppiness"), 1e-9)
        assertEquals(0.9, EngineWeights.weightOf("position"), 1e-9)
    }
}
