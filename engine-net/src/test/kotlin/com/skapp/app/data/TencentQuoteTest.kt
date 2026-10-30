package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 腾讯行情行解析：字段下标写死容易错，用真实报文形状钉住。 */
class TencentQuoteTest {

    private fun row(overrides: Map<Int, String> = emptyMap()): String {
        val f = MutableList(33) { "" }
        f[0] = "1"
        f[1] = "贵州茅台"
        f[2] = "sh600519"
        f[3] = "1371.51"
        f[4] = "1354.00"
        f[5] = "1363.00"
        f[30] = "20261030120531"
        f[31] = "17.51"
        f[32] = "1.29"
        overrides.forEach { (i, v) -> f[i] = v }
        return "v_sh600519=\"" + f.joinToString("~") + "\";"
    }

    @Test
    fun `解析现价涨跌幅与时间`() {
        val q = TencentQuote.parseLine(row())!!
        assertEquals("600519", q.code)
        assertEquals("贵州茅台", q.name)
        assertEquals(1371.51, q.price!!, 0.001)
        assertEquals(1.29, q.pctChg!!, 0.001)
        assertEquals("2026-10-30 12:05:31", q.ts)
    }

    @Test
    fun `短行直接放弃而不是抛下标越界`() {
        val short = "v_sz000001=\"51~平安银行~000001~11.20~\""
        assertNull(TencentQuote.parseLine(short))
    }
}
