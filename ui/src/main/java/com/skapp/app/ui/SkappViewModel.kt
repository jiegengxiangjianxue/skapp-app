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
import com.skapp.app.data.AiRouter
import com.skapp.app.data.GitCodeAiClient
import com.skapp.app.data.OpenAiCompatClient
import com.skapp.app.data.GitHubModelsClient
import com.skapp.app.data.SymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


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


    private var sessionJob: Job? = null

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
        if (sessionJob?.isActive != true) {
            sessionJob = viewModelScope.launch {
                var ticks = 0
                while (isActive) {
                    picks.ensureLocalPicks()
                    if (ticks++ % 10 == 0) launch(Dispatchers.IO) { requestResult { picks.flushPicksArchive() } }
                    delay(30_000)
                }
            }
        }
        market.startQuoteLoop()
        news.startNewsLoop()
        market.startMarketPulseLoop()
        _state.value = _state.value.copy(loading = false, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val cached = readSymbolsSnapshot() ?: readSymbolsFromAssets()
            if (!cached.isNullOrEmpty()) {
                _state.update { it.copy(symbols = cached) }
                shared.askEngine = AskEngine(cached, shared.stockNames)
            }
        }
        shared.sections.load("代码表", fetch = {
            val symbols = DbTables.symbols()
            require(symbols.isNotEmpty()) { "代码表为空，已保留内置或上次结果" }
            if (symbols.size >= SYMBOL_TABLE_MIN) writeSymbolsSnapshot(symbols)
            shared.askEngine = AskEngine(symbols, shared.stockNames)
            symbols
        }) { s, v -> s.copy(symbols = v) }
        shared.sections.load("股票名称", fetch = { DbTables.stockNames() }) { s, v ->
            shared.stockNames = v
            s
        }
        shared.sections.load("自选报价", fetch = { market.refreshQuotes() }) { s, _ -> s }
        market.loadIndices()
        news.loadBoard()
        news.loadSummary()
        shared.sections.load("板块", fetch = {
            val result = com.skapp.app.data.LocalSectors.compute()
            requireNotNull(result) { "板块行情或映射不可达" }
            com.skapp.app.data.LocalSectors.snapshot(result)
        }) { s, v -> s.copy(sectors = v) }
        shared.sections.load("消息快照", fetch = { DbTables.news() }) { s, v ->
            if (s.news.items.isEmpty()) s.copy(news = v) else s
        }
        shared.sections.load("盘后归档", fetch = { DbTables.closeReview() }) { s, v -> s.copy(closeReview = v) }
        shared.sections.load("数据源", fetch = { DbTables.dataSources() }) { s, v -> s.copy(dataSources = v) }
        shared.sections.load("题材", fetch = { DbTables.theme() }) { s, v -> s.copy(themeReport = v) }
        shared.sections.load("市场状态", fetch = {
            com.skapp.app.data.LivePulse.of(com.skapp.app.data.LiveMarket.snapshot())
        }) { s, v -> s.copy(market = com.skapp.app.data.LocalSnapshot.market(v),
            decision = com.skapp.app.data.LocalSnapshot.decision(v, s.screened)) }
    }

    fun loadMomentum(force: Boolean = false) {
        shared.sections.load("板块动量", timeoutMs = 90_000, force = force,
            fetch = { com.skapp.app.data.SectorMomentum.load(force, maxStocks = 0) }) { s, v -> s.copy(momentum = v) }
    }

    fun loadBacktest(force: Boolean = false) {
        shared.sections.load("历史回测", timeoutMs = 90_000, force = force,
            fetch = { com.skapp.app.data.PickBacktest.load(maxFiles = 7, maxRecords = 80) }) { s, v -> s.copy(backtest = v) }
    }

    override fun onCleared() {
        super.onCleared()
        market.cancelQuoteLoop()
    }
}