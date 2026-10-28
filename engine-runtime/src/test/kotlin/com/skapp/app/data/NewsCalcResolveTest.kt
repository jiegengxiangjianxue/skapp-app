package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 快讯标题 → 股票代码的识别口径。
 *
 * 这条链路的错误不会报错：多识别一只票，界面就在一条无关快讯下挂出该票的实时行情与评分，
 * 看上去像「算法找到了关联」，实际是编造。
 */
class NewsCalcResolveTest {

    @Test
    fun `六位代码能识别`() {
        assertEquals(listOf("600519"), NewsCalcEngine.resolveStocks("600519 三季度净利增长", emptyList()))
    }

    @Test
    fun `港股前缀能识别`() {
        assertEquals(listOf("hk00700"), NewsCalcEngine.resolveStocks("HK00700 回购 10 亿", emptyList()))
    }

    @Test
    fun `年份与点位不能当成股票代码`() {
        // 旧正则 (hk)?\d{4,6} 会吃下 "2026" 与 "3100"，normalize 再 padStart 成
        // 002026 / 003100，于是这两条快讯会各挂一只无关股票。
        assertTrue(
            NewsCalcEngine.resolveStocks("2026年10月市场回顾", emptyList()).isEmpty(),
            "年份不应被识别成股票代码",
        )
        assertTrue(
            NewsCalcEngine.resolveStocks("沪指站上3100点", emptyList()).isEmpty(),
            "点位不应被识别成股票代码",
        )
        assertTrue(
            NewsCalcEngine.resolveStocks("成交额1.2万亿", emptyList()).isEmpty(),
            "金额不应被识别成股票代码",
        )
    }
}
