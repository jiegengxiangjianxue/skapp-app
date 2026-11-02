package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import com.skapp.app.ui.state.requestResult
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.DbTables
import com.skapp.app.data.PicksArchiveQueue
import com.skapp.app.data.GitHubRepoWriter
import com.skapp.app.data.AskEngine
import com.skapp.app.data.EngineFeed
import com.skapp.app.data.KPeriod
import com.skapp.app.data.MarketHours
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.data.AiRouter
import com.skapp.app.data.GitCodeAiClient
import com.skapp.app.data.OpenAiCompatClient
import com.skapp.app.data.GitHubModelsClient
import com.skapp.app.data.SectorsSnapshot
import com.skapp.app.data.SymbolEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope


class SkappViewModel : ViewModel() {

    companion object {
        private const val SKAPP_PREFS = com.skapp.app.ui.state.SKAPP_PREFS
        private const val KEY_TOKEN = "gitcode_token"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_CUSTOM_MODEL = "custom_model"
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

    /** 全市场约 5500 条；低于这个数视为半截响应，不能覆盖好快照，也不能当全表用 */
    private val SYMBOL_TABLE_MIN = 3000

    private fun symbolsSnapshotFile(): java.io.File? =
        shared.appContext?.let { java.io.File(it.filesDir, "symbols_snapshot.tsv") }

    private fun writeSymbolsSnapshot(list: List<SymbolEntry>) {
        val file = symbolsSnapshotFile() ?: return
        runCatching {
            // 先写临时文件再原子替换：写到一半被杀不会留下半截表被当成全表
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(list.joinToString("\n") { "${it.code}\t${it.name.orEmpty()}" })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun readSymbolsSnapshot(): List<SymbolEntry>? {
        val file = symbolsSnapshotFile() ?: return null
        if (!file.exists()) return null
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2 || parts[0].isBlank()) null
                else SymbolEntry(parts[0], parts[1].ifBlank { null })
            }
        }.getOrNull()
    }

    /** 打包在 assets 里的代码表快照（app/src/main/assets/symbols_snapshot.json）。 */
    private fun readSymbolsFromAssets(): List<SymbolEntry>? {
        val ctx = shared.appContext ?: return null
        return runCatching {
            val text = ctx.assets.open("symbols_snapshot.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optString("s").ifBlank { o.optString("code") }
                val name = o.optString("n").ifBlank { o.optString("name") }
                if (code.isBlank()) null else SymbolEntry(code, name.ifBlank { null })
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

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
        val customUrl = sp.getString(KEY_CUSTOM_BASE_URL, "") ?: ""
        val customKey = sp.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        val customModel = sp.getString(KEY_CUSTOM_MODEL, "") ?: ""
        shared.appContext = context.applicationContext
        // 归档队列落在应用私有目录：杀进程后队列还在，下次打开接着传
        runCatching { PicksArchiveQueue.init(context.applicationContext.filesDir) }
        // GitHub 仓库写入（消息库 / 开盘推股历史）复用同一个 PAT
        GitHubRepoWriter.configure(gh)
        // 本地推股存档：只恢复上次落盘结果，不在启动时重算（重算要拉 11 页快照 + 40 只日 K）
        runCatching { picks.restoreLocalPicks() }
        runCatching { picks.restorePremarketDraft() }
        AiRouter.configure(context.applicationContext, t, gh, customUrl, customKey, customModel)
        _state.value = _state.value.copy(
            aiToken = t, aiConfigured = t.isNotBlank(),
            githubToken = gh, githubConfigured = gh.isNotBlank(),
            customBaseUrl = customUrl, customApiKey = customKey, customModel = customModel,
            customConfigured = customUrl.isNotBlank() && customKey.isNotBlank(),
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

    fun setCustomBaseUrl(v: String) { _state.value = _state.value.copy(customBaseUrl = v) }
    fun setCustomApiKey(v: String) { _state.value = _state.value.copy(customApiKey = v) }
    fun setCustomModel(v: String) { _state.value = _state.value.copy(customModel = v) }

    /** 设置页「保存自定义接口」按钮：落盘后立刻生效，不用重启 */
    fun saveCustomNow() {
        val s = _state.value
        shared.appContext?.let { ctx ->
            ctx.getSharedPreferences(SKAPP_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_BASE_URL, s.customBaseUrl)
                .putString(KEY_CUSTOM_API_KEY, s.customApiKey)
                .putString(KEY_CUSTOM_MODEL, s.customModel)
                .apply()
            OpenAiCompatClient.configure(s.customBaseUrl, s.customApiKey, s.customModel)
            _state.value = s.copy(customConfigured = OpenAiCompatClient.isConfigured())
            ai.refreshAiProviderStatus()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
            val namesD = async {
                requestResult { DbTables.stockNames() }.getOrDefault(emptyMap()).also { names ->
                    shared.stockNames = names
                    market.search(shared.searchQuery)
                }
            }
            val symbolsD = async(Dispatchers.IO) {
                var loaded = requestResult { DbTables.symbols() }.getOrDefault(emptyList())
                // 代码表直连 raw.githubusercontent.com，国内网络经常拉不到；而消息识股、搜索、问答全依赖它。
                // 拉全了就落盘快照，拉不到就用上次快照，避免整条分析链路静默失效（50 条快讯全「无个股」）。
                if (loaded.size >= SYMBOL_TABLE_MIN) writeSymbolsSnapshot(loaded)
                else readSymbolsSnapshot()?.takeIf { it.size >= SYMBOL_TABLE_MIN }?.let { loaded = it }
                // 首次或缓存失效时用内置快照：GitHub raw 与 api.github.com 在部分网络下都不可达
                if (loaded.size < SYMBOL_TABLE_MIN) {
                    readSymbolsFromAssets()?.let {
                        loaded = it
                        writeSymbolsSnapshot(it)
                    }
                }
                _state.value = _state.value.copy(symbols = loaded)
                market.search(shared.searchQuery)
                loaded
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
            } catch (e: CancellationException) {
                // 用户离开/协程被取消：静默结束，不覆盖 loading 终态
                throw e
            } catch (e: Exception) {
                // 任一取数或快照 IO 失败都复位 loading，否则 spinner 永久转（截图「加载中」卡死根因）
                _state.value = _state.value.copy(loading = false, error = e.message ?: "首屏加载失败")
            }
        }
    }

    /**
     * 启动预热：分 3 批串行推进（批内 2 路并发）。
     *
     * 为什么不能一次 6 路并发：冷启动时 6 个请求同时开打，会一起打满 HttpURLConnection 连接池
     * 并抢 CPU，首页卡顿/白屏的主要原因就在这里。分批后首页首屏先拿行情与指数，
     * 其余数据随后补上；重复调用（重进前台、页面 LaunchedEffect）由 warmUpJob 去重。
     *
     * 批间不插固定 delay：coroutineScope 已经等齐本批全部子协程才返回，再 sleep 一遍纯属
     * 白等（原为 200ms×2）。最大并发数不变，仍是每批 2 路。
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
            // 第 2 批：看板 + 消息（页面级，可稍后）
            coroutineScope {
                launch { runCatching { news.loadBoard() } }
                launch { runCatching { news.refreshNewsCalcs() } }
            }
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