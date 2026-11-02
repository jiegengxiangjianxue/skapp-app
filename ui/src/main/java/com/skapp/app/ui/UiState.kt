package com.skapp.app.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.skapp.app.data.AllSourcesClient
import com.skapp.app.data.BanProb
import com.skapp.app.data.Decision
import com.skapp.app.data.HoloBundle
import com.skapp.app.data.KBar
import com.skapp.app.data.KPeriod
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.data.LocalScreener
import com.skapp.app.data.MarketSnapshot
import com.skapp.app.data.MarketState
import com.skapp.app.data.NewsCalc
import com.skapp.app.data.NewsSnapshot
import com.skapp.app.data.Quote
import com.skapp.app.data.SectorsSnapshot
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.data.StockDetail
import com.skapp.app.data.SymbolEntry
import com.skapp.app.data.TodayPicks
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
    /**
     * 取数缺口：label →「原因 @HH:mm:ss」。
     *
     * 红线要求「缺数据写 unknown，不得静默」：请求失败一律落到这里并在诊断页展示，
     * 不许把失败静静地化成「暂无数据」。下一次取值成功同名条目自动清掉。
     */
    val dataGaps: Map<String, String> = emptyMap(),
    val quotes: List<QuoteRow> = emptyList(),
    val quoteTs: String? = null,
    val marketPulse: List<com.skapp.app.data.MarketPulseRow> = emptyList(),
    val marketPulseTs: String? = null,
    val marketPulseLoading: Boolean = false,
    /** 异动榜口径：全市场 | 池内（自选+推股）| none（本轮没拿到）；界面必须原样展示 */
    val marketPulseScope: String = "unknown",
    /** 本轮从异动榜掉出的票（名称+代码），最多 3 个；让用户看得到「谁掉队」而不是静默消失 */
    val pulseDropped: List<String> = emptyList(),
    /** 本地盘前推股（本地深算产出，T-1 收盘口径，当日冻结） */
    val localPremarketPicks: com.skapp.app.data.StructuredPickArtifact =
        com.skapp.app.data.StructuredPickArtifact(session = "premarket"),
    /** 本地早盘推股（本地深算产出，上午盘中快照，含当日未完成日 K） */
    val localMorningPicks: com.skapp.app.data.StructuredPickArtifact =
        com.skapp.app.data.StructuredPickArtifact(session = "morning"),
    /** 本地午盘推股（本地深算产出，下午盘中快照，含当日未完成日 K） */
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
    /** 多日趋势推演（端侧 TrendForecast）：方向/置信度/ATR 区间；同一批日 K 顺手算，零额外请求 */
    val trendForecast: com.skapp.app.data.TrendForecast.Result? = null,
    /** 分钟级实时 K（MinuteKline）。空列表时必须看 minuteBarsNote，别当成「这只票没有分钟数据」 */
    val minuteBars: List<KBar> = emptyList(),
    val minuteBarsPeriod: String = "5 分钟",
    val minuteBarsNote: String? = null,
    /** 推股名单的多日趋势（按代码索引）：只算名单前几只，取不到的票就没条目，界面要明说 */
    val picksTrend: Map<String, com.skapp.app.data.TrendForecast.Result> = emptyMap(),

    /** 参与过多日趋势计算的代码。用来区分「没轮到算」与「算了但失败」——两者都表现为没有结果。 */
    val picksTrendAttempted: Set<String> = emptySet(),
    /**
     * 本地深算的分阶段耗时（首屏端到端 / 快照 / 筛选 / 引擎 / 日 K 拉取次数与均耗）。
     * 只用于把「首屏到底慢在哪」变成可读的数字，不参与任何排序与判断。
     */
    val localPicksTiming: String? = null,
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
    /** 自定义 OpenAI 兼容接口（第三兜底源） */
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",
    val customConfigured: Boolean = false,
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
