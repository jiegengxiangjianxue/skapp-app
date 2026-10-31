package com.skapp.app.data

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `名称路径识别全部命中的股票`() {
        // 一条快讯常同时提多只票；只取第一只会漏掉后半句里的标的
        val symbols = listOf(SymbolEntry("002131", "利欧股份"), SymbolEntry("300750", "宁德时代"))
        assertEquals(
            listOf("002131", "300750"),
            NewsCalcEngine.resolveStocks("利欧股份与宁德时代签署合作协议", symbols),
        )
    }

    @Test
    fun `括号里的代码也能识别`() {
        assertEquals(
            listOf("002131"),
            NewsCalcEngine.resolveStocks("利欧股份（002131）三季报", emptyList()),
        )
    }

    @Test
    fun `业绩类词进情绪词表`() {
        assertEquals("pos", NewsCalcEngine.sentimentOf("前三季度扭亏为盈").first)
        assertEquals("neg", NewsCalcEngine.sentimentOf("全年业绩预亏").first)
    }

    @Test
    fun `判定带理由`() {
        val (verdict, why) = NewsCalcEngine.verdictDetailed(
            "neg", listOf("亏损", "下降"), "净利润亏损5.1亿元，同比下降560%", null,
        )
        assertEquals("利空", verdict)
        assertTrue(why.contains("亏损"), "理由里必须写明命中的词，否则界面上只剩一句结论")
    }

    @Test
    fun `数据源专名不当作个股`() {
        // 「同花顺数据显示…」是同花顺（300033）自己发的数据快讯，不是它的个股新闻；
        // 给它挂上实时行情就是凭空编造关联
        val symbols = listOf(SymbolEntry("300033", "同花顺"))
        assertTrue(NewsCalcEngine.resolveStocks("同花顺数据显示，昨日两融余额回升", symbols).isEmpty())
    }

    @Test
    fun `symbols missing status`() = runBlocking {
        val out = NewsCalcEngine.compute(
            listOf(mapOf("title" to "利欧股份三季度扭亏为盈")),
            symbols = emptyList(),
        )
        assertEquals(1, out.size)
        assertEquals("symbols_missing", out[0].status)
        assertTrue(out[0].reasoning.any { it.contains("代码表") }, "必须告诉用户是代码表没拉到，而不是消息里没股票")
    }

    @Test
    fun `no stock still gives verdict`() = runBlocking {
        val symbols = listOf(SymbolEntry("600519", "贵州茅台"))
        val out = NewsCalcEngine.compute(
            listOf(mapOf("title" to "央行今日开展逆回购操作")),
            symbols = symbols,
        )
        assertEquals(1, out.size)
        assertEquals("no_stock", out[0].status)
        assertTrue(out[0].reasoning.any { it.startsWith("判定") }, "没有个股也要给出文字层面的判定与依据")
    }
}
