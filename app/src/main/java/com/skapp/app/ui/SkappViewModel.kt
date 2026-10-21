package com.skapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.AskEngine
import com.skapp.app.data.Decision
import com.skapp.app.data.DecisionCard
import com.skapp.app.data.KBar
import com.skapp.app.data.KlineApi
import com.skapp.app.data.MarketSnapshot
import com.skapp.app.data.PickSignal
import com.skapp.app.data.Quote
import com.skapp.app.data.SkappApi
import com.skapp.app.data.StockDetail
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
                askEngine = AskEngine(symbols.await(), names.await())
                _state.value = _state.value.copy(
                    loading = false,
                    picks = picks.await(),
                    decision = decision.await(),
                    market = market.await(),
                    symbols = symbols.await(),
                )
                refreshQuotes()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "数据加载失败",
                )
            }
        }
    }

    /** 30s 自动刷新实时报价 */
    fun startQuoteLoop() {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            while (isActive) {
                refreshQuotes()
                delay(30_000)
            }
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

    /** 打开个股详情：API + bundle + QA + K 线 */
    fun openDetail(code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = true, detail = null, kline = emptyList())
            val detail = SkappApi.fetchStockDetail(code)
            _state.value = _state.value.copy(detailLoading = false, detail = detail)
            _state.value = _state.value.copy(klineLoading = true)
            val k = KlineApi.fetchDayKline(code, 120)
            _state.value = _state.value.copy(klineLoading = false, kline = k)
        }
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
