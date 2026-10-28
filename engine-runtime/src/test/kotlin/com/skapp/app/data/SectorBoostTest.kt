package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 板块强度对排序分的作用边界。
 *
 * 钉三件事：偏移量上限 ±10 分（改不动 minScore 门槛）、板块未知时不拿中性或 0 分顶替、
 * 个股分缺失时排序分必须仍为 null。
 */
class SectorBoostTest {

    @Test
    fun `板块未知时排序分等于个股分`() {
        assertEquals(70.0, LocalScreener.effectiveScoreOf(70.0, null))
    }

    @Test
    fun `板块强度只把排序分挪动正负十分`() {
        assertEquals(80.0, LocalScreener.effectiveScoreOf(70.0, 100.0)!!, 1e-9)
        assertEquals(60.0, LocalScreener.effectiveScoreOf(70.0, 0.0)!!, 1e-9)
        assertEquals(70.0, LocalScreener.effectiveScoreOf(70.0, 50.0)!!, 1e-9)
    }

    @Test
    fun `个股分为 null 时排序分仍为 null`() {
        assertNull(LocalScreener.effectiveScoreOf(null, 90.0))
    }

    @Test
    fun `强板块的次强票可以排到弱板块的强票前面`() {
        // 70 + (20-50)*0.2 = 64；62 + (100-50)*0.2 = 72
        val weakSectorStrongStock = LocalScreener.effectiveScoreOf(70.0, 20.0)!!
        val strongSectorWeakerStock = LocalScreener.effectiveScoreOf(62.0, 100.0)!!
        assertTrue(
            strongSectorWeakerStock > weakSectorStrongStock,
            "板块强度应能挪一档：$strongSectorWeakerStock vs $weakSectorStrongStock",
        )
    }
}
