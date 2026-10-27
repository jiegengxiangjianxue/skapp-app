package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 实时盘口行的口径判定（不联网，只测纯逻辑）。
 *
 * 涨停/跌停是「筛选红线」的一部分（推股不追涨停），判错一天就会放出错误推荐，
 * 所以板块上限、ST、余量这些边角必须钉住。
 */
class LiveMarketRowTest {

    private fun row(code: String, pct: Double, name: String? = "测试股") = LiveMarket.Row(
        code = code,
        name = name,
        price = 10.0,
        pctChg = pct,
        volumeLots = 1.0,
        amount = 1.0,
        amplitude = 1.0,
        turnoverRate = 1.0,
        pe = 10.0,
        volRatio = 1.0,
        mv = 1.0,
        mainNet = 0.0,
        ts = 0L,
    )

    @Test
    fun `涨跌幅上限按板块区分`() {
        assertEquals(10.0, row("600519", 0.0).limitCap)
        assertEquals(10.0, row("000001", 0.0).limitCap)
        assertEquals(20.0, row("300750", 0.0).limitCap)
        assertEquals(20.0, row("688981", 0.0).limitCap)
        assertEquals(30.0, row("830799", 0.0).limitCap)
        assertEquals(30.0, row("920001", 0.0).limitCap)
    }

    @Test
    fun `主板ST按五个点算`() {
        assertEquals(5.0, row("600519", 0.0, "ST茅台").limitCap)
        assertTrue(row("600519", 4.8, "ST茅台").atLimitUp)
        assertFalse(row("600519", 4.0, "ST茅台").atLimitUp)
        // 创业板 ST 仍是 20cm（2020 年改革后规则）
        assertEquals(20.0, row("300001", 0.0, "ST创业").limitCap)
    }

    @Test
    fun `涨停判定留抖动余量`() {
        assertTrue(row("600000", 9.8).atLimitUp)
        assertTrue(row("600000", 9.7).atLimitUp)
        assertFalse(row("600000", 9.5).atLimitUp, "9.5 不算涨停")
        assertTrue(row("600000", -9.8).atLimitDown)
        assertFalse(row("600000", -9.5).atLimitDown)
    }

    @Test
    fun `主板判定排除创业板科创板北交所`() {
        assertTrue(row("600519", 0.0).mainBoard)
        assertTrue(row("000001", 0.0).mainBoard)
        assertFalse(row("300750", 0.0).mainBoard)
        assertFalse(row("301001", 0.0).mainBoard)
        assertFalse(row("688981", 0.0).mainBoard)
        assertFalse(row("830799", 0.0).mainBoard)
    }
}
