package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.aggregateKline
import com.skapp.app.data.AskEngine
import com.skapp.app.data.DataCache
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
import com.skapp.app.data.LocalScreener
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.MarketHours
import com.skapp.app.data.BundleDetail
import com.skapp.app.data.MarketSnapshot
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.data.PickSignal
import com.skapp.app.data.Quote
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.data.SkappApi
import com.skapp.app.data.StockDetail
import com.skapp.app.data.TechnicalCalc
import com.skapp.app.data.SectorsSnapshot
import com.skapp.app.data.SymbolEntry
import com.skapp.app.data.TodayPicks
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 自选股（与 app 默认一致；本地持久化可后续再接） */
object Watchlist {
    val codes = listOf("600519", "300750", "002594", "600941", "hk00700")
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
    val picks: TodayPicks = TodayPicks(),
    val decision: Decision = Decision(),
    val market: MarketSnapshot = MarketSnapshot(),
    val news: NewsSnapshot = NewsSnapshot(),
    val sectors: SectorsSnapshot = SectorsSnapshot(),
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
    val detailAdjust: String = "qfq",
    val fundFlow: List<Map<String, Any?>> = emptyList(),
    val boardsOf: List<Map<String, Any?>> = emptyList(),
    val minuteTrend: List<Map<String, Any?>> = emptyList(),
    val liveQuote: Quote? = null,
    /** 推股列表的实时报价快照（code -> Quote），避免显示过期的本地快照价 */
    val pickQuotes: Map<String, Quote> = emptyMap(),
    /** K 线周期（日/周/月） */
    val kPeriod: KPeriod = KPeriod.DAY,
    /** stocks-holo 全量 bundle */
    val holo: HoloBundle? = null,
    /** 本地实时选股结果 */
    val screened: List<LocalScreener.Screened> = emptyList(),
    val screening: Boolean = false,
    val screenMsg: String? = null,
    /** 逐条快讯的实时算法计算（直接显示在标题下） */
    val newsCalcs: List<NewsCalc> = emptyList(),
    val newsCalcLoading: Boolean = false,
    /** 多池选股（网页版 8 池） */
    val pools: List<SiteBoardClient.Pool> = emptyList(),
    val poolsLoading: Boolean = false,
    /** 看板 */
    val board: Map<String, Any?> = emptyMap(),
    /** 实时总结 */
    val summary: Map<String, Any?> = emptyMap(),
)

class SkappViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var quoteJob: Job? = null
    private var askEngine: AskEngine? = null

    init {
        load()
        startQuoteLoop()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val names = async { SkappApi.fetchStockNames() }
                val symbols = async { SkappApi.fetchSymbols() }
                val picks = async { SkappApi.fetchTodayPicks() }
                val decision = async { SkappApi.fetchDecision() }
                val market = async { SkappApi.fetchMarket() }
                val news = async { SkappApi.fetchNews() }
                val sectors = async { SkappApi.fetchSectors() }
                askEngine = AskEngine(symbols.await(), names.await())
                _state.value = _state.value.copy(
                    loading = false,
                    picks = picks.await(),
                    decision = decision.await(),
                    market = market.await(),
                    news = news.await(),
                    sectors = sectors.await(),
                    symbols = symbols.await(),
                )
                refreshQuotes()
                loadIndices()
                loadNewsCalcs()
                loadPools()
                loadBoard()
                loadSummary()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "数据加载失败",
                )
            }
        }
    }

    /** 行情刷新：只在 A 股交易时段（含竞价）自动刷新，休市时停表 */
    fun startQuoteLoop() {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            while (isActive) {
                val now = java.util.Date()
                val phase = MarketHours.phase(now)
                val trading = MarketHours.isTrading(now) || MarketHours.isAuction(now)
                _state.value = _state.value.copy(
                    marketPhase = MarketHours.describe(now),
                    autoRefresh = trading,
                    nextRefreshIn = MarketHours.msUntilAutoRefresh(now),
                )
                if (trading) {
                    refreshQuotes()
                    refreshPickQuotes()
                    delay(30_000)
                } else {
                    // 休市：按距离下一次开市的剩余时间睡，最长 10 分钟醒一次更新状态
                    val wait = MarketHours.msUntilAutoRefresh(now) ?: (10 * 60_000L)
                    delay(minOf(wait, 10 * 60_000L))
                }
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
            val codes = (_state.value.picks.signals + _state.value.picks.values +
                _state.value.decision.cards.map { PickSignal(it.code, it.name, it.price, it.pctChg) })
                .map { it.code }.distinct()
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
        if (query.isEmpty()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        val res = _state.value.symbols.asSequence()
            .filter { s ->
                s.code.lowercase().contains(query) ||
                    (s.name?.lowercase()?.contains(query) == true)
            }
            .take(50)
            .toList()
        _state.value = _state.value.copy(searchResults = res)
    }

    /**
     * 打开个股详情：stocks-holo 全量 + 实时行情 + 本地引擎（引擎吃 holo/kline/technical）。
     * 缓存优先：重复点同一只股票且复权/周期未变则不重新拉。
     */
    /** 切换复权方式：已选中则忽略 */
    fun switchAdjust(adjust: String) {
        if (_state.value.detailAdjust == adjust) return
        val code = _state.value.detail?.code ?: return
        openDetail(code, adjust)
    }

    fun openDetail(code: String, adjust: String = "qfq") {
        val same = _state.value.detail?.code == code &&
            _state.value.detailAdjust == adjust &&
            _state.value.holo != null
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = !same, detailAdjust = adjust)
            // 1) stocks-holo（网页版同源：kline/technical/finance/moneyflow/news/sentiment）
            val holo = HoloClient.full(code)
            // 2) 日 K：holo 优先（全史），退化腾讯/东财
            val bars = when {
                holo.kline.size >= 30 -> holo.kline
                else -> DataCache.kline(code, adjust, 250)
            }
            if (!same) _state.value = _state.value.copy(kline = bars, holo = holo)
            // 3) 实时报价（现价以实时为准，修「多维评分价格与真实价不一致」）
            val q = DataCache.quote(code)
            // 4) 本地引擎（吃 K 线 + holo technical）
            val engine = DataCache.engine(code, adjust, bars)
            // 5) 资金流：holo 优先，退化东财
            val flow = holo.moneyflow.ifEmpty { DataCache.fundFlow(code, 60) }
            // 6) 板块：东财实时 + 云端
            val boards = DataCache.boards(code)
            // 7) 云端 bundle/QA（兜底）
            val cloud = SkappApi.fetchStockDetail(code)
            val qa = SkappApi.fetchQa(code)
            val merged = (cloud ?: StockDetail(code = code)).copy(
                name = q?.name?.takeIf { it.isNotBlank() } ?: holo.holo?.get("name")?.toString()
                    ?: cloud?.name,
                detail = cloud?.detail ?: bundleFromLocal(bars, q, holo),
                aiQa = qa ?: cloud?.aiQa,
            )
            _state.value = _state.value.copy(
                detailLoading = false,
                detail = merged,
                localEngine = engine,
                fundFlow = flow,
                boardsOf = boards,
                liveQuote = q,
            )
            if (MarketHours.isTrading() || MarketHours.isAuction() ||
                MarketHours.phase() == MarketHours.Phase.AFTER_HOURS) {
                _state.value = _state.value.copy(minuteTrend = DataCache.minute(code))
            }
        }
    }

    /** 切换 K 线周期（日/周/月由日 K 本地聚合，不重新请求） */
    fun switchPeriod(p: KPeriod) {
        if (_state.value.kPeriod == p) return
        val daily = _state.value.kline
        _state.value = _state.value.copy(kPeriod = p, kline = aggregateKline(daily, p))
    }

    /** 快讯 + 主线：逐条标题下实时算（标题命中的股票 → 实时行情 + 本地引擎） */
    fun loadNewsCalcs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(newsCalcLoading = true)
            val symbols = _state.value.symbols
            // 快讯：云端 news API 的 hot_news.items + extra.items
            val items = ArrayList<Map<String, Any?>>()
            _state.value.news.items.forEach { items.add(it) }
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
            val calcs = NewsCalcEngine.compute(items, symbols)
            _state.value = _state.value.copy(newsCalcLoading = false, newsCalcs = calcs)
        }
    }

    /** 多池选股（网页版 8 池） */
    fun loadPools() {
        viewModelScope.launch {
            _state.value = _state.value.copy(poolsLoading = true)
            val ps = runCatching { SiteBoardClient.pools() }.getOrElse { emptyList() }
            _state.value = _state.value.copy(poolsLoading = false, pools = ps)
        }
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

    /** 实时总结 */
    fun loadSummary() {
        viewModelScope.launch {
            val s = runCatching { SiteBoardClient.summary() }.getOrElse {
                mapOf<String, Any?>("status" to "insufficient", "reason" to it.message)
            }
            _state.value = _state.value.copy(summary = s)
        }
    }

    /** 本地实时选股（不用云端旧快照） */
    fun runScreen() {
        viewModelScope.launch {
            _state.value = _state.value.copy(screening = true, screenMsg = "正在实时拉取全市场行情…")
            val res = runCatching { LocalScreener.screen() }.getOrElse {
                _state.value = _state.value.copy(
                    screening = false,
                    screenMsg = "实时选股失败：${it.message ?: "行情接口不可达"}",
                )
                return@launch
            }
            _state.value = _state.value.copy(
                screening = false,
                screened = res,
                screenMsg = if (res.isEmpty()) "预筛后无满足条件的股票" else null,
            )
        }
    }

    /** 用本地 K 线造一个 bundle 形状，供 UI 复用同一套渲染 */
    private fun bundleFromLocal(bars: List<KBar>, q: Quote?, holo: HoloBundle? = null): BundleDetail? {
        if (bars.isEmpty() && holo == null) return null
        val f = TechnicalCalc.compute(bars)
        return BundleDetail(
            status = "ok",
            ts = bars.last().date,
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
            financialLatest = holo?.finance ?: emptyMap(),
        )
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null, kline = emptyList())
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
