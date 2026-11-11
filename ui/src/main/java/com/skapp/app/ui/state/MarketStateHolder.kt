package com.skapp.app.ui.state


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.BanProbClient
import com.skapp.app.data.DbTables
import com.skapp.app.data.MarketStateEngine
import com.skapp.app.data.aggregateKline
import com.skapp.app.data.DataCache
import com.skapp.app.data.EngineFeed
import com.skapp.app.data.HoloBundle
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
import com.skapp.app.data.Quote
import com.skapp.app.data.StockDetail
import com.skapp.app.data.TechnicalCalc
import com.skapp.app.data.SymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.skapp.app.ui.QuoteRow
import com.skapp.app.ui.Watchlist

/** 行情 / 指数 / 异动（pulse）/ 市场状态 / 因子板 / 数据源台账 / 个股详情实时链路 */
internal class MarketStateHolder(shared: SharedVm) : StateHolder(shared) {

    private var quoteJob: Job? = null

    private var pulseJob: Job? = null

    private var detailJob: Job? = null

    private var detailLiveJob: Job? = null

    private var lastSseEventAt: Long = 0L

    private var lastEngineAt: Long = 0L
    private val centralRevision = java.util.concurrent.atomic.AtomicLong()
    private val detailGeneration = java.util.concurrent.atomic.AtomicLong()

    /** 云端引擎产物（qwerzxcva/stocks-ai-view），供 EnginesScreen 使用 */
    /**
     * 引擎池数据。
     *
     * 走**本地实时装配**（实时快照 + 本地 12 引擎），不再读云端归档的 engine_v5/decision。
     * 拿不到就返回带 missing 说明的空结构——宁缺毋假，不拿云端旧结论顶替。
     */
    suspend fun loadEngineFeeds(force: Boolean = false): EngineFeed.EngineFeeds =
        com.skapp.app.data.EngineFeedLive.load(force)

    /** 聪明钱信号（ETF 份额）单独刷新：决策页右上「刷新」与下拉都走这里 */
    fun loadSmartMoney() {
        viewModelScope.launch {
            _state.update { it.copy(smartMoneyLoading = true) }
            val s = gap("聪明钱信号", { null }) { DbTables.smartMoney() }
            _state.update { it.copy(smartMoney = s ?: _state.value.smartMoney, smartMoneyLoading = false) }
        }
    }

    /**
     * 因子否决板：云端 v3 标定（16 指标相对权重）× 端上指标，再叠加样本外校准闸门。
     *
     * 候选来源取「关注列表 + 云端决策卡」的交集式合并（去重、上限 8 只），
     * 每只都要拉日 K 才能算指标，所以限制数量而不是全市场扫。
     */
    fun loadFactorBoard(codes: List<String> = emptyList(), force: Boolean = false) {
        viewModelScope.launch {
            val cur = _state.value
            if (cur.factorBoardLoading) return@launch
            if (!force && !cur.factorBoard?.rows.isNullOrEmpty()) return@launch
            _state.update { it.copy(factorBoardLoading = true) }

            // 候选 = 关注列表 + 云端决策卡，去重后最多 8 只（每只要拉日 K，不能全市场扫）
            val targets = (codes.toList() + cur.decision.cards.map { it.code })
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(8)

            try {
                // 标定参数属数据库表，统一经 Db 取（界面层不再直接读云端）
                val calibDocs = com.skapp.app.data.Db.calibrationDocs()
                val calibDoc = calibDocs[com.skapp.app.data.ArchiveDoc.WEIGHT_CALIBRATION]
                // 参数清单缺失（云端还没发布/网络失败）时继续用标定——不能因为校验机制自己没就绪
                // 就把功能停掉；只有「明确版本不一致」才降级到内置默认口径。
                val manifestJson = calibDocs[com.skapp.app.data.ArchiveDoc.ENGINE_MANIFEST]?.json
                val manifestCheck = com.skapp.app.data.EngineManifest.check(manifestJson)
                val calib = if (manifestJson == null || manifestCheck.ok) {
                    com.skapp.app.data.FactorWeightStrategy.parseCalibration(calibDoc?.json)
                } else {
                    null
                }
                val gate = gap("因子校准闸门", { null }) { com.skapp.app.data.SignalCalibration.load() }
                // 板块主线与因子板一起取：两者在界面上是并列的两道过滤，分开加载会错位
                val sectors = gap("板块映射", { null }) { com.skapp.app.data.SectorLink.load() }

                // 计算必须离开主线程：每只票要跑 16 个指标、160 根 K 线。之前这段直接在
                // viewModelScope（Main）里跑，进决策页会卡住主线程，严重时被判 ANR——表现为「容易闪退」。
                //
                // 取数并发：8 只票原本在同一个 IO 线程里 map 串行拉，整块耗时=8 次往返之和。
                // 改为先并发发起、再按 targets 原序收集（顺序稳定，UI 不跳），总耗时≈最慢的那一只。
                // 单只票打分实测仅 0.2ms（engine PerfProbeTest 基线），瓶颈在网络不在 CPU，
                // 所以计算放 Default、不与网络抢 IO 线程。KlineApi 每次调用自带局部连接，可并发调用。
                val klines = coroutineScope {
                    targets.map { code ->
                        async(Dispatchers.IO) {
                            code to gap("决策K线($code)", { emptyList() }) { KlineApi.fetchDayKline(code, 160) }
                        }
                    }.map { it.await() }
                }

                val rows = withContext(Dispatchers.Default) {
                    klines.map { (code, bars) ->
                        val score = if (calib != null && bars.size >= 60) {
                            gap("因子评分($code)", { null }) {
                                com.skapp.app.data.FactorWeightStrategy.score(code, bars, calib)
                            }
                        } else null
                        com.skapp.app.data.FactorRow(code = code, name = "", score = score)
                    }
                }

                _state.update { it.copy(
                    sectorBoard = sectors ?: _state.value.sectorBoard,
                    factorBoard = com.skapp.app.data.FactorBoard(
                        calib = calib,
                        gate = gate,
                        rows = rows,
                        dataNote = com.skapp.app.data.Db.healthText(listOfNotNull(calibDoc)),
                    ),
                    factorBoardLoading = false,
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(
                    factorBoardLoading = false,
                    factorBoard = _state.value.factorBoard?.copy(lastError = e.message ?: "加载失败"),
                ) }
            }
        }
    }

    /** 市场状态计算 */
    fun loadMarketState() {
        viewModelScope.launch {
            _state.update { it.copy(marketStateLoading = true) }
            try {
                // 走实时路径：盘中打开就是当下的市场状态，不是云端收盘快照
                val ms = MarketStateEngine.computeLive()
                _state.update { it.copy(marketState = ms, marketStateLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(marketStateLoading = false) }
            }
        }
    }

    /** 加载 ban_prob 涨停次日表现概率 */
    fun loadBanProb() {
        viewModelScope.launch {
            _state.update { it.copy(banProbLoading = true) }
            try {
                val bp = BanProbClient.load()
                _state.update { it.copy(banProb = bp, banProbLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(banProbLoading = false) }
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
                val j = shared.allSources.fetch() ?: run {
                    _state.update { it.copy(registryStats = mapOf("status" to "unknown")) }
                    return@launch
                }
                _state.update { it.copy(
                    allSourcesSummary = shared.allSources.summaryLine(j),
                    allSourcesQuotes = shared.allSources.parseQuotes(j),
                    registryStats = registryStats(j),
                ) }
            } catch (_: Exception) {
                _state.update { it.copy(registryStats = mapOf("status" to "unknown")) }
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
                    _state.update { it.copy(
                        marketPhase = newPhase,
                        autoRefresh = trading,
                        nextRefreshIn = newRefreshIn,
                    ) }
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

    /** 供页面主动触发一次异动扫描（非交易时段也能拿最近一次结果）。 */
    fun refreshPulse() {
        viewModelScope.launch { runCatching { refreshMarketPulse() } }
    }

    /**
     * 全市场实时循环：统一交给 [MarketHeartbeat] 管节拍。
     *
     * 一轮心跳（30s）做三件事：
     *   1. 全股盘口 → 异动榜（[refreshMarketPulse]，不拉逐股 K 线）
     *   2. 全股盘口 → 市场状态（相位/涨跌停，复用同一份快照，不再各拉一遍）
     *   3. 每 5 分钟顺带做一次候选池深算（逐只日 K + 12 引擎），结果写进 screened
     *
     * 节拍逻辑抽在 [HeartbeatPolicy]，交易时段判断也在心跳内部，外层不再自己 sleep。
     */
    fun startMarketPulseLoop() {
        pulseJob?.cancel()
        pulseJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.skapp.app.data.MarketHeartbeat.run(onTick = { tick ->
                    _state.update { it.copy(
                        heartbeatAt = tick.at,
                        heartbeatLabel = tick.error ?: tick.pulse.label,
                    ) }
                    // 把心跳同一份快照算出的板块强弱传下去：cycle_v2 不再恒为 0，
                    // 也不会为了一个分数再聚合一遍全市场
                    gap("市场状态", { null }) {
                        com.skapp.app.data.MarketStateEngine.computeLive(tick.pulse, tick.sectors)
                    }?.let { ms ->
                            _state.update { it.copy(marketState = ms, marketStateLoading = false) }
                        }
                    tick.deep?.takeIf { it.isNotEmpty() }?.let { deep ->
                        _state.update { it.copy(screened = deep) }
                    }
                    if (!_state.value.marketPulseLoading) {
                        runCatching { refreshMarketPulse() }
                    }
                })
            }
        }
    }

    /**
     * 全市场快照不可得时的异动口径：自选 + 当日推股池，走腾讯直连。
     *
     * 字段能给的只有价格与涨跌幅（腾讯报价没有量比/资金流），所以池内口径下量比、
     * 主力净额恒为 null——排序会退化成单纯涨幅分位，这点由调用方的 scope 标注告知用户。
     */
    private suspend fun poolSnapshot(): List<Map<String, Any?>> {
        val codes = (
            Watchlist.codes +
                _state.value.picks.signals.mapNotNull { it.code } +
                _state.value.picks.values.mapNotNull { it.code }
            ).mapNotNull { com.skapp.app.data.Code.normalize(it) }.distinct()
        if (codes.isEmpty()) return emptyList()
        val quotes = gap("池内异动报价", { emptyMap<String, Quote>() }) {
            com.skapp.app.data.TencentQuote.batch(codes)
        }
        return quotes.values.map { q ->
            mapOf<String, Any?>(
                "code" to q.code,
                "name" to q.name,
                "price" to q.price,
                "pctChg" to q.pctChg,
                "volRatio" to null,
                "turnover" to null,
                "volume" to null,
                "amplitude" to null,
                "turnoverRate" to null,
                "pe" to null,
                "mv" to null,
                "mainNet" to null,
                "speed" to null,
            )
        }
    }

    suspend fun refreshMarketPulse() {
        _state.update { it.copy(marketPulseLoading = true) }
        try {
            // 全市场快照被拒（东财风控/限流）时不能静默留空：退到自选+推股池，口径随之缩小
            var scope = "全市场"
            val snapshot = gap("全市场快照", { emptyList<Map<String, Any?>>() }) {
                LocalScreener.marketSnapshot(pz = 500)
            }.ifEmpty {
                scope = "池内"
                poolSnapshot()
            }
            val prevRows = _state.value.marketPulse.associateBy { it.code }

            // ── 1) 横截面基础过滤（主板 / 非 ST / 未涨停附近）──
            data class Cell(
                val code: String,
                val name: String,
                val price: Double?,
                val pct: Double,
                val volRatio: Double?,
                val turnover: Double?,
                val mainNet: Double?,
                val fieldSpeed: Double?,
            )
            val cells = snapshot.mapNotNull { raw ->
                val code = raw["code"]?.toString() ?: return@mapNotNull null
                val pct = (raw["pctChg"] as? Number)?.toDouble() ?: return@mapNotNull null
                val name = raw["name"]?.toString().orEmpty()
                // 与推荐/评分同一口径：ST 与退市整理股都排除（此前只判 ST，退市整理股会漏进候选）
                // 池内口径允许 ETF与创业/科创（5/1/3 开头），全市场口径仍只留主板个股
                if ((scope == "全市场" && !com.skapp.app.data.Code.isMainBoard(code)) ||
                    com.skapp.app.data.NameRules.isSTOrDelisting(name) ||
                    pct >= com.skapp.app.data.MarketLimits.LIMIT_UP_PCT
                ) return@mapNotNull null
                Cell(
                    code = code,
                    name = name,
                    price = (raw["price"] as? Number)?.toDouble(),
                    pct = pct,
                    volRatio = (raw["volRatio"] as? Number)?.toDouble(),
                    turnover = (raw["turnover"] as? Number)?.toDouble(),
                    mainNet = (raw["mainNet"] as? Number)?.toDouble(),
                    fieldSpeed = (raw["speed"] as? Number)?.toDouble(),
                )
            }

            // 池内也拉不到（自选为空 / 腾讯也挂）：保留上一轮结果，但把范围如实写进状态，
            // 让界面显示「异动不可用」，而不是拿旧数据冒充本轮
            if (cells.isEmpty()) {
                _state.update { it.copy(marketPulseScope = "none") }
                return
            }

            // ── 2) 横截面分位（0~1）：排序键改用相对强度后，榜单不再被单笔大单带着跳 ──
            fun ranks(values: List<Pair<String, Double>>): Map<String, Double> {
                if (values.isEmpty()) return emptyMap()
                val n = values.size
                return values.sortedBy { it.second }
                    .mapIndexed { i, p -> p.first to (if (n <= 1) 0.5 else i.toDouble() / (n - 1)) }
                    .toMap()
            }
            val pctRank = ranks(cells.map { it.code to it.pct })
            val volRank = ranks(cells.mapNotNull { c -> c.volRatio?.let { c.code to it } })
            val moneyRank = ranks(cells.mapNotNull { c -> c.mainNet?.let { c.code to it } })

            // ── 3) 逐票：动量只做辅助与归因，不再主导排序 ──
            val ranked = cells.map { c ->
                val prev = prevRows[c.code]
                val prevPct = prev?.pctChg
                // 两种动量口径必须分开：tick = 本轮快照差（真 30 秒动量）；field = 东财 f124（约 5 分钟窗口）
                val tickSpeed = prevPct?.let { c.pct - it }
                val speed = tickSpeed ?: c.fieldSpeed
                val speedSource = when {
                    tickSpeed != null -> "tick"
                    c.fieldSpeed != null -> "field"
                    else -> "none"
                }
                // 去抖：连续同向（同为涨/同为跌）才累加轮数，否则重新计 1
                val streak = if (prev != null && prevPct != null && prevPct != 0.0 && c.pct != 0.0 &&
                    (prevPct > 0) == (c.pct > 0)
                ) prev.streak + 1 else 1
                val outlook = when {
                    speed != null && speed >= 0.8 && (c.mainNet == null || c.mainNet > 0) -> "bullish"
                    speed != null && speed <= -0.8 && c.mainNet != null && c.mainNet < 0 -> "bearish"
                    speed != null && speed > 0.3 -> "watch_up"
                    speed != null && speed < -0.3 -> "watch_down"
                    else -> "insufficient"
                }
                val dirWord = when {
                    speed == null -> "无动量数据"
                    speed > 0 -> "涨速加快"
                    speed < 0 -> "涨速转弱"
                    else -> "涨速持平"
                }
                val speedBasis = when (speedSource) {
                    "tick" -> "本轮 30 秒快照差"
                    "field" -> "东财涨速字段（约 5 分钟窗口，首轮替代值）"
                    else -> "无"
                }
                val reason = when (outlook) {
                    "bullish" -> "$dirWord ${"%.2f".format(speed)} 个百分点（$speedBasis）${if (c.mainNet != null) "，主力净额 ${"%.2f".format(c.mainNet)}" else ""}；仅为行情规则推断，不等于确定上涨"
                    "bearish" -> "$dirWord ${"%.2f".format(speed)} 个百分点（$speedBasis），主力净额为负；仅为行情规则推断，不等于确定下跌"
                    "watch_up" -> "$dirWord ${"%.2f".format(speed)} 个百分点（$speedBasis），但证据不足，等待板块/消息确认"
                    "watch_down" -> "$dirWord ${"%.2f".format(speed)} 个百分点（$speedBasis），但证据不足，等待板块/消息确认"
                    else -> "缺少连续快照或资金证据"
                }
                // 相对强度：涨幅分位 45% + 量比分位 35% + 资金分位 20%，全程横截面，跨轮稳定
                val rel = 100.0 * (
                    0.45 * (pctRank[c.code] ?: 0.5) +
                        0.35 * (volRank[c.code] ?: 0.5) +
                        0.20 * (moneyRank[c.code] ?: 0.5)
                    )
                val event = _state.value.news.items.firstOrNull { item ->
                    val rawCodes = item["stocks"] ?: item["codes"] ?: item["affected_stocks"]
                    when (rawCodes) {
                        is List<*> -> rawCodes.any { value ->
                            val candidate = if (value is Map<*, *>) value["code"] ?: value["symbol"] else value
                            candidate?.toString()?.let { com.skapp.app.data.Code.digits6(it) } == c.code
                        }
                        else -> rawCodes?.toString()?.contains(c.code) == true
                    }
                }
                com.skapp.app.data.MarketPulseRow(
                    code = c.code,
                    name = c.name,
                    price = c.price,
                    pctChg = c.pct,
                    speed = speed,
                    speedSource = speedSource,
                    relStrength = rel,
                    streak = streak,
                    isNew = streak == 1,
                    volRatio = c.volRatio,
                    turnover = c.turnover,
                    mainNet = c.mainNet,
                    eventTitle = event?.get("title")?.toString(),
                    eventDirection = event?.get("direction")?.toString() ?: event?.get("sentiment")?.toString(),
                    eventReason = event?.get("positive_reason")?.toString()
                        ?: event?.get("negative_reason")?.toString()
                        ?: event?.get("reason")?.toString(),
                    evidenceTs = event?.get("time")?.toString() ?: event?.get("ts")?.toString(),
                    outlook = outlook,
                    outlookReason = reason,
                ) to (if (streak >= 2) rel else rel - 12.0) // 新进榜先扣 12 分，下一轮确认后再上主榜
            }
            val rows = ranked
                .sortedWith(
                    compareByDescending<Pair<com.skapp.app.data.MarketPulseRow, Double>> { it.second }
                        .thenByDescending { it.first.speed ?: Double.NEGATIVE_INFINITY }
                )
                .map { it.first }
                .take(30)
            val enriched = rows.mapIndexed { index, row ->
                if (index >= 15) row else {
                    val board = gap("板块数据(${row.code})", { emptyList() }) { DataCache.boards(row.code) }
                        .mapNotNull { it["name"]?.toString()?.takeIf { name -> name.isNotBlank() } }
                        .distinct().take(3).joinToString("、").ifBlank { "板块数据不足" }
                    row.copy(board = board)
                }
            }
            // 掉出榜：上一轮在榜、本轮已不在的票，取相对强度最高的 3 只，让用户看得到「谁掉队」
            val shownCodes = rows.map { it.code }.toSet()
            val dropped = prevRows.values
                .filter { it.code !in shownCodes }
                .sortedByDescending { it.relStrength ?: Double.NEGATIVE_INFINITY }
                .take(3)
                .map { "${it.name} ${it.code}" }
            _state.update { it.copy(
                marketPulse = enriched,
                marketPulseScope = scope,
                marketPulseTs = java.time.OffsetDateTime.now().toString(),
                pulseDropped = dropped,
            ) }
        } finally {
            _state.update { it.copy(marketPulseLoading = false) }
        }
    }

    // ───────────────── 本地盘前 / 午盘推股 ─────────────────

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
            // 先在全市场快照里取（零额外请求），快照没覆盖的才补拉
            val quotes = com.skapp.app.data.LiveQuote.quotes(codes)
            _state.update { it.copy(pickQuotes = HashMap(quotes)) }
        }
    }

    /** 全球 + A 股指数（东财 ulist，一次批量） */
    fun loadIndices() {
        viewModelScope.launch {
            val a = MarketDataClient.aShareIndices()
            val g = MarketDataClient.globalIndices()
            _state.update { it.copy(aShareIndices = a, globalIndices = g) }
        }
    }

    suspend fun refreshQuotes() {
        val quotes: Map<String, Quote> = gap("自选报价", { emptyMap<String, Quote>() }) {
            com.skapp.app.data.LiveQuote.quotes(Watchlist.codes)
        }
        val now = System.currentTimeMillis()
        val rows = Watchlist.codes.map { code ->
            val q = quotes[com.skapp.app.data.Code.normalize(code) ?: code]
            val quoteAge = q?.ts?.let { parseAge(it, now) }
            QuoteRow(
                code = code,
                display = com.skapp.app.data.Code.prefixed(code),
                name = q?.name?.takeIf { it.isNotBlank() } ?: nameOf(code) ?: code,
                price = q?.price,
                pctChg = q?.pctChg,
                stale = quoteAge == null || quoteAge > 10 * 60 * 1000,
            )
        }
        _state.update { it.copy(
            quotes = rows,
            quoteTs = quotes.values.firstOrNull()?.ts,
        ) }
    }

    /** 名称回退：先 names，再 symbols */
    private fun nameOf(code: String): String? {
        val n = com.skapp.app.data.Code.normalize(code) ?: return null
        return _state.value.symbols.firstOrNull {
            com.skapp.app.data.Code.normalize(it.code) == n
        }?.name
    }

    /** 搜索：代码或名称模糊匹配 */
    fun search(q: String) {
        val query = q.trim().lowercase()
        shared.searchQuery = q
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        val res = (_state.value.symbols + shared.stockNames.map { (code, name) -> SymbolEntry(code, name) })
            .asSequence()
            .mapNotNull { entry ->
                val code = com.skapp.app.data.Code.normalize(entry.code) ?: return@mapNotNull null
                SymbolEntry(code, entry.name?.takeIf { it.isNotBlank() } ?: shared.stockNames[code])
            }
            .distinctBy { it.code }
            .filter { s ->
                s.code.lowercase().contains(query) ||
                    (s.name?.lowercase()?.contains(query) == true)
            }
            .take(50)
            .toList()
        val fallback = if (res.isEmpty()) com.skapp.app.data.Code.directSymbol(query)?.let {
            listOf(it.copy(name = shared.stockNames[it.code]))
        } ?: emptyList() else emptyList()
        _state.update { it.copy(searchResults = res.ifEmpty { fallback }) }
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
        val normalized = com.skapp.app.data.Code.normalize(code) ?: code
        val same = _state.value.detail?.code == normalized &&
            _state.value.detailAdjust == adjust &&
            !_state.value.detailLoading && _state.value.holo != null
        if (same) return
        detailJob?.cancel()
        detailLiveJob?.cancel()
        detailGeneration.incrementAndGet()
        centralRevision.incrementAndGet()
        detailJob = viewModelScope.launch {
            val code = normalized
            _state.update { it.copy(
                detailLoading = true,
                detail = StockDetail(code = code, name = shared.stockNames[code]),
                detailAdjust = adjust,
                kline = emptyList(),
                detailDailyKline = emptyList(),
                kPeriod = KPeriod.DAY,
                holo = null,
                localEngine = null,
                centralEngineSummary = null,
                centralEngineScore = null,
                centralEngineResonance = null,
                centralEngineStatus = "insufficient",
                liveQuote = null,
                fundFlow = emptyList(),
                fundFlowLive = emptyList(),
                boardsOf = emptyList(),
                minuteTrend = emptyList(),
                minuteDate = null,
                minutePreClose = null,
                minuteSource = null,
                klineSource = null,
                keyLevels = null,
                recoup = null,
                exitPlan = null,
            ) }
            try {
                supervisorScope {
                    val holoD = async { requestResult { HoloClient.full(code) }.getOrNull() }
                    val quoteD = async {
                        val q = requestResult { DataCache.quote(code) }.getOrNull()
                        _state.update { it.copy(
                            detailLoading = false,
                            liveQuote = q,
                            detail = _state.value.detail?.copy(
                                name = q?.name?.takeIf { it.isNotBlank() } ?: _state.value.detail?.name,
                            ),
                        ) }
                    }
                    val cloudD = async {
                        val cloud = requestResult { DbTables.stockDetail(code) }.getOrNull()
                        if (cloud != null) {
                            val current = _state.value.detail
                            _state.update { it.copy(
                                detailLoading = false,
                                detail = cloud.copy(
                                    name = _state.value.liveQuote?.name?.takeIf { it.isNotBlank() }
                                        ?: cloud.name ?: current?.name,
                                    detail = cloud.detail ?: current?.detail,
                                ),
                            ) }
                        }
                    }
                    val boardsD = async {
                        var boards = requestResult { DataCache.boards(code) }.getOrDefault(emptyList())
                        if (boards.isEmpty()) {
                            // 东财 stock/get 常被拦：用云端板块成员反查（本地计算），避免“暂无数据”
                            val suffix = code.takeLast(6)
                            boards = _state.value.sectors.boardMembers.entries.mapNotNull { (bk, m) ->
                                val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val hit = (m["members"] as? List<*>).orEmpty().any { row ->
                                    ((row as? Map<*, *>)?.get("code")?.toString()?.takeLast(6)) == suffix
                                }
                                if (!hit) null else mapOf<String, Any?>(
                                    "name" to name, "code" to bk, "source" to "cloud_board_members",
                                )
                            }
                        }
                        _state.update { it.copy(boardsOf = boards) }
                    }
                    val minuteD = async {
                        // 盘后腾讯 minute/query 仍返回当日全量分时（实测 20:00 仍有 267 个 tick），
                        // 所以任何时段都请求一次；只有交易/竞价时段才由 detailLiveLoop 继续轮询。
                        val rich = requestResult { DataCache.minuteRich(code) }.getOrNull()
                        val fallbackPre = _state.value.liveQuote?.let { q ->
                            val p = q.price
                            val pc = q.pctChg
                            if (p != null && pc != null && pc > -100.0) p / (1.0 + pc / 100.0) else null
                        }
                        // 分钟 K 是独立源（新浪/腾讯），与上面分时曲线不是一路：失败时明确说是「接口拿不到」，
                        // 不能让人以为是这只票没有分钟数据
                        val mb = gap("分钟K", { emptyList() }) {
                            com.skapp.app.data.MinuteKline.fetch(
                                code, com.skapp.app.data.MinuteKline.Period.M5, 240,
                            )
                        }
                        _state.update { it.copy(
                            minuteTrend = rich?.points ?: emptyList(),
                            minuteDate = rich?.date,
                            minutePreClose = rich?.preClose ?: fallbackPre,
                            minuteSource = rich?.source?.takeIf { it != "none" },
                            minuteBars = mb,
                            minuteBarsPeriod = "5 分钟",
                            minuteBarsNote = if (mb.isEmpty()) {
                                "分钟 K 未取到：新浪/腾讯分钟接口不可达或被限流（不是「该股无分钟数据」）；上方分时曲线另有源"
                            } else null,
                        ) }
                    }
                    val historyD = async {
                        val holo = holoD.await()
                        _state.update { it.copy(holo = holo?.takeIf { it.covered }) }
                        // 只有「前复权」才允许用云端 holo 的固定口径 K 线；
                        // 切到后复权/不复权必须真去拉对应口径，否则会出现「点了后复权图没变」。
                        val holoBars = holo?.kline?.takeIf { it.size >= 30 && adjust == "qfq" }
                        val bars = holoBars
                            ?: requestResult { DataCache.kline(code, adjust, 250) }.getOrDefault(emptyList())
                        val keyLevels = gap("关键位", { null }) {
                            withContext(Dispatchers.Default) { com.skapp.app.data.KeyLevels.compute(bars) }
                        }
                        val recoup = gap("回本率", { null }) {
                            withContext(Dispatchers.Default) { com.skapp.app.data.RecoupCalc.compute(bars) }
                        }
                        val exitPlan = gap("退出纪律", { null }) {
                            withContext(Dispatchers.Default) { com.skapp.app.data.ExitPlan.compute(bars) }
                        }
                        val forecast = gap("多日趋势", { null }) {
                            withContext(Dispatchers.Default) { com.skapp.app.data.TrendForecast.compute(bars) }
                        }
                        _state.update { current -> if (current.detail?.code != code) current else current.copy(
                            kline = aggregateKline(bars, current.kPeriod), detailDailyKline = bars,
                            klineSource = if (holoBars != null) "云端holo；请求前复权，因子未独立核验"
                                else "DataCache/行情接口；具体单次来源未随数据返回",
                            keyLevels = keyLevels, recoup = recoup, exitPlan = exitPlan, trendForecast = forecast,
                        ) }
                        val engine = requestResult {
                            withContext(Dispatchers.Default) { DataCache.engine(code, adjust, bars) }
                        }.getOrNull()
                        _state.update { if (it.detail?.code == code) it.copy(localEngine = engine) else it }
                        refreshDetailCentral()
                        val local = requestResult {
                            bundleFromLocal(code, bars, _state.value.liveQuote, holo)
                        }.getOrNull()
                        val current = _state.value.detail
                        _state.update { it.copy(detail = current?.copy(
                            name = current.name ?: holo?.holo?.get("name")?.toString()?.takeIf { it.isNotBlank() },
                            detail = current.detail ?: local,
                        )) }
                    }
                    val flowD = async {
                        val holo = holoD.await()
                        val direct = requestResult { DataCache.fundFlow(code, 60) }.getOrDefault(emptyList())
                        val flow = direct.ifEmpty { holo?.moneyflow.orEmpty() }
                        val live = if (MarketHours.isTrading() || MarketHours.isAuction()) {
                            requestResult { MarketDataClient.fundFlowIntraday(code) }.getOrDefault(emptyList())
                        } else emptyList()
                        _state.update { if (it.detail?.code == code) it.copy(fundFlow = flow, fundFlowLive = live) else it }
                        refreshDetailCentral()
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
                if (isActive) _state.update { it.copy(detailLoading = false) }
            }
        }
    }

    /** 盘中详情实时刷新：SSE 秒级推送为主，15秒轮询做兜底。 */
    private fun startDetailLiveLoop(code: String) {
        detailLiveJob?.cancel()
        detailLiveJob = viewModelScope.launch {
            launch {
                runCatching {
                    MarketDataClient.sseQuote(code).collect { quote ->
                        if (_state.value.detail?.code != code) return@collect
                        lastSseEventAt = System.currentTimeMillis()
                        applyLiveQuote(quote)
                    }
                }
            }
            while (isActive) {
                delay(15_000)
                if (!MarketHours.isTrading() && !MarketHours.isAuction()) break
                if (_state.value.detail?.code != code) break
                // SSE 正常时不重复拉 HTTP；超过10秒没收到推送才降级轮询
                if (System.currentTimeMillis() - lastSseEventAt < 10_000) continue
                runCatching { refreshDetailLiveNow(code) }
            }
        }
    }

    /** SSE 推送落盘：只更新最后一根K和报价，引擎重算限流 5 秒一次。 */
    private suspend fun applyLiveQuote(quote: Quote) {
        val now = System.currentTimeMillis()
        val bars = _state.value.detailDailyKline
        val today = java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString()
        val price = quote.price
        val updated = if (bars.isEmpty() || price == null) bars else {
            val last = bars.last()
            if (last.date == today) {
                bars.dropLast(1) + last.copy(
                    close = price,
                    high = maxOf(last.high, price),
                    low = minOf(last.low, price),
                )
            } else {
                bars + KBar(date = today, open = price, close = price, high = price, low = price, vol = 0.0)
            }
        }
        _state.update { if (it.detail?.code == quote.code) it.copy(liveQuote = quote, detailDailyKline = updated, kline = aggregateKline(updated, it.kPeriod)) else it }
        if (now - lastEngineAt > 5_000 && updated.size >= 30) {
            lastEngineAt = now
            val engine = withContext(Dispatchers.Default) { LocalEngine.analyze(updated) }
            _state.update { if (it.detail?.code == quote.code) it.copy(localEngine = engine) else it }
            refreshDetailCentral()
        }
    }

    suspend fun refreshDetailCentral() {
        val captured = _state.value
        val code = captured.detail?.code ?: return
        val revision = centralRevision.incrementAndGet()
        val generation = detailGeneration.get()
        val nowMs = System.currentTimeMillis()
        val input = requestResult {
            withContext(Dispatchers.IO) { DbTables.stockAnalysisInputs(code, captured.detailDailyKline, nowMs) }
        }.getOrElse {
            com.skapp.app.data.StockAnalysisInputs(code, bars = captured.detailDailyKline,
                missing = listOf("detail_input_unavailable"))
        }
        if (centralRevision.get() != revision || detailGeneration.get() != generation ||
            _state.value.detail?.code != code || _state.value.detailAdjust != captured.detailAdjust) return
        val analysisBars = input.bars
        val chain = if (analysisBars.size >= 30) requestResult {
            com.skapp.app.data.CentralAnalysis.analyze(code, analysisBars, captured.fundFlow,
                captured.newsCalcs, com.skapp.app.data.CentralAnalysis.newsAvailable(captured.news),
                liveFlow = captured.fundFlowLive, minute = captured.minuteTrend, minuteDate = captured.minuteDate)
        }.getOrNull() else null
        if (centralRevision.get() != revision || _state.value.detail?.code != code) return
        val (verifiedEngine, levels, exit) = withContext(Dispatchers.Default) {
            Triple(LocalEngine.analyze(analysisBars), com.skapp.app.data.KeyLevels.compute(analysisBars),
                com.skapp.app.data.ExitPlan.compute(analysisBars))
        }
        val central = centralEngine(verifiedEngine, chain)
        val quote = captured.liveQuote?.takeIf { it.code == code }
        val decision = com.skapp.app.data.TradingDecision.evaluate(
            chain = chain, code = code, price = quote?.price, pctChg = quote?.pctChg,
            quoteAtMs = com.skapp.app.data.CentralAnalysis.timeOf(quote?.ts),
            quoteSource = quote?.let { "MarketDataClient/LiveQuote（源时点随报价）" },
            bars = analysisBars, evidence = com.skapp.app.data.TradingDecision.evidenceOf(input, System.currentTimeMillis()),
            levels = levels, exit = exit,
            nowMs = System.currentTimeMillis(),
        )
        _state.update { current ->
            if (current.detail?.code != code || centralRevision.get() != revision || detailGeneration.get() != generation ||
                current.detailDailyKline != captured.detailDailyKline || current.liveQuote != captured.liveQuote ||
                current.detailAdjust != captured.detailAdjust) current else current.copy(
                centralEngineSummary = decision.summary() + "\n" + central.summary, centralEngineScore = central.score,
                centralEngineResonance = central.resonance, centralEngineStatus = central.status,
                canonicalScore = chain?.canonicalScore, canonicalResonance = chain?.canonical?.resonance,
                engineSpecVersion = chain?.specVersion, engineSpecDrift = chain?.specDrift ?: false,
                chainDegraded = chain?.degraded ?: listOf("完整多输入规格链路"), chainStatus = chain?.status ?: "insufficient",
            )
        }
    }

    fun refreshDetailLive() {
        val code = _state.value.detail?.code ?: return
        viewModelScope.launch { runCatching { refreshDetailLiveNow(code) } }
    }

    private suspend fun refreshDetailLiveNow(code: String) {
        val generation = detailGeneration.get()
        val adjust = _state.value.detailAdjust
        // 报价拿不到就别动已上屏的详情（否则旧价会被当成现价），但缺口必须留下
        val quote = gap("最新报价($code)", { null }) { MarketDataClient.quote(code) } ?: return
        val minuteRich = gap("分时($code)", { null }) { MarketDataClient.minuteTrendRich(code) }
        val minute = minuteRich?.points.orEmpty()
        // 东财/腾讯日K本身包含今日未收盘实时K；拉不到时才用分时+实时价合成。
        val fetched = gap("日K($code)", { emptyList() }) {
            MarketDataClient.dayKline(code, 250, adjust)
        }
        val base = fetched.ifEmpty { _state.value.detailDailyKline }
        val bars = mergeLiveBar(base, quote, minute)
        val engine = if (bars.size >= 30) gap("本地引擎($code)", { null }) { LocalEngine.analyze(bars) } else null
        val liveFlow = if (MarketHours.isTrading() || MarketHours.isAuction()) {
            gap("资金流($code)", { emptyList() }) { MarketDataClient.fundFlowIntraday(code) }
        } else emptyList()
        _state.update { if (it.detail?.code != code || it.detailAdjust != adjust || detailGeneration.get() != generation) it else it.copy(
            liveQuote = quote,
            detailDailyKline = bars,
            kline = aggregateKline(bars, it.kPeriod),
            minuteTrend = minute.ifEmpty { it.minuteTrend },
            minuteDate = minuteRich?.date ?: it.minuteDate,
            fundFlowLive = liveFlow.ifEmpty { it.fundFlowLive },
            localEngine = engine ?: it.localEngine,
        ) }
        if (_state.value.detail?.code == code && _state.value.detailAdjust == adjust && detailGeneration.get() == generation) refreshDetailCentral()
    }

    /** 用当日分时与实时价补齐/替换今天这根日K，避免日K停在历史收盘。 */
    private fun mergeLiveBar(bars: List<KBar>, quote: Quote, minute: List<Map<String, Any?>>): List<KBar> {
        val price = quote.price ?: return bars
        val today = java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString()
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
        _state.update { it.copy(kPeriod = p, kline = aggregateKline(it.detailDailyKline, p)) }
    }

    private data class CentralEngine(
        val summary: String,
        val score: Double?,
        val resonance: Double?,
        val status: String,
    )

    /**
     * 中枢引擎展示口径：**优先规格聚合分**（[EngineChain] canonical，与云端 engine_v5 同构），
     * 旧12引擎仅供技术对照，不能替代最终审核。
     *
     * 两个分数长得一样、名字都叫「中枢」，混着显示过一次就再也查不清某天推荐用的是谁。
     */
    private fun centralEngine(
        engine: LocalEngineResult,
        chain: com.skapp.app.data.EngineChain.ChainResult? = null,
    ): CentralEngine {
        val valid = engine.engines.filter { it.score != null }
        val canonical = chain?.canonicalScore
        val score = canonical
        if (valid.isEmpty() || score == null) {
            return CentralEngine("中枢规格链路数据不足；旧12引擎结果只作技术对照，不替代最终审核。", null, null, "insufficient")
        }
        val resonance = chain?.canonical?.resonance ?: engine.resonanceRatio
        val summary = when {
            canonical != null -> buildString {
                append("中枢引擎（规格口径 V${chain?.specVersion ?: "?"}）：规格分 ")
                append("%.1f".format(canonical))
                append("，共振 %.0f%%".format((resonance ?: 0.0) * 100))
                append("，票数 多 ${chain?.canonical?.bull ?: 0}/空 ${chain?.canonical?.bear ?: 0}/中 ${chain?.canonical?.neutral ?: 0}")
                append("，外部计分票 ${chain?.votes?.size ?: 0} 张")
                append("；权重 ${chain?.weightsSource ?: "未知"}")
                append("；消息审核 ${chain?.news?.usable ?: 0}/${chain?.news?.matched ?: 0} 条（来源/时间/个股匹配过检）")
                chain?.provenance?.takeIf { it.isNotEmpty() }?.let { append("；输入说明：").append(it.joinToString("；")) }
                val bad = chain?.degraded.orEmpty()
                if (bad.isNotEmpty()) append("；降级 ").append(bad.joinToString("、"))
                if (chain?.specDrift == true) append("；**规格漂移**")
                chain?.riskFlag?.let { append("；风控：").append(it) }
            }
            else -> engine.summary ?: "中枢引擎（老口径 12 引擎）：综合 ${"%.1f".format(score)}，共振 ${"%.0f%%".format((resonance ?: 0.0) * 100)}，有效 ${valid.size}/${engine.engines.size}。"
        }
        return CentralEngine(summary, score, resonance, chain?.status ?: engine.status)
    }

    /** 数据源台账刷新（诊断页用） */
    fun loadDataSources() {
        viewModelScope.launch {
            val snap = gap("数据源台账", { com.skapp.app.data.DataSourcesSnapshot() }) { DbTables.dataSources() }
            _state.update { it.copy(dataSources = snap) }
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
                ?: MarketDataClient.financeQuote(code).takeIf { it.isNotEmpty() }
                ?: gap("财务快照($code)", { emptyMap<String, Any?>() }) { DbTables.financial(code) } ?: emptyMap(),
            // 财报后漂移只展示不评分：云端事件表按公告日倒序最近 90 天，按带前缀代码查
            pead = gap("财报事件", { null }) {
                com.skapp.app.data.PeadEvents.parse(com.skapp.app.data.Db.peadEvents())
                    ?.events?.get(com.skapp.app.data.Code.prefixed(code))
            },
        )
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailJob = null
        detailLiveJob?.cancel()
        detailLiveJob = null
        _state.update { it.copy(detail = null, detailLoading = false, kline = emptyList()) }
    }

    /** ViewModel 销毁时停掉行情循环（其余 job 随 viewModelScope 一起结束，行为与拆分前一致） */
    fun cancelQuoteLoop() {
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