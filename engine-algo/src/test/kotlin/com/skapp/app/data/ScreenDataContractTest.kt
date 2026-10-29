package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 逐屏数据契约：**每个屏显示哪些数据 → 这些数据从哪来**，以及这些数据在纯 JVM 上能否算出、
 * 缺数据时给的是「不可得」还是 0 / 50。
 *
 * ## 为什么需要它
 *
 * 本仓 19 个 Screen 类至今没有任何测试提及（界面渲染零自动化覆盖），而容器里没有 root /
 * Shizuku / 无障碍，「装包逐屏截图」做不了。能自动化守住的那一层是：屏幕绑定的每个数据点，
 * 其取值、格式化文案与缺数据语义在数据层是确定且可断言的。真机才可能发现的（布局错位、
 * 颜色对比度、截断）这里一律不覆盖，别把这条当渲染验收。
 *
 * ## 屏 → 数据源映射（state 字段见 `ui/src/main/java/com/skapp/app/ui/SkappViewModel.kt` 的 `UiState`）
 *
 * | 屏 | 显示的数据 | 数据来源（state 字段 → 生产方） |
 * |---|---|---|
 * | BoardScreen | 指数、涨跌统计、涨跌幅榜、情绪、涨停池、板块热度 | `state.board` ← `LocalSectors.boardMap(res, pulse, snap)`（`SkappViewModel.kt:1684`）；`state.marketState` ← `MarketStateEngine`；`state.allSourcesSummary` ← `AllSourcesClient.summaryLine`（`ui/.../data/AllSourcesClient.kt:119`）/ `CloudFeed.healthText`（`engine-net/.../CloudFeed.kt:244`） |
 * | DashboardScreen | 盘面解读、市场脉搏、本地推股、行情、数据源健康 | `state.summary`（云端 summary 产物）、`state.marketPulse` ← `LivePulse`、`state.localPremarketPicks`/`localMiddayPicks` ← `LocalPicks`、`state.picks` ← `TodayPicks` |
 * | DecisionScreen | 决策卡、因子否决板、板块主线、聪明钱 | `state.decision` ← 云端 decision 产物、`state.factorBoard` ← `FactorWeightStrategy` + `SignalCalibration`、`state.sectorBoard` ← `SectorLink`、`state.smartMoney` ← `engine-net/SmartMoney.kt` |
 * | DiagScreen | 数据源台账、登记表统计、健康守卫 | `state.dataSources` ← `DataSourcesSnapshot`（`engine-net/SkappApi.kt:368`）、`state.registryStats` ← `AllSourcesClient`、`HealthGuard` ← `ProcessHealthCore` |
 * | EnginesScreen | 本地实时候选、中枢引擎、规格版本/漂移、链路状态 | `state.screened` ← `LocalScreener`、`state.localEngine` ← `LocalEngine`、`state.engineSpecVersion`/`engineSpecDrift` ← `EngineSpecLoader`、`state.chainStatus`/`chainDegraded` ← `EngineChain` |
 * | HubScreen | 全局状态带（规格版本/数据时点/降级项）、聚合分、多池 | `state.engineSpecVersion`/`engineSpecDrift`/`chainDegraded`/`screenedAt`/`canonicalScore` ← `CanonicalEngine`、`state.pools` |
 * | MarketScreen | 自选股实时报价、指数、时段文案、搜索 | `state.quotes`（行情快照）、`state.marketPhase` ← `MarketHours.describe`、`state.searchResults` ← `SymbolIndex.search` |
 * | NewsScreen | 新闻快讯、新闻计算（利好判定/命中股） | `state.news` ← `NewsSnapshot`、`state.newsCalcs` ← `NewsCalcEngine`（`engine-runtime`） |
 * | PoolsScreen | 多池名单（含实时补价）、板块降级说明 | `state.pools` ← `DbTables.pools` + `repricePools`（`SkappViewModel.kt:1699`）、`state.sectors.freshBoards/staleBoards` |
 * | RealtimeSummaryScreen | 盘后摘要、复盘、胜率矩阵、本地推股、异动 | `state.summary`、`state.closeReview`/`winrateMatrix`（`Models.kt`）、`state.premarketDraft` ← `PremarketPicks`、`state.screened` |
 * | SectorsScreen | 行业/概念列表、板块成分 | `state.sectors` ← `LocalSectors` / `SkappApi`（`SectorsSnapshot`，`Models.kt:277`） |
 * | StockDetailSheet | 实时报价、日 K、分时、资金流、AI 复盘、中枢引擎 | `state.liveQuote`、`state.kline` ← `KlineApi`、`state.fundFlow`、`state.aiReview`、`state.centralEngine*` ← `EngineChain` |
 * | StockDetailTail | 十关键位、回本率、退出纪律、29 因子库、财务摘要 | `state.keyLevels` ← `KeyLevels.compute`（`SkappViewModel.kt:1318`）、`state.recoup` ← `RecoupCalc.compute`（`SkappViewModel.kt:1321`）、`state.exitPlan` ← `ExitPlan`、`ExtendedIndicators.allFactors`（`StockDetailTail.kt:272`） |
 * | ThemeScreen | 题材报告、相关新闻 | `state.themeReport`（云端 theme_report.json）、`state.news` |
 * | UnifiedAskScreen | 市场总结文本/时点、AI 复盘、问答 | `state.summary`、`state.aiReview`、`state.aiAnswer`（GitCode AI）、离线兜底 `AskEngine.answer` |
 * | AiScreen / PicksScreen / SummaryScreen | 纯路由，无自有数据 | `AiScreen` → `UnifiedAskScreen`；`PicksScreen`/`SummaryScreen` → `RealtimeSummaryScreen` |
 * | BoardRules.kt / StockDetailCharts.kt | 非独立屏，是被上面屏引用的组成件 | 图表吃 `bars`/`quotes` 纯数据（`StockDetailCharts.kt:51`） |
 *
 * ## 覆盖的断言（本文件覆盖的用例）
 *
 * 1. 数据源健康聚合的计数与文案（Board/Dashboard 状态带）——真实 `testdata/cloud/` 下的云端产物
 * 2. 单条数据源状态文案（DiagScreen 每行状态）——显式输入走全部分支
 * 3. 十关键位（详情页）——`testdata/parity/golden_v5.json` 的 130 根真实 K 线
 * 4. 回本率与样本说明（详情页）——同一批 K 线 + 不足 80 根的降级分支
 * 5. 回本率门槛「通过/不通过」文案——显式构造 `RecoupCalc.Result` 的真值表
 * 6. 29 因子库键集与缺数据为 null（详情页因子库）——同一批 K 线 + 空输入
 * 6b. 涨停基因计数（`zt_count_20d/60d`）——站点 9.5% 口径与窗口边界
 * 7. K 线尾部统计（涨跌幅 / 振幅 / 量比）——与夹具原始 OHLCV 独立算术对账
 * 8. 时段文案与自动刷新间隔（MarketScreen/BoardScreen 的相位带）——显式 CST 时间点
 * 9. 搜索框的归一化/去重/上限（MarketScreen）——显式 `SymbolEntry` 输入
 * 10. 个股问答的缺数据语义与数字格式化（问答页）——显式 `StockDetail` 输入
 *
 * ## 纪律
 *
 * 期望值只来自两类地方：夹具里真实存在的字段，或被测函数的**显式输入**。
 * 没有任何一条是「先跑一遍被测函数，再把返回值当期望」——那种断言只能证明函数没被改，
 * 证明不了屏幕上的口径是对的。
 *
 * ## 两个口径问题的处理方式（按仓库现有口径定，不在本文件里改 main 代码）
 *
 * 1. **因子库 29 vs 数据层 27（已补齐，本文件锁 29）**：站点参考实现
 *    `stocks-ai-view/scripts/factor_lib.py` 用 `@factor` 登记 **29 个因子**，端上
 *    `ExtendedIndicators.allFactors` 已补 `zt_count_20d` / `zt_count_60d`（涨停基因，
 *    站点口径：`(close/close.shift(1)-1) >= 0.095` 的根数，rolling 20/60 求和）。
 *    口径取「29」（站点 + 本仓 6 处文案一致，ADR 0006 也写 29）。
 * 2. **十关键位的百点台阶对低价个股给出负价（口径：不可能的价格不上屏，已修）**：
 *    `KeyLevels.kt` 的台阶是「顶部取整到百点再每 100 点一档」（指数口径，个股沿用），
 *    个股价格远低于 100 元时下方几档变负数——实测 `sh600017`（现价 2.75）的 `dipBuy` 为
 *    `-100.0`。站点无对应实现（该引擎是端上原创），所以只有「价格不能为负」这一条硬口径。
 *    处理：台阶只保留 >0 的档位，止损位算出负价时退回「跌破支撑止损」。
 */
class ScreenDataContractTest {

    // ── 夹具 ───────────────────────────────────────────────────────
    private fun fixture(relative: String): File {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", relative)
        if (!f.isFile) fail("缺少夹具：${f.absolutePath}")
        return f
    }

    private fun cloudJson(name: String): JSONObject = JSONObject(fixture("cloud/$name").readText())

    private fun goldenV5(): JSONObject = JSONObject(fixture("parity/golden_v5.json").readText())

    /** 黄金向量里的真实日 K（6 只票 × 130 根），字段名与端上 [KBar] 一一对应。 */
    private fun cases(): List<Pair<String, List<KBar>>> {
        val arr = goldenV5().getJSONArray("cases")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            c.getString("code") to barsOf(c.getJSONArray("bars"))
        }
    }

    private fun barsOf(arr: JSONArray): List<KBar> = (0 until arr.length()).map { i ->
        val b = arr.getJSONObject(i)
        KBar(
            date = b.optString("d", "2026-01-01"),
            open = b.getDouble("o"),
            close = b.getDouble("c"),
            high = b.getDouble("h"),
            low = b.getDouble("l"),
            vol = b.optDouble("v", 0.0),
        )
    }

    // ── 渲染口径 ───────────────────────────────────────────────────
    private fun assertRenderable(label: String, text: String) {
        assertTrue(text.isNotBlank(), "$label 渲染为空")
        for (bad in listOf("null", "NaN", "Infinity")) {
            assertTrue(!text.contains(bad), "$label 含不可渲染片段「$bad」：$text")
        }
    }

    /** 详情页对缺失值一律显示 `—`，不许落成 0：这是「不可得」与「值就是 0」的区别。 */
    private fun pct1OrDash(v: Double?): String =
        v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"

    // ── 1. 数据源健康聚合 ─────────────────────────────────────────
    @Test
    fun `数据源健康聚合的计数与文案与登记表一致`() {
        val docs = listOf(
            // 三个真实云端产物 + 一个拉取失败：Board/Dashboard 状态带与诊断页都直接显示这句
            CloudFeed.CloudDoc(CloudFeed.Doc.MARKET_PHASE, cloudJson("market_phase.json"), 0L, 1.0),
            CloudFeed.CloudDoc(CloudFeed.Doc.ENGINE_FUSION, cloudJson("engine_fusion.json"), 0L, 0.25),
            CloudFeed.CloudDoc(CloudFeed.Doc.ENGINE_V5, cloudJson("engine_v5.json"), 0L, 48.0),
            CloudFeed.CloudDoc(CloudFeed.Doc.DECISION, null, 0L, null, error = "HTTP 404"),
        )
        // 3 个可用（其中 1 个超过 36h 判陈旧）+ 1 个不可用
        assertEquals("3/4 可用 · 1 陈旧 · 1 失败", CloudFeed.healthText(docs))
        assertEquals("未加载", CloudFeed.healthText(emptyList()),
            "一个源都没加载时必须说「未加载」，不能显示 0/0 可用")

        // 全部新鲜时不得出现「陈旧 / 失败」片段，否则界面会常年挂着一句假警告
        assertEquals("2/2 可用", CloudFeed.healthText(docs.take(2)))
    }

    // ── 2. 单条数据源状态文案 ─────────────────────────────────────
    @Test
    fun `单条数据源状态文案覆盖不可用未知与三档时长`() {
        val json = cloudJson("market_phase.json")
        fun doc(ageHours: Double?, j: JSONObject? = json) =
            CloudFeed.CloudDoc(CloudFeed.Doc.MARKET_PHASE, j, 0L, ageHours)

        assertEquals("不可用", doc(null, j = null).tsText, "拉不到的源必须直说不可用")
        assertEquals("时间未知", doc(null).tsText,
            "解析不出 ts 时说「时间未知」，不许假定它新鲜")
        assertEquals("30 分钟前", doc(0.5).tsText)
        assertEquals("2 小时前", doc(2.5).tsText)
        assertEquals("2 天前", doc(48.0).tsText)
        assertEquals(true, doc(48.0).stale, "超过 36h 必须判陈旧（FreshnessPolicy.CLOUD_STALE_HOURS）")
        assertEquals(false, doc(1.0).stale)
    }

    // ── 3. 十关键位 ───────────────────────────────────────────────
    @Test
    fun `十关键位在详情页可渲染且梯子围绕现价`() {
        val cases = cases()
        assertTrue(cases.isNotEmpty(), "golden_v5 里没有 K 线用例")
        for ((code, bars) in cases) {
            val levels = KeyLevels.compute(bars)
                ?: fail("$code 有 ${bars.size} 根真实 K 线却算不出十关键位")
            // 详情页的「现价」就是最后一根收盘，不是另取一个价
            assertEquals(bars.last().close, levels.cur, 1e-12, "$code 现价与最后一根收盘不一致")
            val tens = listOf(
                "阻力位" to levels.resistance, "压力位" to levels.pressure,
                "承压位" to levels.bearPressure, "反弹位" to levels.rebound,
                "筹码位" to levels.chip, "资金位" to levels.moneyCost,
                "回调位" to levels.pullback, "支撑位" to levels.support,
                "抄底位" to levels.dipBuy,
            )
            for ((label, v) in tens) {
                // 价格位必须是正数：低价个股的台阶位丢掉负价后不会落到这里，出现非正价说明算错了
                assertTrue(v.isFinite() && v > 0.0, "$code $label 非正价格：$v")
                assertRenderable("$code $label 文案", String.format(Locale.US, "%.2f", v))
            }
            levels.ladderBelow.forEach {
                assertTrue(it > 0.0, "$code 下方台阶 $it 不是正价")
                assertTrue(it < levels.cur, "$code 下方台阶 $it 不在现价 ${levels.cur} 之下")
            }
            levels.ladderAbove.forEach {
                assertTrue(it > 0.0, "$code 上方台阶 $it 不是正价")
                assertTrue(it > levels.cur, "$code 上方台阶 $it 不在现价 ${levels.cur} 之上")
            }
            assertRenderable("$code 买卖位建议", levels.trade)
        }
        // K 线不足 80 根时详情页不显示这张卡（返回 null），不许硬算一组假位
        assertNull(KeyLevels.compute(cases.first().second.take(79)),
            "不足 80 根仍返回关键位，详情页会显示一组没有样本支撑的价格")
    }

    // ── 4. 回本率 ─────────────────────────────────────────────────
    @Test
    fun `回本率与样本说明可渲染且不足八十根时全为空`() {
        for ((code, bars) in cases()) {
            val r = RecoupCalc.compute(bars)
            assertEquals(bars.size, r.n)
            for ((label, v) in listOf(
                "22 日回本率" to r.recoup22, "60 日回本率" to r.recoup60,
                "均最高涨幅" to r.avgMaxGain22, "一年位置" to r.posInYear,
            )) {
                val value = v ?: fail("$code 有 130 根 K 线却拿不到 $label")
                assertTrue(value.isFinite(), "$code $label 非有限值：$value")
                assertTrue(value in -100.0..100.0 || label.contains("涨幅"), "$code $label 越界：$value")
            }
            // 回本率是百分比占比，必须落在 0..100
            assertTrue(r.recoup22!! in 0.0..100.0, "$code 22 日回本率越界：${r.recoup22}")
            assertTrue(r.recoup60!! in 0.0..100.0, "$code 60 日回本率越界：${r.recoup60}")
            assertTrue(r.posInYear!! in 0.0..100.0, "$code 一年位置越界：${r.posInYear}")
            assertTrue(r.sample.contains(bars.size.toString()), "$code 样本说明未写根数：${r.sample}")
            assertRenderable("$code 回本文案", pct1OrDash(r.recoup22))
            // 详情页门槛文案：「通过 / 不通过」。判定口径见 RecoupCalc.gatePass KDoc
            assertEquals(
                r.recoup22!! >= 70.0 && !(r.posInYear!! > 90.0 && r.avgMaxGain22!! < 2.0),
                RecoupCalc.gatePass(r),
                "$code 回本门槛判定与阈值口径不一致",
            )
        }
        val short = RecoupCalc.compute(cases().first().second.take(60))
        assertEquals("历史不足80日", short.sample)
        assertTrue(short.recoup22 == null && short.recoup60 == null &&
            short.avgMaxGain22 == null && short.posInYear == null,
            "样本不足时四个数必须是 null（详情页显示「—」），不许拿 0 顶")
        assertEquals("—", pct1OrDash(short.recoup22), "缺数据必须渲染成「—」")
    }

    // ── 5. 回本率门槛真值表 ───────────────────────────────────────
    @Test
    fun `回本率门槛的通过与不通过边界`() {
        fun r(recoup22: Double, avgMaxGain22: Double, posInYear: Double) =
            RecoupCalc.Result(100, recoup22, 80.0, avgMaxGain22, posInYear, "近一年")

        assertEquals(true, RecoupCalc.gatePass(r(72.0, 5.0, 50.0)), "回本率 72% 且位置不高应通过")
        assertEquals(false, RecoupCalc.gatePass(r(60.0, 5.0, 50.0)), "回本率 60% 低于 70% 门槛必须降级")
        assertEquals(false, RecoupCalc.gatePass(r(90.0, 1.5, 95.0)),
            "高位（>90）且均最高涨幅 <2% 必须降级——这正是「回本率高但追高套人」的形态")
        assertEquals(true, RecoupCalc.gatePass(r(90.0, 5.0, 95.0)),
            "高位但最高涨幅够大（≥2%）不算套人")
        assertEquals(true, RecoupCalc.gatePass(r(70.0, 5.0, 50.0)), "门槛取 ≥70，恰好 70 应通过")
    }

    // ── 6. 因子库 ────────────────────────────────────────────────
    @Test
    fun `因子库键集固定且缺数据必须是空值`() {
        val bars = cases().first().second
        val factors = ExtendedIndicators.allFactors(bars)
        assertEquals(29, factors.size,
            "因子库键数漂移（现为 29，对齐站点 factor_lib.py）：${factors.keys}")
        for (k in listOf("ret_1d", "amplitude", "vol_ratio_5d", "vol_20d", "position_240d", "close_position")) {
            assertTrue(factors.containsKey(k), "因子库缺 $k，详情页会少一行")
        }
        // 130 根 < 240 根：长周期分位必须是 null（详情页显示「暂无」），不许填 0 或 50
        assertNull(factors["position_240d"], "K 线不足 240 根却给了 240 日位置，界面会把「不可得」显示成真实分位")
        assertTrue(factors["ret_1d"] != null && factors["amplitude"] != null,
            "有 K 线时短周期因子必须出值")

        // 空 K 线：所有因子为 null（界面走「K 线不足 30 根」分支，数据层也不许造数）
        val empty = ExtendedIndicators.allFactors(emptyList())
        assertEquals(29, empty.size)
        empty.forEach { (k, v) -> assertNull(v, "没有 K 线时因子 $k 却是 $v，界面会显示一个凭空算出的数") }

        // 界面文案里的「N 因子」取 ExtendedIndicators.factorCount，必须与键集同源
        assertEquals(empty.size, ExtendedIndicators.factorCount,
            "factorCount 与因子键集不同源，界面文案会与实际行数不符")

        // 5 根：短周期出值、长周期为 null——同一张表里两种语义必须分得开
        val tiny = ExtendedIndicators.allFactors(bars.take(5))
        assertTrue(tiny["ret_1d"] != null, "5 根 K 线足够算单日涨跌幅")
        assertNull(tiny["momentum_20d"], "5 根 K 线算不出 20 日动量")
        assertNull(tiny["vol_20d"], "5 根 K 线算不出 20 日波动率")
    }

    // ── 6b. 涨停基因（站点 factor_lib.py 口径：涨跌幅 >= 0.095 的根数）──
    @Test
    fun `涨停基因按站点口径计数`() {
        fun bar(c: Double) = KBar("2026-01-01", open = c, close = c, high = c, low = c, vol = 1.0)
        // 21 根：只有一根 +9.5%（边界值，站点写的是 >=）
        val bars = (0..20).map { if (it <= 2) bar(100.0) else bar(109.5) }
        val f = ExtendedIndicators.allFactors(bars)
        assertEquals(1.0, f["zt_count_20d"], "20 日窗口内恰好一根 +9.5%，边界含等号必须计 1 次")
        assertNull(f["zt_count_60d"], "21 根凑不满 60 日窗口，必须是 null，不许拿 1 充 60 日")
        // 20 根整：首根无涨跌幅，站点 rolling(20).sum() 为 NaN → 端上也必须是 null
        assertNull(ExtendedIndicators.allFactors(bars.take(20))["zt_count_20d"],
            "20 根只凑出 19 个涨跌幅，20 日涨停次数应为 null")
        // 前收 0 的脏数据：相除得 Infinity，不许被当成一次涨停
        val dirty = (0..20).map { if (it == 10) bar(0.0) else bar(100.0) }
        assertEquals(0.0, ExtendedIndicators.allFactors(dirty)["zt_count_20d"],
            "前收为 0 时涨跌幅不可算，不能计入涨停次数")
    }

    // ── 7. K 线尾部统计（涨跌幅 / 振幅 / 量比）─────────────────────
    @Test
    fun `K线尾部涨跌幅振幅量比与夹具原始值一致`() {
        for ((code, bars) in cases()) {
            val last = bars.last()
            val prev = bars[bars.size - 2]

            // 涨跌幅：末两根收盘
            val expectRet = (last.close - prev.close) / prev.close
            assertEquals(expectRet, ExtendedIndicators.momentum(bars, 1)!!, 1e-12,
                "$code 单日涨跌幅与夹具原始收盘不一致")

            // 振幅：(高 - 低) / 开
            val expectAmp = (last.high - last.low) / last.open
            assertEquals(expectAmp, ExtendedIndicators.amplitude(bars, 1)!!, 1e-12,
                "$code 振幅公式与夹具原始 OHLC 不一致")
            assertTrue(expectAmp >= 0.0, "$code 振幅为负：$expectAmp")

            // 量比：当根量 / 前 5 根均量
            val avgVol = bars.dropLast(1).takeLast(5).map { it.vol }.average()
            val expectVr = last.vol / avgVol
            assertEquals(expectVr, MarketDataClient.volumeRatio(bars, 5)!!, 1e-12,
                "$code 量比取值范围与夹具原始成交量不一致")
            assertTrue(expectVr > 0.0, "$code 量比必须为正：$expectVr")

            assertRenderable("$code 涨跌幅文案", String.format(Locale.US, "%+.2f%%", expectRet * 100))
            assertRenderable("$code 振幅文案", String.format(Locale.US, "%.2f%%", expectAmp * 100))
            assertRenderable("$code 量比文案", String.format(Locale.US, "%.2f", expectVr))
        }
    }

    // ── 8. 时段文案（MarketScreen / BoardScreen）──────────────────
    @Test
    fun `时段文案与自动刷新间隔覆盖全天各相位`() {
        fun cst(month: Int, day: Int, hour: Int, minute: Int): Date =
            Calendar.getInstance(TimeZone.getTimeZone(MarketHours.TZ_CST)).apply {
                set(2026, month - 1, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

        // 2026-10-29 是周四且非节假日/补班日
        val phases = listOf(
            cst(10, 29, 8, 0) to "盘前",
            cst(10, 29, 9, 20) to "开盘竞价",
            cst(10, 29, 10, 0) to "上午交易中",
            cst(10, 29, 12, 0) to "午间休市",
            cst(10, 29, 14, 0) to "下午交易中",
            cst(10, 29, 15, 2) to "收盘竞价",
            cst(10, 29, 16, 0) to "已收盘",
        )
        for ((at, label) in phases) {
            assertEquals(label, MarketHours.describe(at), "时段文案错误（界面状态带直接显示它）")
            assertRenderable("时段文案 $label", MarketHours.describe(at))
        }
        // 国庆长假与周六调休补班：两条国定日历规则必须生效
        assertEquals("休市日", MarketHours.describe(cst(10, 1, 10, 0)), "法定节假日必须判休市")
        assertEquals("上午交易中", MarketHours.describe(cst(10, 10, 10, 0)), "调休补班的周六必须交易日")

        // nextRefreshIn：到下一次自动刷新的毫秒数（交易/竞价中为 0）
        assertEquals(75 * 60_000L, MarketHours.msUntilAutoRefresh(cst(10, 29, 8, 0)), "08:00 应等到 09:15")
        assertEquals(80 * 60_000L, MarketHours.msUntilAutoRefresh(cst(10, 29, 11, 40)), "午休中应等到 13:00")
        assertEquals(60 * 60_000L, MarketHours.msUntilAutoRefresh(cst(10, 29, 12, 0)), "午休应等到 13:00")
        // 收盘后：等到次日 09:15（用两个时间点相减，避免手算分钟出错）
        assertEquals(
            cst(10, 30, 9, 15).time - cst(10, 29, 15, 6).time,
            MarketHours.msUntilAutoRefresh(cst(10, 29, 15, 6)),
            "收盘后应等到次日 09:15",
        )
        assertEquals(0L, MarketHours.msUntilAutoRefresh(cst(10, 29, 10, 0)), "交易中不该再计时")
        assertEquals(0L, MarketHours.msUntilAutoRefresh(cst(10, 29, 15, 2)), "收盘竞价必须算需要刷新")
    }

    // ── 9. 搜索框（MarketScreen）───────────────────────────────
    @Test
    fun `搜索框按代码精确优先并去重且遵上限`() {
        val index = SymbolIndex(
            listOf(
                SymbolEntry("600519", "贵州茅台"),
                SymbolEntry("sh600519", "贵州茅台酒"), // 同一只票的另一种写法
                SymbolEntry("000001", "平安银行"),
                SymbolEntry("hk00700", "腾讯控股"),
                SymbolEntry("300750", "宁德时代"),
                SymbolEntry("601398", "工商银行"),
                SymbolEntry("002594", null), // 名称缺失的票（byName 不得收录）
            ),
        )
        // 同一代码多种写法只留一条，否则下拉列表会出现重复行
        assertEquals(6, index.byCode.size)
        assertEquals("600519", index.byCode["600519"]?.code)
        // 重名时保留先出现的中文名（不因别名覆盖）
        assertEquals("贵州茅台", index.byCode["600519"]?.name)

        assertEquals("600519", index.search("600519").first().code, "代码必须能直接命中")
        assertEquals("600519", index.search(" 600519 ").first().code, "前后空格不能挡住命中")
        assertEquals("hk00700", index.search("hk00700").first().code, "港股代码要原样认")
        assertEquals("600519", index.search("贵州 茅台").first().code, "名称搜索要忽略空格")
        assertTrue(index.search("腾讯").any { it.code == "hk00700" }, "名称片段应能命中港股")

        assertEquals(emptyList(), index.search("   "), "空查询必须返回空，不能把全表倒出来")
        assertEquals(emptyList(), index.search("600519", limit = 0))
        val capped = index.search("银行", limit = 1)
        assertEquals(1, capped.size, "搜索上限未生效（界面只排得下几条）")
        assertEquals(capped.size, capped.map { it.code }.distinct().size, "结果里有重复代码")
        // 名称缺失的票只能按代码命中，不得进入名称索引
        assertTrue(index.search("002594").any { it.code == "002594" })
    }

    // ── 10. 个股问答（离线兜底）──────────────────────────────────
    @Test
    fun `个股问答缺数据时只说暂无数据且不伪造数字`() {
        val engine = AskEngine(emptyList(), emptyMap())

        // 没有匹配到股票：给引导文案，不是空白答案
        assertRenderable("无匹配回答", engine.answer("600519 现在能买吗", emptyList()))
        assertTrue(engine.answer("600519 现在能买吗", emptyList()).contains("没有找到匹配的股票"))

        // 除代码外没有任何数据：每个意图都必须明说「暂无数据」
        val bare = StockDetail(code = "600519", name = "贵州茅台")
        val expects = listOf(
            "财务怎么样" to "财务数据：暂无数据",
            "有什么风险" to "主要风险：暂无数据",
            "技术面怎么样" to "技术指标：暂无数据",
            "属于什么板块" to "所属板块：暂无数据",
            "打多少分" to "引擎评分：暂无数据",
        )
        for ((q, expect) in expects) {
            val ans = engine.answer(q, listOf(bare))
            assertTrue(ans.contains(expect), "问「$q」应回答「$expect」，实际：$ans")
        }
        // 没有报价时不许出现被 0 顶替的现价/涨跌幅
        val noQuote = engine.answer("贵州茅台现价多少", listOf(bare))
        assertTrue(!noQuote.contains("现价：0"), "无报价却渲染出「现价：0」：$noQuote")
        assertTrue(!noQuote.contains("0.00%"), "无涨跌幅却渲染出 0.00%：$noQuote")

        // 有真实值时按详情页口径格式化（千元以上取整、涨跌幅两位小数）
        val rich = StockDetail(
            code = "600519", name = "贵州茅台",
            detail = BundleDetail(
                status = "ok",
                quote = mapOf("price" to 1688.4, "pctChg" to 1.234),
                financialLatest = mapOf("roe" to 12.345, "pe" to 22.0),
            ),
            boards = listOf("白酒", "消费"),
        )
        val richAnswer = engine.answer("贵州茅台现价多少", listOf(rich))
        assertTrue(richAnswer.contains("现价：1688"), "现价格式化口径变了：$richAnswer")
        assertTrue(richAnswer.contains("涨跌幅 1.23%"), "涨跌幅格式化口径变了：$richAnswer")
        val finAnswer = engine.answer("财务怎么样", listOf(rich))
        assertTrue(finAnswer.contains("ROE 12.35"), "财务字段标签/格式变了：$finAnswer")
        assertTrue(finAnswer.contains("所属板块：白酒"), "命中数据时板块必须照实列出：$finAnswer")
    }
}
