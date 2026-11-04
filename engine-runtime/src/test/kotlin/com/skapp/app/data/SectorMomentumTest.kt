package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectorMomentumTest {
    private val now = 1_800_000_000_000L
    private fun row(code: String, pct: Double?, flow: Double? = null) = LiveMarket.Row(
        code, "fixture", 10.0, pct, null, null, null, null, null, null, null, flow, now)

    @Test fun `缺历史不把单日叫轮动也不补零`() {
        val result = SectorMomentum.compute(listOf(row("600001", 2.0), row("600002", -1.0), row("600003", null)),
            { if (it == "600003") null else "样本板块" }, nowMs = now)
        val r = result.rows.single()
        assertEquals(0.5, r.return1d)
        assertEquals(0.5, r.breadth)
        assertEquals(null, r.return5d)
        assertEquals(null, r.return20d)
        assertEquals(null, r.mainNet)
        assertEquals(2, result.mapped)
        assertEquals(2, r.sampleCodes.size)
        assertEquals(2.0 / 3, result.coverage)
        assertTrue(result.issues.any { it.contains("不代表轮动") })
    }

    @Test fun `qfq请求参数不能冒充可信复权`() {
        val bars = (1..21).map { KBar("2026-10-%02d".format(it), 10.0, 10.0 + it, 40.0, 9.0, 1.0) }
        val result = SectorMomentum.compute(listOf(row("600001", 2.0)), { "板块" },
            mapOf("600001" to bars), dataDate = "2026-10-21", nowMs = now)
        assertEquals(null, result.rows.single().return20d)
        assertEquals(0, result.rows.single().sampleCount20d)
    }

    @Test fun `有同源证据才给样本历史收益`() {
        val bars = (1..21).map { KBar("2026-10-%02d".format(it), 10.0, 10.0 + it, 40.0, 9.0, 1.0) }
        val result = SectorMomentum.compute(listOf(row("600001", 2.0, 100.0)), { "板块" },
            mapOf("600001" to bars), mapOf("600001" to "cnfin:hfq_factor:fixture"), "2026-10-21", now)
        assertEquals((31.0 / 11.0 - 1) * 100, result.rows.single().return20d)
        assertEquals(1, result.rows.single().sampleCount20d)
        assertEquals(100.0, result.rows.single().mainNet)
    }
}
