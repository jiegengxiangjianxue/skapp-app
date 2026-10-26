package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.AllSourcesClient
import com.skapp.app.data.BanProb
import com.skapp.app.data.BanProbClient
import com.skapp.app.data.MarketState
import com.skapp.app.data.MarketStateEngine
import com.skapp.app.data.aggregateKline
import com.skapp.app.data.AskEngine
import com.skapp.app.data.DataCache
import com.skapp.app.data.EngineFeed
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.Decision
import com.skapp.app.data.DecisionCard
import com.skapp.app.data.HoloBundle
import com.skapp.app.data.NewsCalc
import com.skapp.app.data.NewsCalcEngine
import com.skapp.app.data.HoloClient
import com.skapp.app.data.KBar
import com.skapp.app.data.KPeriod
import com.skapp.app.data.KlineApi
import com.skapp.app.data.LocalEngine
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.data.LocalNewsResult
import com.skapp.app.data.LocalScreener
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.MarketHours
import com.skapp.app.data.BundleDetail
import com.skapp.app.data.MarketSnapshot
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.data.PickSignal
import com.skapp.app.data.Quote
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.data.AiRouter
import com.skapp.app.data.GitCodeAiClient
import com.skapp.app.data.GitHubModelsClient
import com.skapp.app.data.SkappApi
import com.skapp.app.data.StockDetail
import com.skapp.app.data.TechnicalCalc
import com.skapp.app.data.SectorsSnapshot
import com.skapp.app.data.SymbolEntry
import com.skapp.app.data.TodayPicks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 自选股：SharedPreferences 持久化 + Compose 可观察，支持增删 */
object Watchlist {
    private const val PREF = "watchlist"
    private const val KEY = "codes"
    private val DEFAULT_CODES = listOf("600519", "002594", "600941", "600036", "hk00700")

    val codes = mutableStateListOf<String>()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null)
        codes.clear()
        codes.addAll(saved?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_CODES)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, codes.joinToString(",")).apply()
    }

    fun add(context: Context, code: String) {
        val c = code.trim()
        if (c.isEmpty() || codes.contains(c)) return
        codes.add(c)
        persist(context)
    }

    fun remove(context: Context, code: String) {
        if (codes.remove(code)) persist(context)
    }

    fun contains(code: String?): Boolean = code != null && codes.contains(code)
}

data class QuoteRow(
    val code: String,
    val display: String,
    val name: String,
    val price: Double?,
    val pctChg: Double?,
    val stale: Boolean,
)

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val quotes: List<QuoteRow> = emptyList(),
    val quoteTs: String? = null,
    val marketPulse: List<com.skapp.app.data.MarketPulseRow> = emptyList(),
    val marketPulseTs: String? = null,
    val marketPulseLoading: Boolean = false,
    val picks: TodayPicks = TodayPicks(),
    val premarketPicks: com.skapp.app.data.StructuredPickArtifact = com.skapp.app.data.StructuredPickArtifact(session = "premarket"),
    val middayPicks: com.skapp.app.data.StructuredPickArtifact = com.skapp.app.data.StructuredPickArtifact(session = "midday"),
    val closeReview: com.skapp.app.data.CloseReview = com.skapp.app.data.CloseReview(),
    val winrateMatrix: com.skapp.app.data.WinrateMatrix = com.skapp.app.data.WinrateMatrix(),
    val decision: Decision = Decision(),
    val market: MarketSnapshot = MarketSnapshot(),
    val news: NewsSnapshot = NewsSnapshot(),
    val sectors: SectorsSnapshot = SectorsSnapshot(),
    /** 引擎产出聚合（engines_feed：regime/cycle/anomaly/price_level/orchestrator） */
    val engineFeeds: com.skapp.app.data.EngineFeed.EngineFeeds? = null,
    val symbols: List<SymbolEntry> = emptyList(),
    val searchResults: List<SymbolEntry> = emptyList(),
    val detail: StockDetail? = null,
    val detailLoading: Boolean = false,
    val kline: List<KBar> = emptyList(),
    val klineLoading: Boolean = false,
    val askQuestion: String = "",
    val askAnswer: String? = null,
    val askLoading: Boolean = false,
    val askMatches: List<StockDetail> = emptyList(),
    val marketPhase: String = "加载中",
    val autoRefresh: Boolean = false,
    val nextRefreshIn: Long? = null,
    val aShareIndices: List<Map<String, Any?>> = emptyList(),
    val globalIndices: List<Map<String, Any?>> = emptyList(),
    val localEngine: LocalEngineResult? = null,
    /** 当前详情的中枢引擎总结；无凭据时直接展示此结果。 */
    val centralEngineSummary: String? = null,
    val centralEngineScore: Double? = null,
    val centralEngineResonance: Double? = null,
    val centralEngineStatus: String = "insufficient",
    val detailAdjust: String = "qfq",
    val fundFlow: List<Map<String, Any?>> = emptyList(),
    /** 当日分钟级资金流（交易时段实时） */
    val fundFlowLive: List<Map<String, Any?>> = emptyList(),
    val boardsOf: List<Map<String, Any?>> = emptyList(),
    val minuteTrend: List<Map<String, Any?>> = emptyList(),
    val liveQuote: Quote? = null,
    /** 推股列表的实时报价快照（code -> Quote），避免显示过期的本地快照价 */
    val pickQuotes: Map<String, Quote> = emptyMap(),
    /** K 线周期（日/周/月） */
    val kPeriod: KPeriod = KPeriod.DAY,
    /** 市场状态机输出 */
    val marketState: MarketState? = null,
    val marketStateLoading: Boolean = false,
    /** 云端 190 源合并产物摘要（兜底数据源） */
    val allSourcesSummary: String? = null,
    val allSourcesQuotes: Map<String, AllSourcesClient.Quote> = emptyMap(),
    /** ban_prob 涨停次日表现概率（含样本均衡结果） */
    val banProb: BanProb? = null,
    val banProbLoading: Boolean = false,
    /** stocks-holo 全量 bundle */
    val holo: HoloBundle? = null,
    /** 本地实时选股结果 */
    val screened: List<LocalScreener.Screened> = emptyList(),
    val screening: Boolean = false,
    val screenMsg: String? = null,
    /** 当前扫描模式：score 达标池 / all 全分析池 / limitup 涨停扫描（同模式结果缓存复用） */
    val scanMode: String = "",
    /** 逐条快讯的实时算法计算（直接显示在标题下） */
    val newsCalcs: List<NewsCalc> = emptyList(),
    val newsCalcLoading: Boolean = false,
    val summaryRefreshing: Boolean = false,
    val summaryRefreshMessage: String? = null,
    /** GitCode AI */
    val aiToken: String = "",
    val aiConfigured: Boolean = false,
    /** GitHub Models（云端 AI 第二源，GitCode 的备份） */
    val githubToken: String = "",
    val githubConfigured: Boolean = false,
    /** AiRouter 双源状态行（可用 provider + 调用/失败/冷却） */
    val aiProviderStatus: String = "",
    /** 最近一次 AI 调用实际走的 provider */
    val aiLastProvider: String? = null,
    val aiReview: String? = null,
    val aiReviewLoading: Boolean = false,
    val aiAnswer: String? = null,
    val aiAnswerLoading: Boolean = false,
    val aiQuestion: String = "",
    /** 多池选股（网页版 8 池） */
    val pools: List<SiteBoardClient.Pool> = emptyList(),
    val poolsLoading: Boolean = false,
    /** 看板 */
    val board: Map<String, Any?> = emptyMap(),
    /** 实时总结 */
    val summary: Map<String, Any?> = emptyMap(),
    /** 题材报告，优先 theme_report，旧热点只作页面显式 fallback。 */
    val themeReport: JSONObject? = null,
    /** registry 健康度；远端不可达时字段为空，页面显示 unknown。 */
    val registryStats: Map<String, Any?> = emptyMap(),
)

class SkappViewModel : ViewModel() {

    companion object {
        private const val PREFS = "skapp_prefs"
        private const val KEY_TOKEN = "gitcode_token"
        private const val KEY_GITHUB_TOKEN = "github_token"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var quoteJob: Job? = null
    private var newsJob: Job? = null
    private var pulseJob: Job? = null
    private var detailJob: Job? = null
    private var detailLiveJob: Job? = null
    private var askEngine: AskEngine? = null
    private var stockNames: Map<String, String> = emptyMap()
    private var searchQuery: String = ""

    private suspend fun <T> requestResult(block: suspend () -> T): Result<T> = try {
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    init {
        // 不在 init 里 load()：token 要等 loadSavedToken 恢复；此时拉数据会因无鉴权全失败（app 打开全空的根因）
        startQuoteLoop()
        startNewsLoop()
        startMarketPulseLoop()
    }

    /** MainActivity 启动时调用：从 SharedPreferences 恢复 GitCode + GitHub token，并初始化双源路由 */
    fun loadSavedToken(context: android.content.Context) {
        val sp = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val t = sp.getString(KEY_TOKEN, "") ?: ""
        val gh = sp.getString(KEY_GITHUB_TOKEN, "") ?: ""
        _context = context.applicationContext
        AiRouter.configure(context.applicationContext, t, gh)
        _state.value = _state.value.copy(
            aiToken = t, aiConfigured = t.isNotBlank(),
            githubToken = gh, githubConfigured = gh.isNotBlank(),
            aiProviderStatus = AiRouter.statusLine(),
        )
    }

    /** 保存 token 到 SharedPreferences */
    fun saveToken(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, _state.value.aiToken).apply()
    }

    /** 保存 GitHub token 到 SharedPreferences */
    fun saveGithubToken(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_GITHUB_TOKEN, _state.value.githubToken).apply()
    }

    /** 设置页「保存 GitHub token」按钮 */
    fun saveGithubTokenNow() {
        _context?.let { ctx ->
            saveGithubToken(ctx)
            GitHubModelsClient.setToken(_state.value.githubToken)
            refreshAiProviderStatus()
        }
    }

    /** 刷新双源状态行（设置页显示用） */
    fun refreshAiProviderStatus() {
        _state.value = _state.value.copy(aiProviderStatus = AiRouter.statusLine())
    }

    /** AiScreen 的「保存并启用」按钮调它（需要 context，由 MainActivity 提供） */
    var _context: android.content.Context? = null
    fun saveTokenNow() {
        _context?.let { ctx ->
            saveToken(ctx)
            GitCodeAiClient.setToken(_state.value.aiToken)
            refreshAiProviderStatus()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val namesD = async {
                requestResult { SkappApi.fetchStockNames() }.getOrDefault(emptyMap()).also { names ->
                    stockNames = names
                    search(searchQuery)
                }
            }
            val symbolsD = async {
                requestResult { SkappApi.fetchSymbols() }.getOrDefault(emptyList()).also { symbols ->
                    _state.value = _state.value.copy(symbols = symbols)
                    search(searchQuery)
                }
            }
            val picksD = async { requestResult { SkappApi.fetchTodayPicks() }.getOrDefault(TodayPicks()) }
            val structuredD = async { requestResult { SkappApi.fetchStructuredPicks() }.getOrDefault(emptyMap()) }
            val closeReviewD = async { requestResult { SkappApi.fetchCloseReview() }.getOrDefault(com.skapp.app.data.CloseReview()) }
            val winrateD = async { requestResult { SkappApi.fetchWinrateMatrix() }.getOrDefault(com.skapp.app.data.WinrateMatrix()) }
            val decisionD = async { requestResult { SkappApi.fetchDecision() }.getOrDefault(Decision()) }
            val marketD = async { requestResult { SkappApi.fetchMarket() }.getOrDefault(MarketSnapshot()) }
            val newsD = async { requestResult { SkappApi.fetchNews() }.getOrDefault(NewsSnapshot()) }
            val sectorsD = async { requestResult { SkappApi.fetchSectors() }.getOrDefault(SectorsSnapshot()) }
            val poolsD = async { requestResult { SiteBoardClient.pools() }.getOrDefault(emptyList()) }
            val boardD = async { requestResult { SiteBoardClient.board() }.getOrDefault(emptyMap()) }
            val themeD = async { requestResult { SiteBoardClient.themeReport() }.getOrNull() }

            val symbols = symbolsD.await()
            val names = namesD.await()
            askEngine = AskEngine(symbols, names)
            _state.value = _state.value.copy(
                loading = false,
                picks = picksD.await(),
                premarketPicks = structuredD.await()["premarket"] ?: com.skapp.app.data.StructuredPickArtifact(session = "premarket"),
                middayPicks = structuredD.await()["midday"] ?: com.skapp.app.data.StructuredPickArtifact(session = "midday"),
                closeReview = closeReviewD.await(),
                winrateMatrix = winrateD.await(),
                decision = decisionD.await(),
                market = marketD.await(),
                news = newsD.await(),
                sectors = sectorsD.await(),
                pools = poolsD.await(),
                board = boardD.await(),
                themeReport = themeD.await(),
                symbols = symbols,
            )
            // 首屏预热消息计算与实时总结，失败时保留已有快照并写入明确状态。
            loadNewsCalcs()
            loadSummary()
            loadAllSources()
            loadBanProb()
            refreshQuotes()
            refreshPickQuotes()   // 推股/决策列表补实时报价（覆盖快照价）
            loadIndices()
            loadMarketState()
        }
    }

    /** 云端引擎产物（qwerzxcva/stocks-ai-view），供 EnginesScreen 使用 */
    suspend fun loadEngineFeeds(): EngineFeed.EngineFeeds = EngineFeed.loadAll()

    /** 市场状态计算 */
    fun loadMarketState() {
        viewModelScope.launch {
            _state.value = _state.value.copy(marketStateLoading = true)
            try {
                val ms = MarketStateEngine.compute()
                _state.value = _state.value.copy(marketState = ms, marketStateLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(marketStateLoading = false)
            }
        }
    }

    private val allSources = AllSourcesClient()

    /** 加载 ban_prob 涨停次日表现概率 */
    fun loadBanProb() {
        viewModelScope.launch {
            _state.value = _state.value.copy(banProbLoading = true)
            try {
                val bp = BanProbClient.load()
                _state.value = _state.value.copy(banProb = bp, banProbLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(banProbLoading = false)
            }
        }
    }

    /** 获取平衡后的涨停概率 */
    fun getBalancedZtRate(tier: String): Double? {
        val bp = _state.value.banProb
        return bp?.balanced?.byLianban?.get(tier)?.balZtNextRate
    }

    /** 判断连板层样本是否充足 */
    fun isLiAnbanSufficient(tier: String): Boolean {
        val bp = _state.value.banProb
        return bp?.balanced?.byLianban?.get(tier)?.sufficient ?: false
    }

    /** 拉云端 190 源合并产物：作行情兜底 + 数据源健康度展示 */
    fun loadAllSources() {
        viewModelScope.launch {
            try {
                val j = allSources.fetch() ?: run {
                    _state.value = _state.value.copy(registryStats = mapOf("status" to "unknown"))
                    return@launch
                }
                _state.value = _state.value.copy(
                    allSourcesSummary = allSources.summaryLine(j),
                    allSourcesQuotes = allSources.parseQuotes(j),
                    registryStats = registryStats(j),
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(registryStats = mapOf("status" to "unknown"))
            }
        }
    }

    private fun registryStats(root: JSONObject): Map<String, Any?> {
        val registry = root.optJSONArray("sources_registry")
            ?: root.optJSONArray("sources")
            ?: root.optJSONArray("registry")
            ?: return mapOf("status" to "unknown")
        var tested = 0
        var success = 0
        var failure = 0
        var stale = 0
        var unknown = 0
        for (i in 0 until registry.length()) {
            val item = registry.optJSONObject(i)
            if (item == null) {
                unknown++
                continue
            }
            val status = item.optString("status").lowercase()
            val result = item.optString("result").lowercase()
            tested += if (status.isNotBlank() || result.isNotBlank()) 1 else 0
            when {
                listOf(status, result).any { it in setOf("success", "ok", "healthy") || it.contains("success") || it.contains("healthy") } -> success++
                listOf(status, result).any { it in setOf("failure", "failed", "error") || it.contains("fail") || it.contains("error") } -> failure++
                listOf(status, result).any { it == "stale" || it.contains("stale") } -> stale++
                else -> unknown++
            }
        }
        return mapOf("status" to "ok", "total" to registry.length(), "tested" to tested,
            "success" to success, "failure" to failure, "stale" to stale, "unknown" to unknown)
    }

    /** 行情刷新：只在 A 股交易时段（含竞价）自动刷新，休市时停表 */
    fun startQuoteLoop() {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            while (isActive) {
                val now = java.util.Date()
                val trading = MarketHours.isTrading(now) || MarketHours.isAuction(now)
                val newPhase = MarketHours.describe(now)
                val newRefreshIn = MarketHours.msUntilAutoRefresh(now)
                // 只在真正变化时更新 state，避免无谓重组（休市时每次醒来都触发全屏重绘）
                val cur = _state.value
                if (cur.marketPhase != newPhase || cur.autoRefresh != trading || cur.nextRefreshIn != newRefreshIn) {
                    _state.value = cur.copy(
                        marketPhase = newPhase,
                        autoRefresh = trading,
                        nextRefreshIn = newRefreshIn,
                    )
                }
                if (trading) {
                    refreshQuotes()
                    refreshPickQuotes()
                    delay(5_000)
                } else {
                    // 休市：按距离下一次开市的剩余时间睡，最长 10 分钟醒一次更新状态
                    val wait = MarketHours.msUntilAutoRefresh(now) ?: (10 * 60_000L)
                    delay(minOf(wait, 10 * 60_000L))
                }
            }
        }
    }

    /** 全市场快照异动榜：交易时段约30秒，规则预筛不拉逐股K线。 */
    fun startMarketPulseLoop() {
        pulseJob?.cancel()
        pulseJob = viewModelScope.launch {
            while (isActive) {
                if (!_state.value.marketPulseLoading && (MarketHours.isTrading() || MarketHours.isAuction())) {
                    runCatching { refreshMarketPulse() }
                }
                delay(if (MarketHours.isTrading() || MarketHours.isAuction()) 30_000L else 180_000L)
            }
        }
    }

    private suspend fun refreshMarketPulse() {
        _state.value = _state.value.copy(marketPulseLoading = true)
        try {
            val snapshot = LocalScreener.marketSnapshot(pz = 500)
            val previous = _state.value.marketPulse.associateBy { it.code }
            val rows = snapshot.mapNotNull { raw ->
                val code = raw["code"]?.toString() ?: return@mapNotNull null
                val pct = (raw["pctChg"] as? Number)?.toDouble() ?: return@mapNotNull null
                val name = raw["name"]?.toString().orEmpty()
                if (code.take(3) !in setOf("600", "601", "603", "605", "000", "001", "002") || name.contains("ST", true) || pct >= 9.6) return@mapNotNull null
                val old = previous[code]?.pctChg
                val speed = if (old != null) pct - old else (raw["speed"] as? Number)?.toDouble()
                val mainNet = (raw["mainNet"] as? Number)?.toDouble()
                val volRatio = (raw["volRatio"] as? Number)?.toDouble()
                val outlook = when {
                    speed != null && speed >= 0.8 && (mainNet == null || mainNet > 0) -> "bullish"
                    speed != null && speed <= -0.8 && mainNet != null && mainNet < 0 -> "bearish"
                    speed != null && speed > 0.3 -> "watch_up"
                    speed != null && speed < -0.3 -> "watch_down"
                    else -> "insufficient"
                }
                val reason = when (outlook) {
                    "bullish" -> "涨速加快${"%.2f".format(speed)}个百分点${if (mainNet != null) "，主力净额${"%.2f".format(mainNet)}" else ""}；仅为行情规则推断，不等于确定上涨"
                    "bearish" -> "涨速转弱${"%.2f".format(speed)}个百分点，主力净额为负；仅为行情规则推断，不等于确定下跌"
                    "watch_up" -> "涨速偏强但证据不足，等待板块/消息确认"
                    "watch_down" -> "涨速偏弱但证据不足，等待板块/消息确认"
                    else -> "缺少连续快照或资金证据"
                }
                val event = _state.value.news.items.firstOrNull { item ->
                    val rawCodes = item["stocks"] ?: item["codes"] ?: item["affected_stocks"]
                    when (rawCodes) {
                        is List<*> -> rawCodes.any { value ->
                            val candidate = if (value is Map<*, *>) value["code"] ?: value["symbol"] else value
                            candidate?.toString()?.filter(Char::isDigit)?.takeLast(6) == code
                        }
                        else -> rawCodes?.toString()?.contains(code) == true
                    }
                }
                com.skapp.app.data.MarketPulseRow(
                    code = code,
                    name = name,
                    price = (raw["price"] as? Number)?.toDouble(),
                    pctChg = pct,
                    speed = speed,
                    volRatio = volRatio,
                    turnover = (raw["turnover"] as? Number)?.toDouble(),
                    mainNet = mainNet,
                    eventTitle = event?.get("title")?.toString(),
                    eventDirection = event?.get("direction")?.toString() ?: event?.get("sentiment")?.toString(),
                    eventReason = event?.get("positive_reason")?.toString()
                        ?: event?.get("negative_reason")?.toString()
                        ?: event?.get("reason")?.toString(),
                    evidenceTs = event?.get("time")?.toString() ?: event?.get("ts")?.toString(),
                    outlook = outlook,
                    outlookReason = reason,
                )
            }.sortedWith(compareByDescending<com.skapp.app.data.MarketPulseRow> { it.speed ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.mainNet ?: Double.NEGATIVE_INFINITY })
                .take(30)
            val enriched = rows.mapIndexed { index, row ->
                if (index >= 15) row else {
                    val board = runCatching { DataCache.boards(row.code) }.getOrDefault(emptyList())
                        .mapNotNull { it["name"]?.toString()?.takeIf { name -> name.isNotBlank() } }
                        .distinct().take(3).joinToString("、").ifBlank { "板块数据不足" }
                    row.copy(board = board)
                }
            }
            _state.value = _state.value.copy(
                marketPulse = enriched,
                marketPulseTs = java.time.OffsetDateTime.now().toString(),
            )
        } finally {
            _state.value = _state.value.copy(marketPulseLoading = false)
        }
    }

    /** 本地消息刷新：交易时段约30秒，非交易时段约3分钟；云端快照只作低频补偿。 */
    fun startNewsLoop() {
        newsJob?.cancel()
        newsJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.newsCalcLoading) {
                    delay(5_000)
                    continue
                }
                runCatching { refreshNewsCalcs() }
                val wait = if (MarketHours.isTrading() || MarketHours.isAuction()) 30_000L else 180_000L
                delay(wait)
            }
        }
    }

    /**
     * 给推股/决策列表补实时报价。用户实测：列表里的价格是本地快照，
     * 与实时行情不一致（引力传媒 快照 +4% 实际 +6%）。
     * 这里盘中直接从腾讯批量拉，覆盖快照价；休市时保留快照（快照即收盘价）。
     */
    fun refreshPickQuotes() {
        if (!MarketHours.isTrading() && !MarketHours.isAuction()) return
        viewModelScope.launch {
            val codes = (_state.value.picks.signals.map { it.code } +
                _state.value.picks.values.map { it.code } +
                _state.value.decision.cards.map { it.code })
                .distinct()
            if (codes.isEmpty()) return@launch
            val prefixed = codes.map { SkappApi.prefixed(it) }
            val quotes = SkappApi.fetchQuotes(prefixed)
            val byNorm = HashMap<String, Quote>()
            quotes.forEach { (k, v) -> SkappApi.normalize(k)?.let { n -> byNorm[n] = v } }
            _state.value = _state.value.copy(pickQuotes = byNorm)
        }
    }

    /** 全球 + A 股指数（东财 ulist，一次批量） */
    fun loadIndices() {
        viewModelScope.launch {
            val a = MarketDataClient.aShareIndices()
            val g = MarketDataClient.globalIndices()
            _state.value = _state.value.copy(aShareIndices = a, globalIndices = g)
        }
    }

    suspend fun refreshQuotes() {
        val prefixed = Watchlist.codes.map { SkappApi.prefixed(it) }
        val quotes: Map<String, Quote> = SkappApi.fetchQuotes(prefixed)
        val now = System.currentTimeMillis()
        val rows = Watchlist.codes.map { code ->
            val q = quotes[SkappApi.prefixed(code)]
            val quoteAge = q?.ts?.let { parseAge(it, now) }
            QuoteRow(
                code = code,
                display = SkappApi.prefixed(code),
                name = q?.name?.takeIf { it.isNotBlank() } ?: nameOf(code) ?: code,
                price = q?.price,
                pctChg = q?.pctChg,
                stale = quoteAge == null || quoteAge > 10 * 60 * 1000,
            )
        }
        _state.value = _state.value.copy(
            quotes = rows,
            quoteTs = quotes.values.firstOrNull()?.ts,
        )
    }

    /** 名称回退：先 names，再 symbols */
    private fun nameOf(code: String): String? {
        val n = SkappApi.normalize(code) ?: return null
        return _state.value.symbols.firstOrNull {
            SkappApi.normalize(it.code) == n
        }?.name
    }

    /** 搜索：代码或名称模糊匹配 */
    fun search(q: String) {
        val query = q.trim().lowercase()
        searchQuery = q
        if (query.isEmpty()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        val res = (_state.value.symbols + stockNames.map { (code, name) -> SymbolEntry(code, name) })
            .asSequence()
            .mapNotNull { entry ->
                val code = SkappApi.normalize(entry.code) ?: return@mapNotNull null
                SymbolEntry(code, entry.name?.takeIf { it.isNotBlank() } ?: stockNames[code])
            }
            .distinctBy { it.code }
            .filter { s ->
                s.code.lowercase().contains(query) ||
                    (s.name?.lowercase()?.contains(query) == true)
            }
            .take(50)
            .toList()
        val fallback = if (res.isEmpty()) SkappApi.directSymbol(query)?.let {
            listOf(it.copy(name = stockNames[it.code]))
        } ?: emptyList() else emptyList()
        _state.value = _state.value.copy(searchResults = res.ifEmpty { fallback })
    }

    /**
     * 打开个股详情：stocks-holo 全量 + 实时行情 + 中枢引擎（引擎吃 holo/kline/technical）。
     * 缓存优先：重复点同一只股票且复权/周期未变则不重新拉。
     */
    /** 切换复权方式：已选中则忽略 */
    fun switchAdjust(adjust: String) {
        if (_state.value.detailAdjust == adjust) return
        val code = _state.value.detail?.code ?: return
        openDetail(code, adjust)
    }

    fun openDetail(code: String, adjust: String = "qfq") {
        val normalized = SkappApi.normalize(code) ?: code
        val same = _state.value.detail?.code == normalized &&
            _state.value.detailAdjust == adjust &&
            !_state.value.detailLoading && _state.value.holo != null
        if (same) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val code = normalized
            _state.value = _state.value.copy(
                detailLoading = true,
                detail = StockDetail(code = code, name = stockNames[code]),
                detailAdjust = adjust,
                kline = emptyList(),
                kPeriod = KPeriod.DAY,
                holo = null,
                localEngine = null,
                centralEngineSummary = null,
                centralEngineScore = null,
                centralEngineResonance = null,
                centralEngineStatus = "insufficient",
                liveQuote = null,
                fundFlow = emptyList(),
                boardsOf = emptyList(),
                minuteTrend = emptyList(),
            )
            try {
                supervisorScope {
                    val holoD = async { requestResult { HoloClient.full(code) }.getOrNull() }
                    val quoteD = async {
                        val q = requestResult { DataCache.quote(code) }.getOrNull()
                        _state.value = _state.value.copy(
                            detailLoading = false,
                            liveQuote = q,
                            detail = _state.value.detail?.copy(
                                name = q?.name?.takeIf { it.isNotBlank() } ?: _state.value.detail?.name,
                            ),
                        )
                    }
                    val cloudD = async {
                        val cloud = requestResult { SkappApi.fetchStockDetail(code) }.getOrNull()
                        if (cloud != null) {
                            val current = _state.value.detail
                            _state.value = _state.value.copy(
                                detailLoading = false,
                                detail = cloud.copy(
                                    name = _state.value.liveQuote?.name?.takeIf { it.isNotBlank() }
                                        ?: cloud.name ?: current?.name,
                                    detail = cloud.detail ?: current?.detail,
                                ),
                            )
                        }
                    }
                    val boardsD = async {
                        val boards = requestResult { DataCache.boards(code) }.getOrDefault(emptyList())
                        _state.value = _state.value.copy(boardsOf = boards)
                    }
                    val minuteD = async {
                        if (MarketHours.isTrading() || MarketHours.isAuction() ||
                            MarketHours.phase() == MarketHours.Phase.AFTER_HOURS) {
                            val minute = requestResult { DataCache.minute(code) }.getOrDefault(emptyList())
                            _state.value = _state.value.copy(minuteTrend = minute)
                        }
                    }
                    val historyD = async {
                        val holo = holoD.await()
                        _state.value = _state.value.copy(holo = holo?.takeIf { it.covered })
                        val bars = holo?.kline?.takeIf { it.size >= 30 }
                            ?: requestResult { DataCache.kline(code, adjust, 250) }.getOrDefault(emptyList())
                        _state.value = _state.value.copy(kline = bars)
                        val engine = requestResult {
                            withContext(Dispatchers.Default) { DataCache.engine(code, adjust, bars) }
                        }.getOrNull()
                        val central = engine?.let { centralEngine(it) }
                            ?: CentralEngine("中枢引擎：数据不足，无法形成有效总结。", null, null, "insufficient")
                        _state.value = _state.value.copy(
                            localEngine = engine,
                            centralEngineSummary = central.summary,
                            centralEngineScore = central.score,
                            centralEngineResonance = central.resonance,
                            centralEngineStatus = central.status,
                        )
                        val local = requestResult {
                            bundleFromLocal(code, bars, _state.value.liveQuote, holo)
                        }.getOrNull()
                        val current = _state.value.detail
                        _state.value = _state.value.copy(detail = current?.copy(
                            name = current.name ?: holo?.holo?.get("name")?.toString()?.takeIf { it.isNotBlank() },
                            detail = current.detail ?: local,
                        ))
                    }
                    val flowD = async {
                        val holo = holoD.await()
                        val flow = holo?.moneyflow?.takeIf { it.isNotEmpty() }
                            ?: requestResult { DataCache.fundFlow(code, 60) }.getOrDefault(emptyList())
                        val live = if (MarketHours.isTrading() || MarketHours.isAuction()) {
                            requestResult { MarketDataClient.fundFlowIntraday(code) }.getOrDefault(emptyList())
                        } else emptyList()
                        _state.value = _state.value.copy(fundFlow = flow, fundFlowLive = live)
                    }
                    quoteD.await()
                    cloudD.await()
                    boardsD.await()
                    minuteD.await()
                    historyD.await()
                    flowD.await()
                    if (MarketHours.isTrading() || MarketHours.isAuction()) {
                        runCatching { refreshDetailLiveNow(code) }
                    }
                }
                if (MarketHours.isTrading() || MarketHours.isAuction()) startDetailLiveLoop(code)
            } finally {
                if (isActive) _state.value = _state.value.copy(detailLoading = false)
            }
        }
    }

    /** 盘中详情实时刷新：约15秒一次，把当日分时/实时价合成最后一根K并重算中枢引擎。 */
    private fun startDetailLiveLoop(code: String) {
        detailLiveJob?.cancel()
        detailLiveJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (!MarketHours.isTrading() && !MarketHours.isAuction()) break
                if (_state.value.detail?.code != code) break
                runCatching { refreshDetailLiveNow(code) }
            }
        }
    }

    fun refreshDetailLive() {
        val code = _state.value.detail?.code ?: return
        viewModelScope.launch { runCatching { refreshDetailLiveNow(code) } }
    }

    private suspend fun refreshDetailLiveNow(code: String) {
        val quote = runCatching { MarketDataClient.quote(code) }.getOrNull() ?: return
        val minute = runCatching { MarketDataClient.minuteTrend(code) }.getOrDefault(emptyList())
        // 东财/腾讯日K本身包含今日未收盘实时K；拉不到时才用分时+实时价合成。
        val fetched = runCatching {
            MarketDataClient.dayKline(code, 250, _state.value.detailAdjust)
        }.getOrDefault(emptyList())
        val base = fetched.ifEmpty { _state.value.kline }
        val bars = mergeLiveBar(base, quote, minute)
        val engine = if (bars.size >= 30) runCatching { LocalEngine.analyze(bars) }.getOrNull() else null
        val central = engine?.let { centralEngine(it) }
        val liveFlow = if (MarketHours.isTrading() || MarketHours.isAuction()) {
            runCatching { MarketDataClient.fundFlowIntraday(code) }.getOrDefault(emptyList())
        } else emptyList()
        _state.value = _state.value.copy(
            liveQuote = quote,
            kline = bars,
            minuteTrend = minute.ifEmpty { _state.value.minuteTrend },
            fundFlowLive = liveFlow.ifEmpty { _state.value.fundFlowLive },
            localEngine = engine ?: _state.value.localEngine,
            centralEngineScore = central?.score ?: _state.value.centralEngineScore,
            centralEngineResonance = central?.resonance ?: _state.value.centralEngineResonance,
            centralEngineSummary = central?.summary ?: _state.value.centralEngineSummary,
            centralEngineStatus = central?.status ?: _state.value.centralEngineStatus,
        )
    }

    /** 用当日分时与实时价补齐/替换今天这根日K，避免日K停在历史收盘。 */
    private fun mergeLiveBar(bars: List<KBar>, quote: Quote, minute: List<Map<String, Any?>>): List<KBar> {
        val price = quote.price ?: return bars
        val today = java.time.LocalDate.now().toString()
        val prices = minute.mapNotNull { (it["price"] as? Number)?.toDouble() }
        val volSum = minute.sumOf { (it["vol"] as? Number)?.toDouble() ?: 0.0 }
        val lastClose = bars.lastOrNull { it.date != today }?.close
        val open = prices.firstOrNull() ?: lastClose ?: price
        val high = listOfNotNull(prices.maxOrNull(), price, open).max()
        val low = listOfNotNull(prices.minOrNull(), price, open).min()
        val bar = KBar(
            date = today,
            open = open,
            close = price,
            high = maxOf(high, price),
            low = minOf(low, price),
            vol = if (volSum > 0) volSum else bars.lastOrNull()?.vol ?: 0.0,
        )
        return (bars.filter { it.date != today } + bar).sortedBy { it.date }
    }

    /** 切换 K 线周期（日/周/月由日 K 本地聚合，不重新请求） */
    fun switchPeriod(p: KPeriod) {
        if (_state.value.kPeriod == p) return
        val daily = _state.value.kline
        _state.value = _state.value.copy(kPeriod = p, kline = aggregateKline(daily, p))
    }

    private data class CentralEngine(
        val summary: String,
        val score: Double?,
        val resonance: Double?,
        val status: String,
    )

    private fun centralEngine(engine: LocalEngineResult): CentralEngine {
        val valid = engine.engines.filter { it.score != null }
        val score = engine.centralScore ?: engine.weightedComposite ?: engine.composite
        if (valid.isEmpty() || score == null) {
            return CentralEngine(engine.summary ?: "中枢引擎：数据不足，无法形成有效总结。", null, null, "insufficient")
        }
        val resonance = engine.resonanceRatio
        val summary = engine.summary ?: "中枢引擎：综合 ${"%.1f".format(score)}，共振 ${"%.0f%%".format((resonance ?: 0.0) * 100)}，有效 ${valid.size}/${engine.engines.size}。"
        return CentralEngine(summary, score, resonance, engine.status)
    }

    /** 快讯 + 主线：逐条标题下实时算（标题命中的股票 → 实时行情 + 中枢引擎） */
    fun loadNewsCalcs(): Job = viewModelScope.launch {
        requestResult { refreshNewsCalcs() }
    }

    private suspend fun refreshNewsCalcs() {
        _state.value = _state.value.copy(newsCalcLoading = true)
        try {
            val symbols = _state.value.symbols
            val items = ArrayList<Map<String, Any?>>()
            // 本地多源：东财、爱股票、金融界，各源失败隔离并保留健康状态。
            val local = requestResult { MarketDataClient.localNews(30) }.getOrDefault(LocalNewsResult())
            items.addAll(local.items)
            // 云端新闻数据库：分析字段和跨源映射只作补充，不覆盖本地新消息。
            val stored = requestResult { SkappApi.fetchNewsImpact() }.getOrDefault(emptyList())
            items.addAll(stored)
            // state.news.items 是消息页统一数据源；保留已加载的云端快照作为最后补充。
            items.addAll(_state.value.news.items)
            if (items.isEmpty()) {
                val liveFallback = requestResult { MarketDataClient.news724(50) }.getOrDefault(emptyList())
                items.addAll(liveFallback)
            }
        val unique = items.map { item ->
                val title = (item["title"] ?: item["newsTitle"] ?: item["digest"] ?: item["content"])?.toString()?.trim().orEmpty()
                val time = (item["time"] ?: item["updated"] ?: item["ctime"] ?: item["ts"])?.toString()
                item + mapOf(
                    "title" to title,
                    "source" to (item["source"] ?: item["source_name"] ?: ""),
                    "time" to time,
                    "verdict" to (item["verdict"] ?: "insufficient"),
                    "reason" to (item["reason"] ?: item["analysis"] ?: "insufficient"),
                    "stocks" to (item["stocks"] ?: item["codes"] ?: emptyList<Any>()),
                    "id" to (item["id"] ?: item["news_id"] ?: ""),
                )
            }.filter { (it["title"] as? String)?.isNotBlank() == true }
                .distinctBy { item ->
                    val id = item["id"]?.toString().orEmpty()
                    if (id.isNotBlank()) id else "${item["title"]}|${item["time"] ?: ""}"
                }
            // 主线：hot_themes.themes 的 name + 关联个股
            val themes = _state.value.news.themes.mapNotNull { t ->
                val name = (t["name"] ?: t["theme"])?.toString() ?: return@mapNotNull null
                val st = (t["stocks"] as? List<*>)?.mapNotNull { s ->
                    (s as? Map<*, *>)?.get("name")?.toString()
                }?.joinToString(" ") ?: ""
                mapOf<String, Any?>(
                    "title" to "【主线】$name ${if (st.isNotEmpty()) "关联：$st" else ""}",
                    "source" to "hot_themes",
                    "updated" to (t["ts"]?.toString()),
                )
            }
            items.addAll(themes)
            val merged = (unique + themes).map { item ->
                val title = item["title"]?.toString().orEmpty()
                item + mapOf(
                    "id" to (item["id"] ?: ""),
                    "title" to title,
                    "source" to (item["source"] ?: item["source_name"] ?: ""),
                    "time" to (item["time"] ?: item["updated"] ?: item["ctime"] ?: item["ts"]),
                    "verdict" to (item["verdict"] ?: "insufficient"),
                    "reason" to (item["reason"] ?: item["analysis"] ?: "insufficient"),
                    "stocks" to (item["stocks"] ?: item["codes"] ?: emptyList<Any>()),
                )
            }.distinctBy { item ->
                val id = item["id"]?.toString().orEmpty()
                if (id.isNotBlank()) id else "${item["title"]}|${item["time"] ?: ""}"
            }.take(50)
            if (merged.isEmpty()) throw IllegalStateException("暂无消息数据")
            val previous = _state.value.news
            val sourceTs = merged.mapNotNull { it["time"]?.toString()?.takeIf { ts -> ts.isNotBlank() } }.maxOrNull()
            _state.value = _state.value.copy(
                news = previous.copy(
                    items = merged,
                    n = merged.size,
                    sourceTs = sourceTs ?: previous.sourceTs,
                    fetchedAt = local.fetchedAt ?: previous.fetchedAt,
                    sourceStatus = local.sourceStatus,
                    sourceErrors = local.errors,
                    dedupCount = (items.size - merged.size).coerceAtLeast(0),
                ),
            )
            val calcs = NewsCalcEngine.compute(merged, symbols).map { calc ->
                val source = merged.firstOrNull { it["title"] == calc.newsTitle }
                calc.copy(
                    source = calc.source ?: source?.get("source")?.toString()?.takeIf { it.isNotBlank() },
                    ts = calc.ts ?: source?.get("time")?.toString(),
                    verdict = source?.get("verdict")?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" } ?: calc.verdict,
                    direction = source?.get("direction")?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" } ?: calc.direction,
                    positiveReason = source?.get("positive_reason")?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" } ?: calc.positiveReason,
                    negativeReason = source?.get("negative_reason")?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" } ?: calc.negativeReason,
                    imageUrl = source?.get("image")?.toString()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: calc.imageUrl,
                    note = source?.get("reason")?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" } ?: calc.note,
                )
            }
            _state.value = _state.value.copy(newsCalcs = calcs)
        } finally {
            _state.value = _state.value.copy(newsCalcLoading = false)
        }
    }

    /** 多池选股：云端池 + 实时补价 + 本地引擎实时池。 */
    fun loadPools() {
        viewModelScope.launch {
            _state.value = _state.value.copy(poolsLoading = true)
            val ps = runCatching { SiteBoardClient.pools() }.getOrElse { emptyList() }
            val enriched = enrichPoolsWithLiveQuotes(ps)
            _state.value = _state.value.copy(poolsLoading = false, pools = localPool() + enriched)
        }
    }

    /** 用实时报价覆盖云端池里的旧价，并标出补价时间；非交易时段保留快照价。 */
    private suspend fun enrichPoolsWithLiveQuotes(pools: List<SiteBoardClient.Pool>): List<SiteBoardClient.Pool> {
        if (!MarketHours.isTrading() && !MarketHours.isAuction()) return pools
        val codes = pools.flatMap { pool -> pool.items.mapNotNull { SkappApi.normalize(it.code) } }
            .distinct()
            .take(300)
        if (codes.isEmpty()) return pools
        val quotes = runCatching {
            SkappApi.fetchQuotes(codes.map { SkappApi.prefixed(it) })
        }.getOrDefault(emptyMap())
        if (quotes.isEmpty()) return pools
        val byCode = quotes.entries.mapNotNull { (key, value) ->
            SkappApi.normalize(key)?.let { it to value }
        }.toMap()
        val now = java.time.OffsetDateTime.now().toString()
        return pools.map { pool ->
            pool.copy(items = pool.items.map { item ->
                val code = SkappApi.normalize(item.code) ?: return@map item
                val quote = byCode[code] ?: return@map item
                item.copy(
                    price = quote.price ?: item.price,
                    pctChg = quote.pctChg ?: item.pctChg,
                    extra = item.extra + mapOf(
                        "live_price_ts" to (quote.ts ?: now),
                        "live_source" to "tencent_quote",
                    ),
                )
            })
        }
    }

    /** 本地实时池：直接来自本轮本地深算，价格/K线/资金/消息均为本地实时计算。 */
    private fun localPool(): List<SiteBoardClient.Pool> {
        val screened = _state.value.screened
        val ts = _state.value.quoteTs ?: java.time.OffsetDateTime.now().toString()
        val items = screened.take(20).map { row ->
            SiteBoardClient.PoolItem(
                code = row.code,
                name = row.name,
                score = row.centralScore,
                price = row.price,
                pctChg = row.pctChg,
                extra = mapOf(
                    "engine" to "local_screener",
                    "resonance" to row.resonanceRatio,
                    "reason" to (row.centralSummary ?: row.reason),
                    "calculation_time" to ts,
                ),
            )
        }
        return listOf(
            SiteBoardClient.Pool(
                id = "local_live",
                name = "本地实时",
                subtitle = "本地 K线+资金+消息实时深算",
                items = items,
                ts = ts,
                status = if (items.isEmpty()) "insufficient" else "ok",
                reason = if (items.isEmpty()) "本轮本地深算尚无候选，可在实时总结刷新" else null,
            )
        )
    }

    /** 看板 */
    fun loadBoard() {
        viewModelScope.launch {
            val b = runCatching { SiteBoardClient.board() }.getOrElse {
                mapOf<String, Any?>("status" to "insufficient", "reason" to it.message)
            }
            _state.value = _state.value.copy(board = b)
        }
    }

    /** 推股页数据补齐：多池 + 引擎产出（load() 已覆盖 today_picks/decision/news/sectors/board） */
    fun loadPicks() {
        viewModelScope.launch {
            val poolsD = async {
                val raw = runCatching { SiteBoardClient.pools() }.getOrElse { emptyList() }
                localPool() + enrichPoolsWithLiveQuotes(raw)
            }
            val feedsD = async { runCatching { loadEngineFeeds() }.getOrNull() }
            _state.value = _state.value.copy(
                pools = poolsD.await(),
                engineFeeds = feedsD.await(),
            )
        }
    }

    /** 手动刷新实时总结：正文、消息异动和本地推股统一由一个入口触发。 */
    fun refreshRealtimeSummary(mode: String = "score") {
        if (_state.value.summaryRefreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(summaryRefreshing = true, summaryRefreshMessage = "正在刷新盘面、消息和实时推股…")
            var message = "刷新已取消"
            try {
                val refreshed = supervisorScope {
                    val summaryD = async { requestResult { refreshSummary() } }
                    val newsD = async { requestResult { refreshNewsCalcs() } }
                    val screenedD = async {
                        requestResult {
                            if (_state.value.screening) throw IllegalStateException("已有实时选股任务正在运行")
                            refreshScreen(mode)
                        }
                    }
                    val structuredD = async { requestResult { SkappApi.fetchStructuredPicks() } }
                    val closeD = async { requestResult { SkappApi.fetchCloseReview() } }
                    val winrateD = async { requestResult { SkappApi.fetchWinrateMatrix() } }
                    listOf(
                        "盘面" to summaryD.await(),
                        "消息" to newsD.await(),
                        "实时推股" to screenedD.await(),
                        "结构化推股" to structuredD.await(),
                        "盘后总结" to closeD.await(),
                        "胜率" to winrateD.await(),
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val structuredMap = refreshed[3].second.getOrNull() as? Map<String, com.skapp.app.data.StructuredPickArtifact>
                val close = refreshed[4].second.getOrNull() as? com.skapp.app.data.CloseReview
                val winrate = refreshed[5].second.getOrNull() as? com.skapp.app.data.WinrateMatrix
                _state.value = _state.value.copy(
                    premarketPicks = structuredMap?.get("premarket") ?: _state.value.premarketPicks,
                    middayPicks = structuredMap?.get("midday") ?: _state.value.middayPicks,
                    closeReview = close ?: _state.value.closeReview,
                    winrateMatrix = winrate ?: _state.value.winrateMatrix,
                )
                val failures = refreshed.filter { it.second.isFailure }
                message = if (failures.isEmpty()) "刷新完成" else {
                    val details = failures.joinToString("；") { (label, result) ->
                        "$label：${result.exceptionOrNull()?.message ?: "数据源不可达"}"
                    }
                    val prefix = if (failures.size == refreshed.size) "刷新失败" else "部分刷新完成"
                    "$prefix；$details"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "刷新失败：${e.message ?: "数据源不可达"}"
            } finally {
                _state.value = _state.value.copy(summaryRefreshing = false, summaryRefreshMessage = message)
            }
        }
    }

    /** 实时总结正文：加载失败时保留明确的 insufficient 状态。 */
    fun loadSummary(): Job = viewModelScope.launch {
        requestResult { refreshSummary() }
    }

    private suspend fun refreshSummary() {
        val cloud = requestResult { SiteBoardClient.summary() }.getOrNull()
        val cloudOk = cloud != null && cloud["status"]?.toString() !in setOf("insufficient", "unknown", "error")
        if (cloudOk) {
            _state.value = _state.value.copy(summary = cloud!!)
            return
        }
        // 云端产物不可达/过期时，用本地实时行情、消息与本地深算生成总结，不伪造数字。
        val local = buildLocalSummary()
        _state.value = _state.value.copy(summary = local)
        if (local["status"]?.toString() != "ok") {
            throw IllegalStateException(
                local["reason"]?.toString()
                    ?: cloud?.get("reason")?.toString()
                    ?: "暂无盘面数据"
            )
        }
    }

    /** 本地实时总结：只用当前已有真实数据；任何指标缺失就直说不编造。 */
    private fun buildLocalSummary(): Map<String, Any?> {
        val s = _state.value
        val pulse = s.marketPulse
        val up = pulse.count { (it.pctChg ?: 0.0) > 0 }
        val down = pulse.count { (it.pctChg ?: 0.0) < 0 }
        val newsPos = s.news.items.count { it["direction"]?.toString() == "pos" }
        val newsNeg = s.news.items.count { it["direction"]?.toString() == "neg" }
        val leaders = pulse.take(5).joinToString("、") { row ->
            val pct = row.pctChg?.let { "%+.2f%%".format(it) } ?: "暂无"
            "${row.name}$pct(${outlookLabel(row.outlook)})"
        }
        val picks = s.screened.take(5).joinToString("、") { row ->
            val score = row.centralScore?.let { "%.1f".format(it) } ?: "暂无分"
            "${row.name ?: row.code}$score"
        }
        val lines = buildList {
            if (pulse.isNotEmpty()) {
                add("实时异动：涨 $up / 跌 $down（前 ${pulse.size} 只异动候选）")
                if (leaders.isNotBlank()) add("异动前列：$leaders")
            }
            if (s.screened.isNotEmpty() && picks.isNotBlank()) add("本地深算推股：$picks")
            if (s.news.items.isNotEmpty()) add("消息面：偏多 $newsPos 条 / 偏空 $newsNeg 条（共 ${s.news.items.size} 条）")
            s.quoteTs?.let { add("行情时点：$it") }
        }
        if (lines.isEmpty()) {
            return mapOf(
                "status" to "insufficient",
                "reason" to "本地实时数据尚未就绪（行情、消息、深算均无结果）",
                "source" to "local_realtime",
            )
        }
        return mapOf(
            "status" to "ok",
            "source" to "local_realtime",
            "ts" to (s.marketPulseTs ?: s.quoteTs ?: java.time.OffsetDateTime.now().toString()),
            "date" to java.time.LocalDate.now().toString(),
            "text" to lines.joinToString("\n"),
        )
    }

    private fun outlookLabel(value: String): String = when (value) {
        "bullish" -> "偏多"
        "bearish" -> "偏空"
        "watch_up" -> "观察偏强"
        "watch_down" -> "观察偏弱"
        else -> "数据不足"
    }

    // ── GitCode AI ─────────────────────────────────────

    fun setAiToken(t: String) {
        GitCodeAiClient.setToken(t)
        _state.value = _state.value.copy(
            aiToken = t,
            aiConfigured = t.isNotBlank(),
        )
        refreshAiProviderStatus()
    }

    /** 设置页 GitHub token 输入框 onValueChange */
    fun setGithubToken(t: String) {
        GitHubModelsClient.setToken(t)
        _state.value = _state.value.copy(
            githubToken = t,
            githubConfigured = t.isNotBlank(),
        )
        refreshAiProviderStatus()
    }

    /** AI 复盘当前详情页的股票：先生成中枢引擎总结，有凭据才请求云端二次总结。 */
    fun aiReviewCurrent() {
        val d = _state.value.detail ?: return
        if (_state.value.aiReviewLoading) return
        viewModelScope.launch {
            val central = _state.value.centralEngineSummary
                ?: "中枢引擎：数据不足，无法形成有效总结。"
            _state.value = _state.value.copy(aiReviewLoading = true, aiReview = central)
            if (AiRouter.availableProviders().isEmpty()) {
                _state.value = _state.value.copy(
                    aiReviewLoading = false,
                    aiReview = central,
                    aiLastProvider = null,
                    aiProviderStatus = AiRouter.statusLine(),
                )
                return@launch
            }
            val data = buildStockReviewData(d)
            val prompt = """你是 A 股复盘分析师。以下是该股 ${d.code} ${d.name} 的**真实实时数据**，你只允许基于这些数据回答，禁止编造任何数字或事实。数据里没有的就说「数据不足」。

中枢引擎原始总结：
$central

$data

请输出 3 部分（每部分 1-2 句，总 300 字内）：
1. 【当前状态】技术面+资金面+消息面综合评价
2. 【风险】主要风险点
3. 【观察点】后续该盯什么
直接输出文本，不要 markdown 代码块。"""
            val r = try {
                AiRouter.askDeep(listOf("user" to prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val out = r?.takeIf { !it.failed && !it.text.isNullOrBlank() }?.let {
                "[via ${it.provider ?: "?"}${it.model?.let { model -> " · $model" } ?: ""}]\n\n${it.text}"
            }
            _state.value = _state.value.copy(
                aiReviewLoading = false,
                aiReview = out ?: central,
                aiLastProvider = r?.provider,
                aiProviderStatus = AiRouter.statusLine(),
            )
        }
    }

    /** 拼复盘用的真实数据底稿（原 GitCodeAiClient.reviewStock 的数据段，现归 ViewModel） */
    private fun buildStockReviewData(d: StockDetail): String {
        return buildString {
            append("【基础】代码=${d.code} 名称=${d.name}")
            _state.value.liveQuote?.let { q ->
                append(" 现价=${q.price ?: "暂无"} 涨跌=${q.pctChg?.let { "%.2f%%".format(it) } ?: "暂无"}")
            }
            appendLine()
            _state.value.localEngine?.let { e ->
                appendLine("【中枢引擎】综合=${(e.centralScore ?: e.weightedComposite ?: e.composite)?.let { "%.1f".format(it) } ?: "insufficient"} 共振=${e.resonanceRatio?.let { "%.0f%%".format(it * 100) } ?: "insufficient"}（${e.nValid}/${e.engines.size} 有效）")
                e.engines.forEach { en ->
                    if (en.score != null) {
                        appendLine("  · ${en.engine}=${"%.1f".format(en.score)} ${en.signal}（${en.reasons.take(2).joinToString("，")}）")
                    }
                }
            }
            val factors = ExtendedIndicators.allFactors(_state.value.kline)
            val validFactors = factors.entries.filter { it.value != null }.take(15)
            if (validFactors.isNotEmpty()) {
                appendLine("【因子】" + validFactors.joinToString(" ") { "${it.key}=${"%.4f".format(it.value!!)}" })
            }
            _state.value.holo?.technical?.entries?.filter { it.value != null }?.take(10)?.let { t ->
                appendLine("【holo 技术】" + t.joinToString(" ") { "${it.key}=${"%.2f".format(it.value!!)}" })
            }
            _state.value.holo?.finance?.entries?.take(8)?.let { f ->
                appendLine("【财务】" + f.joinToString(" ") { "${it.key}=${it.value ?: "暂无"}" })
            }
            val news = _state.value.holo?.news ?: emptyList()
            if (news.isNotEmpty()) {
                appendLine("【该股新闻】")
                news.take(6).forEach { n ->
                    appendLine("  · ${n["title"] ?: "无标题"}")
                }
            }
            val boards = _state.value.boardsOf
            if (boards.isNotEmpty()) {
                appendLine("【板块】" + boards.mapNotNull { it["name"]?.toString() }.joinToString("、"))
            }
        }
    }

    /** 自由提问（把当前上下文全部给 AI） */
    fun aiAsk() {
        val q = _state.value.aiQuestion.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            if (AiRouter.availableProviders().isEmpty()) {
                _state.value = _state.value.copy(
                    aiAnswerLoading = false,
                    aiAnswer = _state.value.centralEngineSummary
                        ?: "中枢引擎：暂无可用于回答的数据。",
                    aiLastProvider = null,
                    aiProviderStatus = AiRouter.statusLine(),
                )
                return@launch
            }
            _state.value = _state.value.copy(aiAnswerLoading = true, aiAnswer = null)
            val ctx = buildAiContext()
            val prompt = """你是 A 股数据分析助手。用户提问：$q

以下是你可调用的**实时数据底稿**（app 刚从数据源拉到的真实值）：

$ctx

规则：
- 只允许基于上面这些数据回答；数据里没有的就明说「未拉取到相关数据」
- 结论要有依据，引用具体数字（如「量比 2.3」「中枢引擎 71.2」）
- 300 字内，直接回答，不要寒暄、不要免责声明"""
            val r = try {
                AiRouter.askDeep(listOf("user" to prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val out = r?.takeIf { !it.failed && !it.text.isNullOrBlank() }?.let {
                "[via ${it.provider ?: "?"}${it.model?.let { model -> " · $model" } ?: ""}]\n\n${it.text}"
            }
            _state.value = _state.value.copy(
                aiAnswerLoading = false,
                aiAnswer = out ?: _state.value.centralEngineSummary ?: "中枢引擎：暂无可用于回答的数据。",
                aiLastProvider = r?.provider,
                aiProviderStatus = AiRouter.statusLine(),
            )
        }
    }

    fun onAiQuestionChange(q: String) {
        _state.value = _state.value.copy(aiQuestion = q)
    }

    /** 把当前 app 已拉到的全部数据序列化成 AI 底稿 */
    private fun buildAiContext(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("【市场时段】${s.marketPhase}")
        s.centralEngineSummary?.let { sb.appendLine("【中枢引擎原始总结】$it") }
        sb.appendLine("【行情】${s.quotes.joinToString("；") { "${it.name} ${it.price} ${it.pctChg?.let { "%.2f%%".format(it) } ?: ""}" }}")
        val p = s.picks
        sb.appendLine("【推股】signals=${p.signals.size} values=${p.values.size} 涨停=${p.limitUp.size}")
        p.signals.take(8).forEach { sb.appendLine("  · ${it.name ?: it.code} 分=${it.score} ${it.engine ?: ""} 价=${it.price}") }
        val d = s.decision
        sb.appendLine("【决策】${d.marketRegime} ${d.regimeDetail ?: ""} 仓位=${d.positionAdvice}")
        val m = s.market
        sb.appendLine("【看板】${m.market.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(200)}")
        val n = s.news
        sb.appendLine("【消息情绪】${n.digest["global_mood"]} 评分=${n.digest["global_score"]} 共 ${n.digest["total"]} 条")
        val sec = s.sectors
        sb.appendLine("【板块】行业 ${sec.industries.size} 概念 ${sec.concepts.size}")
        val ps = s.pools
        sb.appendLine("【多池】" + ps.joinToString("；") { "${it.name}${it.items.size}只" })
        // 若当前打开了某只股，带上它的完整本地分析
        s.detail?.let { dd ->
            sb.appendLine("【当前详情】${dd.name ?: dd.code}（${dd.code}）")
            s.localEngine?.let { e ->
                sb.appendLine("  中枢引擎综合=${e.composite?.let { "%.1f".format(it) } ?: "insufficient"}（${e.nValid}/${e.engines.size}）")
                e.engines.filter { it.score != null }.forEach {
                    sb.appendLine("    · ${it.engine}=${"%.1f".format(it.score!!)} ${it.signal}")
                }
            }
        }
        return sb.toString()
    }

    /** 启动预热：并发拉取全部页面数据，消息与实时总结在进入页面前就可用。 */
    fun warmUpAll() {
        viewModelScope.launch {
            supervisorScope {
                launch { runCatching { loadBoard() } }
                launch { runCatching { loadPicks() } }
                launch { runCatching { refreshQuotes() } }
                launch { runCatching { loadIndices() } }
                launch { runCatching { refreshNewsCalcs() } }
                launch { runCatching { refreshSummary() } }
            }
        }
    }

    /** 实时选股（设备直连行情 + 中枢引擎，不用云端快照）。同模式结果缓存，切 tab 不重扫。 */
    fun runScreen(mode: String = "score", force: Boolean = false): Job? {
        if (_state.value.screening) return null
        if (_state.value.scanMode == mode && _state.value.screened.isNotEmpty() && !force) return null
        return viewModelScope.launch {
            requestResult { refreshScreen(mode) }
        }
    }

    private suspend fun refreshScreen(mode: String) {
        _state.value = _state.value.copy(screening = true, screenMsg = "正在实时拉取全市场行情…")
        try {
            val res = LocalScreener.screen(onProgress = { msg ->
                _state.value = _state.value.copy(screenMsg = msg)
            }, mode = mode)
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(
                screened = res,
                scanMode = mode,
                screenMsg = if (res.isEmpty()) "预筛后无满足条件的股票" else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(screenMsg = "实时选股失败：${e.message ?: "行情接口不可达"}")
            throw e
        } finally {
            _state.value = _state.value.copy(screening = false)
        }
    }

    /** 用本地 K 线造一个 bundle 形状，供 UI 复用同一套渲染 */
    private suspend fun bundleFromLocal(code: String, bars: List<KBar>, q: Quote?, holo: HoloBundle? = null): BundleDetail? {
        if (bars.isEmpty() && holo?.covered != true) return null
        val f = TechnicalCalc.compute(bars)
        return BundleDetail(
            status = "ok",
            ts = bars.lastOrNull()?.date,
            quote = buildMap {
                val hPrice = (holo?.holo?.get("price") as? Number)?.toDouble()
                val live = q?.price ?: hPrice ?: bars.lastOrNull()?.close
                if (live != null) put("price", live)
                val livePct = q?.pctChg ?: (holo?.holo?.get("pctChg") as? Number)?.toDouble()
                if (livePct != null) put("pctChg", livePct)
            },
            technical = (holo?.technical ?: emptyMap()) + mapOf<String, Double?>(
                "ma5" to f?.ma5, "ma10" to f?.ma10, "ma20" to f?.ma20, "ma60" to f?.ma60,
                "rsi14" to f?.rsi14, "macd" to f?.macdHist, "vol_ratio" to f?.volRatio,
                "k" to f?.k, "d" to f?.d, "j" to f?.j,
                "atr14" to f?.atr14,
                "boll_lower" to f?.bollLower, "boll_mid" to f?.bollMid, "boll_upper" to f?.bollUpper,
            ).filterValues { it != null },
            financialLatest = holo?.finance?.takeIf { it.isNotEmpty() }
                ?: MarketDataClient.financeQuote(code),
        )
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailJob = null
        _state.value = _state.value.copy(detail = null, detailLoading = false, kline = emptyList())
    }

    fun onQuestionChange(q: String) {
        _state.value = _state.value.copy(askQuestion = q)
    }

    /** 提问式选股 */
    fun ask() {
        val q = _state.value.askQuestion.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(askLoading = true, askAnswer = null, askMatches = emptyList())
            val engine = askEngine ?: AskEngine(_state.value.symbols, emptyMap()).also { askEngine = it }
            val codes = engine.resolveStocks(q)
            val details = codes.mapNotNull { SkappApi.fetchStockDetail(it) }
            val answer = engine.answer(q, details)
            _state.value = _state.value.copy(
                askLoading = false,
                askAnswer = answer,
                askMatches = details,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        quoteJob?.cancel()
    }
}

/** 腾讯返回时间 yyyy/MM/dd HH:mm:ss → 距今毫秒；解析失败返回 null */
private fun parseAge(ts: String, now: Long): Long? {
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        now - (fmt.parse(ts)?.time ?: return null)
    } catch (e: Exception) {
        null
    }
}
