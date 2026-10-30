package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import com.skapp.app.ui.state.requestResult
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.AllSourcesClient
import com.skapp.app.data.BanProb
import com.skapp.app.data.BanProbClient
import com.skapp.app.data.DbTables
import com.skapp.app.data.MarketState
import com.skapp.app.data.MarketStateEngine
import com.skapp.app.data.NewsArchive
import com.skapp.app.data.IntradayPicks
import com.skapp.app.data.PicksArchiveQueue
import com.skapp.app.data.GitHubRepoWriter
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
import com.skapp.app.data.StructuredPickArtifact
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
import kotlinx.coroutines.coroutineScope
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
    /** 本轮从异动榜掉出的票（名称+代码），最多 3 个；让用户看得到「谁掉队」而不是静默消失 */
    val pulseDropped: List<String> = emptyList(),
    /** 本地盘前推股（本地深算产出，T-1 收盘口径，当日冻结） */
    val localPremarketPicks: com.skapp.app.data.StructuredPickArtifact =
        com.skapp.app.data.StructuredPickArtifact(session = "premarket"),
    /** 本地午盘推股（本地深算产出，当日实时口径，当日冻结） */
    val localMiddayPicks: com.skapp.app.data.StructuredPickArtifact =
        com.skapp.app.data.StructuredPickArtifact(session = "midday"),
    val localPicksLoading: Boolean = false,
    val localPicksMsg: String? = null,
    /** 早盘推股草稿（09:00-09:30 随消息可变，09:30 后冻结） */
    val premarketDraft: com.skapp.app.data.PremarketPicks.Draft? = null,
    val picks: TodayPicks = TodayPicks(),
    /** 盘前/午盘推股已全部改为本地深算（LocalPicks），云端 premarket/midday 产物不再拉取也不再上屏 */
    val closeReview: com.skapp.app.data.CloseReview = com.skapp.app.data.CloseReview(),
    val winrateMatrix: com.skapp.app.data.WinrateMatrix = com.skapp.app.data.WinrateMatrix(),
    val decision: Decision = Decision(),
    /** 聪明钱信号（宽基 ETF 份额跟踪）；live 序列不足 20 天时 ready=false，仅展示不调仓 */
    val smartMoney: com.skapp.app.data.SmartMoney.Signal? = null,
    val smartMoneyLoading: Boolean = false,
    val market: MarketSnapshot = MarketSnapshot(),
    val news: NewsSnapshot = NewsSnapshot(),
    val sectors: SectorsSnapshot = SectorsSnapshot(),
    /** 数据源台账（登记/可用/已接执行器/缺口/不可用） */
    val dataSources: com.skapp.app.data.DataSourcesSnapshot = com.skapp.app.data.DataSourcesSnapshot(),
    /** 多池上次拉取时间（毫秒）：避免重复拉取，同时支持强制刷新 */
    val poolsLoadedAt: Long = 0L,
    /** 引擎产出聚合（engines_feed：regime/cycle/anomaly/price_level/orchestrator） */
    val engineFeeds: com.skapp.app.data.EngineFeed.EngineFeeds? = null,
    /** 最近一轮盘中心跳（0=未启动）；界面用它显示「30 秒前更新」 */
    val heartbeatAt: Long = 0L,
    val heartbeatLabel: String = "",
    /** 因子否决板：云端 v3 标定权重 × 端上 16 指标；gate 为样本外/市况校准闸门 */
    val factorBoard: com.skapp.app.data.FactorBoard? = null,
    val factorBoardLoading: Boolean = false,
    /** 板块主线：云端 sector_engine 的 27 个板块分档 + 个股归属 */
    val sectorBoard: com.skapp.app.data.SectorLink.Board? = null,
    val symbols: List<SymbolEntry> = emptyList(),
    val searchResults: List<SymbolEntry> = emptyList(),
    val detail: StockDetail? = null,
    val detailLoading: Boolean = false,
    val kline: List<KBar> = emptyList(),
    val klineLoading: Boolean = false,
    /** 十关键位（端侧引擎 KeyLevels）与实时回本率（RecoupCalc），随 K 线一起算 */
    val keyLevels: com.skapp.app.data.KeyLevels.Levels? = null,
    val recoup: com.skapp.app.data.RecoupCalc.Result? = null,
    /** 退出纪律（端侧引擎 ExitPlan）：清仓线/止盈线/今日形态/动作，随 K 线一起算 */
    val exitPlan: com.skapp.app.data.ExitPlan.Result? = null,
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
    /** 规格聚合分（含 flow/tape 外部票）：与云端 engine_v5 同构的那一份；null = 不可用，不是 0 */
    val canonicalScore: Double? = null,
    val canonicalResonance: Double? = null,
    /** 规格链路健康：版本 / 漂移 / 降级项。界面状态带直接读这三项。 */
    val engineSpecVersion: String? = null,
    val engineSpecDrift: Boolean = false,
    val chainDegraded: List<String> = emptyList(),
    val chainStatus: String = "insufficient",
    val detailAdjust: String = "qfq",
    val fundFlow: List<Map<String, Any?>> = emptyList(),
    /** 当日分钟级资金流（交易时段实时） */
    val fundFlowLive: List<Map<String, Any?>> = emptyList(),
    val boardsOf: List<Map<String, Any?>> = emptyList(),
    val minuteTrend: List<Map<String, Any?>> = emptyList(),
    /** 分时图元数据：所属交易日 / 昨收 / 实际数据源（分时图基准线与涨幅计算必需） */
    val minuteDate: String? = null,
    val minutePreClose: Double? = null,
    val minuteSource: String? = null,
    /** 日 K 实际生效的复权口径与来源，如「东财 push2his fqt=2（后复权）」 */
    val klineSource: String? = null,
    val liveQuote: Quote? = null,
    /** 推股列表的实时报价快照（code -> Quote），避免显示过期的本地快照价 */
    val pickQuotes: Map<String, Quote> = emptyMap(),
    /** K 线周期（日/周/月） */
    val kPeriod: KPeriod = KPeriod.DAY,
    /** 市场状态机输出 */
    val marketState: MarketState? = null,
    val marketStateLoading: Boolean = false,
    /** 每日市场宽度（涨停/跌停/成交额/涨跌家数），云端 market_breadth.json 累积 */
    val marketBreadth: Map<String, Any?> = emptyMap(),
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
    /** 本地深算完成时点（ISO），用于判定候选项是否已过期 */
    val screenedAt: String? = null,
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
        private const val SKAPP_PREFS = com.skapp.app.ui.state.SKAPP_PREFS
        private const val KEY_TOKEN = "gitcode_token"
        private const val KEY_GITHUB_TOKEN = "github_token"
    }

    private val _state = MutableStateFlow(UiState())

    val state: StateFlow<UiState> = _state.asStateFlow()


    // ───────── 状态持有者：拆成 4 个按职责划分的 StateHolder，门面只做转发 ─────────
    // 状态仍集中在同一个 UiState 里，holder 之间只共享 SharedVm，不互相持有 UiState 副本。
    private val shared = com.skapp.app.ui.state.SharedVm(_state, viewModelScope)
    /** 行情 / 指数 / 异动 / 市场状态 / 数据源 / 个股详情 */
    private val market = com.skapp.app.ui.state.MarketStateHolder(shared)
    /** 本地推股深算 / 存档 / 早盘草稿 / 归档队列 / 实时选股 */
    private val picks = com.skapp.app.ui.state.LocalPicksHolder(shared, market)
    /** 消息摘要 / 看板 / 多池 / 本地盘后分析 */
    private val news = com.skapp.app.ui.state.NewsSummaryHolder(shared, market, picks)
    /** AI 复盘 / 自由问答 / 提问式选股 */
    private val ai = com.skapp.app.ui.state.AiStateHolder(shared)

    /** MainActivity / 设置页注入的应用 Context；值存在 shared 里，holder 与门面共用一份 */
    var _context: android.content.Context?
        get() = shared.appContext
        set(value) {
            shared.appContext = value
        }

    // ───────── 以下为门面委托：对外签名与拆分前逐条一致 ─────────

    // 行情 / 指数 / 异动 / 市场状态 / 因子板 / 数据源
    fun startQuoteLoop() { market.startQuoteLoop() }
    fun refreshPulse() { market.refreshPulse() }
    fun startMarketPulseLoop() { market.startMarketPulseLoop() }
    suspend fun refreshQuotes() { market.refreshQuotes() }
    fun loadIndices() { market.loadIndices() }
    fun refreshPickQuotes() { market.refreshPickQuotes() }
    fun loadMarketState() { market.loadMarketState() }
    fun loadSmartMoney() { market.loadSmartMoney() }
    fun loadFactorBoard(codes: List<String> = emptyList(), force: Boolean = false) { market.loadFactorBoard(codes, force) }
    fun loadBanProb() { market.loadBanProb() }
    fun getBalancedZtRate(tier: String): Double? = market.getBalancedZtRate(tier)
    fun isLiAnbanSufficient(tier: String): Boolean = market.isLiAnbanSufficient(tier)
    fun loadAllSources() { market.loadAllSources() }
    fun loadDataSources() { market.loadDataSources() }
    fun search(q: String) { market.search(q) }
    suspend fun loadEngineFeeds(force: Boolean = false): EngineFeed.EngineFeeds = market.loadEngineFeeds(force)

    // 个股详情
    fun openDetail(code: String, adjust: String = "qfq") { market.openDetail(code, adjust) }
    fun switchAdjust(adjust: String) { market.switchAdjust(adjust) }
    fun switchPeriod(p: KPeriod) { market.switchPeriod(p) }
    fun refreshDetailLive() { market.refreshDetailLive() }
    fun closeDetail() { market.closeDetail() }

    // 本地推股 / 实时选股
    fun ensureLocalPicks() { picks.ensureLocalPicks() }
    fun ensurePicksFor(session: com.skapp.app.data.LocalPicks.Session) { picks.ensurePicksFor(session) }
    fun refreshLocalPicks(session: com.skapp.app.data.LocalPicks.Session) { picks.refreshLocalPicks(session) }
    fun loadPicks(force: Boolean = false) { picks.loadPicks(force) }
    fun runScreen(mode: String = "score", force: Boolean = false): Job? = picks.runScreen(mode, force)

    // 消息 / 摘要 / 看板 / 多池 / 本地分析
    fun startNewsLoop() { news.startNewsLoop() }
    fun loadNewsCalcs(): Job = news.loadNewsCalcs()
    fun loadSummary(): Job = news.loadSummary()
    fun loadBoard(force: Boolean = false) { news.loadBoard(force) }
    fun loadPools(force: Boolean = true) { news.loadPools(force) }
    fun refreshPoolReprice() { news.refreshPoolReprice() }
    fun refreshLocalAnalytics() { news.refreshLocalAnalytics() }
    fun refreshRealtimeSummary(mode: String = "score") { news.refreshRealtimeSummary(mode) }

    // AI
    fun setAiToken(t: String) { ai.setAiToken(t) }
    fun setGithubToken(t: String) { ai.setGithubToken(t) }
    fun refreshAiProviderStatus() { ai.refreshAiProviderStatus() }
    fun aiReviewCurrent() { ai.aiReviewCurrent() }
    fun aiAsk() { ai.aiAsk() }
    fun onAiQuestionChange(q: String) { ai.onAiQuestionChange(q) }
    fun onQuestionChange(q: String) { ai.onQuestionChange(q) }
    fun ask() { ai.ask() }


    private var warmUpJob: Job? = null

    init {
        // 不在 init 里 load()：token 要等 loadSavedToken 恢复；此时拉数据会因无鉴权全失败（app 打开全空的根因）
        market.startQuoteLoop()
        news.startNewsLoop()
        market.startMarketPulseLoop()
    }

    /** MainActivity 启动时调用：从 SharedPreferences 恢复 GitCode + GitHub token，并初始化双源路由 */
    fun loadSavedToken(context: android.content.Context) {
        val sp = context.getSharedPreferences(SKAPP_PREFS, android.content.Context.MODE_PRIVATE)
        val t = sp.getString(KEY_TOKEN, "") ?: ""
        val gh = sp.getString(KEY_GITHUB_TOKEN, "") ?: ""
        shared.appContext = context.applicationContext
        // 归档队列落在应用私有目录：杀进程后队列还在，下次打开接着传
        runCatching { PicksArchiveQueue.init(context.applicationContext.filesDir) }
        // GitHub 仓库写入（消息库 / 开盘推股历史）复用同一个 PAT
        GitHubRepoWriter.configure(gh)
        // 本地推股存档：只恢复上次落盘结果，不在启动时重算（重算要拉 11 页快照 + 40 只日 K）
        runCatching { picks.restoreLocalPicks() }
        runCatching { picks.restorePremarketDraft() }
        AiRouter.configure(context.applicationContext, t, gh)
        _state.value = _state.value.copy(
            aiToken = t, aiConfigured = t.isNotBlank(),
            githubToken = gh, githubConfigured = gh.isNotBlank(),
            aiProviderStatus = AiRouter.statusLine(),
        )
    }

    /** 保存 token 到 SharedPreferences */
    fun saveToken(context: android.content.Context) {
        context.getSharedPreferences(SKAPP_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, _state.value.aiToken).apply()
    }

    /** 保存 GitHub token 到 SharedPreferences */
    fun saveGithubToken(context: android.content.Context) {
        context.getSharedPreferences(SKAPP_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_GITHUB_TOKEN, _state.value.githubToken).apply()
    }

    /** 设置页「保存 GitHub token」按钮 */
    fun saveGithubTokenNow() {
        shared.appContext?.let { ctx ->
            saveGithubToken(ctx)
            GitHubModelsClient.setToken(_state.value.githubToken)
            ai.refreshAiProviderStatus()
        }
    }

    fun saveTokenNow() {
        shared.appContext?.let { ctx ->
            saveToken(ctx)
            GitCodeAiClient.setToken(_state.value.aiToken)
            ai.refreshAiProviderStatus()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val namesD = async {
                requestResult { DbTables.stockNames() }.getOrDefault(emptyMap()).also { names ->
                    shared.stockNames = names
                    market.search(shared.searchQuery)
                }
            }
            val symbolsD = async {
                requestResult { DbTables.symbols() }.getOrDefault(emptyList()).also { symbols ->
                    _state.value = _state.value.copy(symbols = symbols)
                    market.search(shared.searchQuery)
                }
            }
            // ── 推股 / 决策 / 市场概览：本地产出（实时），不再读云端快照 ──
            // 云端那三份（today_picks / decision / market）是收盘后算的，盘中打开是旧结论。
            // 这里改成：本地深算产物 + 实时全市场脉搏现算，界面模型不变。
            val pulseD = async {
                runCatching {
                    com.skapp.app.data.LivePulse.of(com.skapp.app.data.LiveMarket.snapshot())
                }.getOrNull()
            }
            val picksD = async { com.skapp.app.data.LocalSnapshot.todayPicks(picks.localPicksArtifact()) }
            val closeReviewD = async { requestResult { DbTables.closeReview() }.getOrDefault(com.skapp.app.data.CloseReview()) }
            val winrateD = async { requestResult { DbTables.winrate() }.getOrDefault(com.skapp.app.data.WinrateMatrix()) }
            val decisionD = async {
                com.skapp.app.data.LocalSnapshot.decision(pulseD.await(), _state.value.screened)
            }
            val marketD = async { com.skapp.app.data.LocalSnapshot.market(pulseD.await()) }
            val newsD = async { requestResult { DbTables.news() }.getOrDefault(NewsSnapshot()) }
            // 板块：本地实时聚合（快照 × 库里的成分映射）优先；映射/行情缺失才回退库表归档并标明
            val sectorsD = async {
                val local = runCatching { com.skapp.app.data.LocalSectors.compute() }.getOrNull()
                if (local != null) com.skapp.app.data.LocalSectors.snapshot(local)
                else requestResult { DbTables.sectors() }.getOrDefault(SectorsSnapshot())
            }
            val sourcesD = async { requestResult { DbTables.dataSources() }.getOrDefault(com.skapp.app.data.DataSourcesSnapshot()) }
            val poolsD = async { requestResult { DbTables.pools() }.getOrDefault(emptyList()) }
            // 看板/主题归档只用于回看：当日涨跌榜与板块热度由 LocalSectors 实时现算
            val boardD = async { runCatching { news.localBoardMap() }.getOrDefault(news.localBoardUnavailable()) }
            val themeD = async { requestResult { DbTables.theme() }.getOrNull() }
            val smartD = async { runCatching { DbTables.smartMoney() }.getOrNull() }

            val symbols = symbolsD.await()
            val names = namesD.await()
            shared.askEngine = AskEngine(symbols, names)
            _state.value = _state.value.copy(
                loading = false,
                picks = picksD.await(),
                closeReview = closeReviewD.await(),
                winrateMatrix = winrateD.await(),
                decision = decisionD.await(),
                smartMoney = smartD.await(),
                market = marketD.await(),
                news = newsD.await(),
                sectors = sectorsD.await(),
                dataSources = sourcesD.await(),
                pools = poolsD.await(),
                board = boardD.await(),
                themeReport = themeD.await(),
                symbols = symbols,
            )
            // 首屏预热消息计算与实时总结，失败时保留已有快照并写入明确状态。
            news.loadNewsCalcs()
            news.loadSummary()
            market.loadAllSources()
            market.loadBanProb()
            picks.ensureLocalPicks()    // 首屏先给本地深算产物，缺就后台补一次
            market.refreshQuotes()
            market.refreshPickQuotes()   // 推股/决策列表补实时报价（覆盖快照价）
            news.refreshPoolReprice()  // 池子成分本地实时重算（首屏先用云端快照，随即覆盖）
            market.loadIndices()
            market.loadMarketState()
        }
    }

    /**
     * 启动预热：分 3 批串行推进，批间让出主线程。
     *
     * 为什么不能一次 6 路并发：冷启动时 6 个请求同时开打，会一起培 HttpURLConnection 连接池
     * 并抢 CPU，首页卡顿/白屏的主要原因就在这里。分批后首页首屏先拿行情与指数，
     * 其余数据随后补上；重复调用（重进前台、页面 LaunchedEffect）由 warmUpJob 去重。
     */
    fun warmUpAll() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = viewModelScope.launch {
            // 第 0 步：先补传上次没发出去的消息库 / 推送历史（用户开 App 时间不固定）
            launch { runCatching { picks.flushPicksArchive() } }
            // 第 1 批：首屏必需（自选行情 + 指数）
            coroutineScope {
                launch { runCatching { market.refreshQuotes() } }
                launch { runCatching { market.loadIndices() } }
            }
            delay(200)
            // 第 2 批：看板 + 消息（页面级，可稍后）
            coroutineScope {
                launch { runCatching { news.loadBoard() } }
                launch { runCatching { news.refreshNewsCalcs() } }
            }
            delay(200)
            // 第 3 批：推股池 + 实时总结正文
            coroutineScope {
                launch { runCatching { picks.loadPicks() } }
                launch { runCatching { news.refreshSummary() } }
            }
            // 第 4 步：当前时段还没有当日本地推股存档时才补一次（冷启动不抢在首屏前）
            delay(500)
            com.skapp.app.data.LocalPicks.sessionFor(MarketHours.phase())?.let { session ->
                runCatching { picks.rebuildLocalPicks(session, force = false) }
            }
            // 第 5 步：早盘名单（09:00-09:30 随消息可变，09:30 后冻结）
            launch { runCatching { picks.refreshPremarketDraft() } }
            // 归档队列定时补传：盘中每 5 分钟把攒的消息 / 推送记录刷一次
            launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    runCatching { picks.flushPicksArchive() }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        market.cancelQuoteLoop()
    }
}
