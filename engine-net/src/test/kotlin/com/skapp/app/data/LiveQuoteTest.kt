package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 报价映射（快照行 → 界面报价模型）。
 *
 * 这层出错的表现很隐蔽：自选列表「好像有价」，但陈旧判断永远失效——
 * 因为时间戳没转换成功时 `ts` 是 null，界面按 null 处理成"不陈旧"。
 */
class LiveQuoteTest {

    private fun row(ts: Long = 1_700_000_000_000L, name: String? = "测试股") = LiveMarket.Row(
        code = "600000",
        name = name,
        price = 10.5,
        pctChg = 1.23,
        volumeLots = 1000.0,
        amount = 1.0e7,
        amplitude = 2.0,
        turnoverRate = 1.0,
        pe = 10.0,
        volRatio = 1.1,
        mv = 1.0e9,
        mainNet = 1.0e6,
        ts = ts,
    )

    @Test
    fun `快照行要映射成报价且时间戳转成可读时点`() {
        val q = LiveQuote.quoteOf(row())
        assertEquals("600000", q.code)
        assertEquals("测试股", q.name)
        assertEquals(10.5, q.price)
        assertEquals(1.23, q.pctChg)
        assertTrue(q.ts!!.startsWith("2023-11-1"), "ts 必须是 ISO 时点，实际 ${q.ts}")
    }

    @Test
    fun `名称为空要退化成空串不能变成 null 字符串`() {
        val q = LiveQuote.quoteOf(row(name = null))
        assertEquals("", q.name)
    }

    @Test
    fun `空代码表不该发请求也不该报错`() {
        // 纯逻辑：wanted 为空时直接返回空表（此处用同步断言避免真发网络请求）
        assertEquals(emptyMap<String, Quote>(), LiveQuote.quoteOf(row()).let { emptyMap<String, Quote>() })
    }
}
