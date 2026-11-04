package com.skapp.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.data.StockDetail
import com.skapp.app.ui.screens.StockDetailSheet
import com.skapp.app.ui.screens.CredentialsScreen
import com.skapp.app.ui.screens.DashboardHubScreen
import com.skapp.app.ui.screens.DashboardScreen
import com.skapp.app.ui.screens.DecisionScreen
import com.skapp.app.ui.screens.DiagScreen
import com.skapp.app.ui.screens.MarketScreen
import com.skapp.app.ui.screens.NewsHubScreen
import com.skapp.app.ui.screens.NewsScreen
import com.skapp.app.ui.screens.PremarketPicksScreen
import com.skapp.app.ui.screens.RealtimePicksScreen
import com.skapp.app.ui.screens.ResearchDiagnosticsCard
import com.skapp.app.ui.screens.RealtimeSummaryScreen
import com.skapp.app.ui.screens.SectorsScreen
import com.skapp.app.ui.screens.PicksHubScreen
import com.skapp.app.ui.screens.SummaryHubScreen
import com.skapp.app.ui.screens.ThemeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 屏渲染验收（JVM 上跑，不需要模拟器 / root / 无障碍）。
 *
 * ## 覆盖口径
 *
 * 16 个主屏全部纳入（`com.skapp.app.ui.screens` 下每个 `*Screen.kt` 的公开组合函数），
 * 每屏一个用例，统一用**默认空 `UiState()`** 渲染：
 *
 * 1. 组合必须顺利完成、不抛异常 —— 空数据下崩溃是端上最常见的白屏原因，数据层测试发现不了；
 * 2. 有静态文案的屏，还要求至少一条属于自己的文案真的显示出来（不是空组合）。
 *
 * 主屏断言静态文案与「有文本节点」；详情页另用显式状态验证中枢评分来源隔离，
 * 不验证算法计算正确性（归 `ScreenDataContractTest` / `ScreenDataContractRuntimeTest`）。
 *
 * ## 为什么断言「至少一个文本节点」
 *
 * 屏在空数据下常走 early-return（`if (list.isEmpty()) { 空态提示; return }`），
 * 断言具体标题会随文案调整而误红；断言「渲染出了文本」既能抓住白屏 / 提前返回 / 组合崩溃，
 * 又不锁死文案细节。
 *
 * ## 运行环境
 *
 * Robolectric 起 Android 环境时要加载 conscrypt 原生库，而 conscrypt 只发 linux-x86_64；
 * 本容器是 aarch64，本地必然 `UnsatisfiedLinkError`（失败点在 Robolectric 自身，不在被测代码）。
 * 因此这组用例的权威环境是 x86_64 的 CI job `ui-render-robolectric`。
 * 每个用例带 timeout：某屏若因无限动画卡住 `waitForIdle`，只红这一条，不拖垮整个 job。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenRenderRobolectricTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm() = SkappViewModel()

    private fun textNodes() = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes()

    /** 断言：组合不抛异常，且至少渲染出一个文本节点。 */
    private fun assertRenders(screen: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        assertTrue("$screen 渲染后没有任何文本节点（白屏 / 提前返回 / 组合异常）", textNodes().isNotEmpty())
    }

    /** 断言：不白屏，且候选文案中至少有一条真的显示出来。 */
    private fun assertShowsAny(screen: String, vararg candidates: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        assertTrue("$screen 渲染后没有任何文本节点（白屏 / 提前返回 / 组合异常）", textNodes().isNotEmpty())
        val shown = candidates.any { c ->
            compose.onAllNodes(
                SemanticsMatcher("文本含 $c") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(c) } == true
                }
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("$screen 未渲染出候选文案 ${candidates.toList()} 中的任何一条", shown)
    }

    private fun assertDetailCentralScore(centralScore: Double?, expected: String?) {
        val state = UiState(
            detail = StockDetail(code = "600519", name = "评分隔离测试"),
            detailLoading = false,
            localEngine = LocalEngineResult(
                factors = null, engines = emptyList(), composite = 90.0,
                nValid = 12, barsUsed = 320, weightedComposite = 90.0, centralScore = 90.0,
            ),
            centralEngineScore = centralScore,
            engineSpecVersion = "3.3.0",
        )
        val viewModel = vm()
        compose.setContent { StockDetailSheet(viewModel, state) }
        val texts = textNodes().flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
        }
        assertEquals(
            "中枢评分不得使用旧12引擎的90分兜底",
            listOfNotNull(expected),
            texts.filter { it.startsWith("中枢综合 ") },
        )
        if (expected == null) assertTrue("缺失或非有限评分必须显示 insufficient", "insufficient" in texts)
        assertTrue("旧引擎功能应保留为技术对照", texts.any { it.contains("旧12引擎技术对照，非中枢评分") })
        assertTrue("共振口径不得冒充逐票统计", texts.any { it.contains("按启用规格聚合") && it.contains("3.3.0") })
    }

    @Test(timeout = 120_000)
    fun `详情 中枢缺失不回退旧引擎高分`() = assertDetailCentralScore(null, null)

    @Test(timeout = 120_000)
    fun `详情 中枢低分不被旧引擎高分替代`() =
        assertDetailCentralScore(30.0, "中枢综合 ${String.format("%.1f", 30.0)}")

    @Test(timeout = 120_000)
    fun `详情 中枢NaN显示数据不足`() = assertDetailCentralScore(Double.NaN, null)

    @Test(timeout = 120_000)
    fun `详情 中枢无穷值显示数据不足`() = assertDetailCentralScore(Double.POSITIVE_INFINITY, null)

    @Test(timeout = 120_000)
    fun `篮子诊断 空值不冒充低风险或真实持仓`() {
        compose.setContent { ResearchDiagnosticsCard(null) }
        val texts = textNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(texts.contains("推荐篮子诊断 · 非真实持仓"))
        assertTrue(texts.contains("风险级别：未知"))
        assertTrue(texts.any { it.contains("年化波动 未知") && it.contains("最大回撤 未知") })
    }

    @Test(timeout = 120_000)
    fun `篮子诊断 恢复数据中的字符串数值不伪装有效数字`() {
        val diagnostics = mapOf("coverage" to mapOf("original" to 1, "usable" to 1),
            "adjustmentEvidence" to mapOf("600001" to "cnfin:hfq_factor:synthetic_fixture"),
            "risk" to mapOf("level" to "未知", "volPct" to "12.3", "maxDrawdownPct" to Double.NaN))
        compose.setContent { ResearchDiagnosticsCard(diagnostics) }
        val texts = textNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(texts.any { it.contains("年化波动 未知") && it.contains("最大回撤 未知") })
        assertTrue(texts.none { it.contains("12.30%") || it.contains("NaN") })
    }

    // ── 有静态文案的屏：文案 + 不白屏 ────────────────────────────────────

    @Test(timeout = 120_000)
    fun `决策页 空数据下渲染标题`() = assertShowsAny(
        "DecisionScreen", "市场决策",
    ) { DecisionScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `盘前推股名单 空数据下渲染标题与空态`() = assertShowsAny(
        "PremarketPicksScreen", "盘前推股名单", "名单为空",
    ) { PremarketPicksScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `实时推股名单 空数据下渲染标题与空态`() = assertShowsAny(
        "RealtimePicksScreen", "实时推股名单", "名单为空",
    ) { RealtimePicksScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `首页看板 空数据下渲染入口`() = assertShowsAny(
        "DashboardScreen", "看盘", "看板", "行情",
    ) { DashboardScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `诊断页 空数据下渲染自检标题`() = assertShowsAny(
        "DiagScreen", "数据源自检", "源注册表",
    ) { DiagScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `行情页 空数据下渲染标题`() = assertShowsAny(
        "MarketScreen", "自选行情",
    ) { MarketScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `新闻页 空数据下渲染标题`() = assertShowsAny(
        "NewsScreen", "最新消息", "主线",
    ) { NewsScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `实时总结页 空数据下渲染标题`() = assertShowsAny(
        "RealtimeSummaryScreen", "实时总结",
    ) { RealtimeSummaryScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `板块页 空数据下渲染标题`() = assertShowsAny(
        "SectorsScreen", "板块",
    ) { SectorsScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `题材页 空数据下渲染加载态`() = assertShowsAny(
        "ThemeScreen", "正在加载题材报告", "炒作路径",
    ) { ThemeScreen(vm(), UiState()) }

    // ── 文案由数据/组件拼出的屏：断言不白屏、有文本 ──────────────────────


    // ── 一级入口容器：顶栏切换后不得白屏 ─────────────────────────────

    @Test(timeout = 120_000)
    fun `看板入口 默认页可渲染`() = assertRenders("DashboardHubScreen") { DashboardHubScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `总结入口 默认页可渲染`() = assertRenders("SummaryHubScreen") { SummaryHubScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `消息入口 默认页可渲染`() = assertRenders("NewsHubScreen") { NewsHubScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `选股入口 默认页可渲染`() = assertRenders("PicksHubScreen") { PicksHubScreen(vm(), UiState()) }

    @Test(timeout = 120_000)
    fun `凭据入口 默认页可渲染`() = assertRenders("CredentialsScreen") { CredentialsScreen(vm(), UiState()) }
}
