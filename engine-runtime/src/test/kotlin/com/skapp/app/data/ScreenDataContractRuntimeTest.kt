package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 运行时层的屏幕数据契约（engine-runtime）。
 *
 * algo 层的算法口径由 ScreenDataContractTest 守；这里守的是「算完之后到界面之间」那一段：
 * 快讯→股票识别、利好质量判定、因子否决板的一句话总结。这些错误不会抛异常，
 * 只会让界面显示一条看上去合理的错结论。
 */
class ScreenDataContractRuntimeTest {

    // ── 1. 名称表识别（代码表命中路径，NewsCalcResolveTest 未覆盖）──────────

    @Test
    fun `标题里只有股票名时用全市场名称表识别`() {
        val symbols = listOf(SymbolEntry("600519", "贵州茅台"))
        assertEquals(listOf("600519"), NewsCalcEngine.resolveStocks("贵州茅台 三季度净利增长", symbols))
    }

    @Test
    fun `短名称不参与识别`() {
        // name.length >= 3 才进名称表匹配：否则「平安」这类短名会把整版快讯都挂上同一只票
        val symbols = listOf(SymbolEntry("600519", "ST"))
        assertTrue(NewsCalcEngine.resolveStocks("ST 板块整体走强", symbols).isEmpty(),
            "两位名称不该参与股票名匹配")
    }

    // ── 2. 代码优先于名称 ──────────────────────────────────────────────

    @Test
    fun `同时出现代码与名称时以代码为准且不重复`() {
        val symbols = listOf(SymbolEntry("000858", "贵州茅台"))
        assertEquals(listOf("600519"), NewsCalcEngine.resolveStocks("600519 贵州茅台 签订大单", symbols),
            "代码命中后不应再走名称表，否则一条快讯挂两只票")
    }

    // ── 3. 利好质量判定 ────────────────────────────────────────────────

    @Test
    fun `利空词压过利好情绪`() {
        val text = "中标 5 亿订单 但遭立案调查"
        val (senti, hits) = NewsCalcEngine.sentimentOf(text)
        assertEquals("pos", senti, "利好词数多于利空词时情绪判正（词表口径）")
        assertEquals("利空", NewsCalcEngine.verdictOf(senti, hits, text, null),
            "命中风险词必须判利空，不能被「中标」洗成利好")
    }

    @Test
    fun `利好但标的已大涨要提示防追高`() {
        assertEquals("利好·但标的已大涨9.2%，防追高套人",
            NewsCalcEngine.verdictOf("pos", listOf("中标"), "中标 5 亿订单", 9.2))
    }

    @Test
    fun `带具体落地词的利好才算真利好`() {
        assertEquals("真利好", NewsCalcEngine.verdictOf("pos", listOf("中标"), "中标 5 亿订单", 2.0))
        assertEquals("利好", NewsCalcEngine.verdictOf("pos", listOf("增长"), "三季度净利增长", 2.0))
    }

    @Test
    fun `中性情绪只给中性结论`() {
        assertEquals("中性", NewsCalcEngine.verdictOf("neu", emptyList(), "公司发布日常公告", null))
    }

    // ── 4. 因子否决板 ─────────────────────────────────────────────────

    private fun row(code: String, verdict: FactorWeightStrategy.Verdict?) = FactorRow(
        code = code,
        name = code,
        score = verdict?.let {
            FactorWeightStrategy.Score(
                code = code, buyScore = 0.0, avoidScore = 0.0, netScore = 0.0,
                verdict = it, votes = emptyList(), bullCount = 0, bearCount = 0,
                neutralCount = 0, reasons = emptyList(),
            )
        },
    )

    @Test
    fun `否决板总结按否决数与存疑数出文案`() {
        val board = FactorBoard(rows = listOf(
            row("600519", FactorWeightStrategy.Verdict.AVOID),
            row("000858", FactorWeightStrategy.Verdict.WATCH),
            row("601318", FactorWeightStrategy.Verdict.PASS),
        ))
        assertEquals("1 只被否决 · 1 只存疑 · 1 只无否决", board.summary)
        assertEquals(1, board.avoidCount)
        assertEquals(1, board.watchCount)
        assertEquals(3, board.scored.size)
    }

    @Test
    fun `无否决时总结写成无标的被否决`() {
        val board = FactorBoard(rows = listOf(row("600519", FactorWeightStrategy.Verdict.PASS)))
        assertTrue(board.summary.startsWith("无标的被否决"), "全部通过时不应出现「0 只被否决」这类读起来像有问题的文案：${board.summary}")
    }

    @Test
    fun `拿不到分数的不许计入任何一档`() {
        // score == null 表示 K 线不足 60 根，界面显示「样本不足」，不能算成通过
        val board = FactorBoard(rows = listOf(row("600519", null)))
        assertEquals(0, board.scored.size)
        assertEquals(0, board.passCount)
        assertTrue(board.summary.contains("暂无可用标的"), "空态文案要说明原因：${board.summary}")
    }
}
