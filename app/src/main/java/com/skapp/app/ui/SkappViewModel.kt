package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.AskEngine
import com.skapp.app.data.DataCache
import com.skapp.app.data.Decision
import com.skapp.app.data.DecisionCard
import com.skapp.app.data.KBar
import com.skapp.app.data.KlineApi
import com.skapp.app.data.LocalEngine
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.MarketHours
import com.skapp.app.data.BundleDetail
import com.skapp.app.data.MarketSnapshot
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.data.PickSignal
import com.skapp.app.data.Quote
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
            val quotes = MarketDataClient.fetchQuotes(prefixed)
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
     * 打开个股详情：缓存优先，避免重复点击已选中的复权按钮也重新拉取。
     * 数据链：日K(缓存→腾讯→东财) → 本地引擎 + 资金流 + 板块 + 实时报价
     */
    fun openDetail(code: String, adjust: String = "qfq") {
        val cached = _state.value.detail
        val sameStock = cached?.code == code && _state.value.detailAdjust == adjust
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = !sameStock, detailAdjust = adjust)
            // 1) 日 K（缓存优先）
            val bars = DataCache.kline(code, adjust, 250)
            if (_state.value.kline.isEmpty() || !sameStock) {
                _state.value = _state.value.copy(kline = bars)
            }
            // 2) 实时报价（盘中 30s TTL；休市时只取一次）
            val q = DataCache.quote(code)
            // 3) 本地引擎（缓存）
            val engine = DataCache.engine(code, adjust, bars)
            // 4) 云端 bundle + QA
            val cloud = SkappApi.fetchStockDetail(code)
            val qa = SkappApi.fetchQa(code)
            // 5) 资金流 + 板块（缓存）
            val flow = DataCache.fundFlow(code, 60)
            val boards = DataCache.boards(code)
            val merged = (cloud ?: StockDetail(code = code)).copy(
                name = q?.name?.takeIf { it.isNotBlank() } ?: cloud?.name,
                detail = (cloud?.detail ?: bundleFromLocal(bars, q)),
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

    /** 用本地 K 线造一个 bundle 形状，供 UI 复用同一套渲染 */
    private fun bundleFromLocal(bars: List<KBar>, q: Quote?): BundleDetail? {
        if (bars.isEmpty()) return null
        val f = TechnicalCalc.compute(bars)
        return BundleDetail(
            status = "ok",
            ts = bars.last().date,
            quote = mapOf<String, Double?>(
                "price" to (q?.price ?: bars.last().close),
                "pctChg" to q?.pctChg,
            ).filterValues { it != null } as Map<String, Double?>,
            technical = mapOf<String, Double?>(
                "ma5" to f?.ma5, "ma10" to f?.ma10, "ma20" to f?.ma20, "ma60" to f?.ma60,
                "rsi14" to f?.rsi14, "macd" to f?.macdHist, "vol_ratio" to f?.volRatio,
                "k" to f?.k, "d" to f?.d, "j" to f?.j,
                "atr14" to f?.atr14,
                "boll_lower" to f?.bollLower, "boll_mid" to f?.bollMid, "boll_upper" to f?.bollUpper,
            ).filterValues { it != null },
            financialLatest = emptyMap(),
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
