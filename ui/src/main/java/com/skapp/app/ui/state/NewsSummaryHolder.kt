package com.skapp.app.ui.state


import androidx.lifecycle.ViewModel
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
import com.skapp.app.ui.QuoteRow
import com.skapp.app.ui.UiState
import com.skapp.app.ui.Watchlist

/** 消息摘要 / 看板 / 多池 / 本地盘后分析 / 实时总结 */
internal class NewsSummaryHolder(
    shared: SharedVm,
    private val market: MarketStateHolder,
    private val picks: LocalPicksHolder,
) : StateHolder(shared) {

    private var newsJob: Job? = null

    private var boardJob: Job? = null

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

    /** 快讯 + 主线：逐条标题下实时算（标题命中的股票 → 实时行情 + 中枢引擎） */
    fun loadNewsCalcs(): Job = viewModelScope.launch {
        requestResult { refreshNewsCalcs() }
    }

    suspend fun refreshNewsCalcs() {
        _state.value = _state.value.copy(newsCalcLoading = true)
        try {
            val symbols = _state.value.symbols
            val items = ArrayList<Map<String, Any?>>()
            // 本地多源：东财、爱股票、金融界，各源失败隔离并保留健康状态。
            val local = requestResult { MarketDataClient.localNews(30) }.getOrDefault(LocalNewsResult())
            items.addAll(local.items)
            // 云端新闻数据库：分析字段和跨源映射只作补充，不覆盖本地新消息。
            val stored = requestResult { DbTables.newsImpact() }.getOrDefault(emptyList())
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
            // 板块上下文：消息分析需要结合板块动量与板块内个股
            val sec = _state.value.sectors
            val boardList = (sec.industries + sec.concepts)
                .mapNotNull { b -> b.name?.takeIf { it.isNotBlank() }?.let { it to b.chg1d } }
            val boardMembers = sec.boardMembers.mapNotNull { (_, m) ->
                val name = (m["name"] as? String) ?: return@mapNotNull null
                val members = (m["members"] as? List<*>).orEmpty()
                    .mapNotNull { row -> (row as? Map<*, *>)?.get("code")?.toString() }
                if (members.isEmpty()) null else name to members
            }.toMap()
            val calcs = NewsCalcEngine.compute(merged, symbols, boards = boardList, boardMembers = boardMembers).map { calc ->
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
            // 即采即传：不管开 App 是几点，算好的消息都进归档队列；flush 由启动/定时触发
            picks.archiveNewsCalcs(calcs)
            // 消息变了早盘名单也要跟着变（09:00-09:30 可变，冻结后内部直接跳过）
            runCatching { picks.refreshPremarketDraft() }
        } finally {
            _state.value = _state.value.copy(newsCalcLoading = false)
        }
    }

    /** 本地实时看板：全市场快照 × 板块映射现算，涨跌榜/涨停池/板块热度都是当下的。 */
    suspend fun localBoardMap(force: Boolean = false): Map<String, Any?> {
        val snap = com.skapp.app.data.LiveMarket.snapshot(force = force)
        if (snap.isEmpty()) return localBoardUnavailable()
        val pulse = com.skapp.app.data.LivePulse.of(snap)
        val res = runCatching { com.skapp.app.data.LocalSectors.compute() }.getOrNull()
        return com.skapp.app.data.LocalSectors.boardMap(res, pulse, snap)
    }

    fun localBoardUnavailable(): Map<String, Any?> = mapOf(
        "status" to "insufficient",
        "source" to "local",
        "reason" to "全市场实时行情不可达（未使用云端归档冒充当日看板）",
    )

    /** 多池选股：库里池子名单 + 本地实时补价。force=true 时忽略间隔强制重拉。 */
    fun loadPools(force: Boolean = true) {
        val last = _state.value.poolsLoadedAt
        val now = System.currentTimeMillis()
        if (!force && now - last < 60_000L) return
        viewModelScope.launch {
            _state.value = _state.value.copy(poolsLoading = true)
            val ps = runCatching { DbTables.pools() }.getOrElse { emptyList() }
            val enriched = runCatching { DbTables.repricePools(ps) }.getOrDefault(ps)
            _state.value = _state.value.copy(
                poolsLoading = false,
                // 抓失败（enriched 为空）时不要用空表覆盖已算过的池：
                // 名单的池加权已经生效，界面标签却会因此集体消失，看起来像「算法没加权」。
                pools = enriched.ifEmpty { _state.value.pools },
                poolsLoadedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 首屏先用云端快照把页面渲染出来，随后立刻用设备直连行情覆盖池子价格（不阻塞首屏）。
     *  与 loadPools 共用同一实现 SiteBoardClient.repriceLive，不再另写一份补价逻辑。 */
    fun refreshPoolReprice() {
        viewModelScope.launch {
            val cur = _state.value.pools
            if (cur.isEmpty()) return@launch
            val live = runCatching { DbTables.repricePools(cur) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(pools = live)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 本地计算：盘后总结 + 推股胜率（云端只给原料，结论本地算）
    // ─────────────────────────────────────────────────────────────
    fun refreshLocalAnalytics() {
        viewModelScope.launch {
            runCatching { computeLocalCloseReview() }
            runCatching { computeLocalWinrate() }
        }
    }

    private fun analyticsCodes(limit: Int): List<String> =
        (_state.value.picks.signals.map { it.code } + _state.value.screened.map { it.code })
            .filter { it.length == 6 }.distinct().take(limit)

    /** 盘后总结：推股池当日实际涨跌（本地 K 线计算）。 */
    private suspend fun computeLocalCloseReview() {
        val codes = analyticsCodes(20)
        if (codes.isEmpty()) return
        val today = java.time.LocalDate.now().toString()
        val pcts = ArrayList<Pair<String, Double>>()
        for (code in codes) {
            val bars = runCatching { DataCache.kline(code, "qfq", 10) }.getOrNull() ?: continue
            if (bars.size < 2) continue
            val last = bars.last()
            val prev = bars[bars.size - 2]
            if (prev.close <= 0.0) continue
            if (last.date.take(10) != today) continue   // 只用当日收盘/实时 K
            pcts.add(code to (last.close / prev.close - 1.0) * 100.0)
        }
        if (pcts.isEmpty()) return
        val wins = pcts.count { it.second > 0 }
        val avg = pcts.map { it.second }.average()
        val best = pcts.maxByOrNull { it.second }
        val worst = pcts.minByOrNull { it.second }
        _state.value = _state.value.copy(
            closeReview = com.skapp.app.data.CloseReview(
                generatedAt = java.time.OffsetDateTime.now().toString(),
                date = today,
                status = "ok",
                reason = "本地计算：推股池当日表现（本地日K）",
                stages = mapOf(
                    "本地推股表现" to mapOf(
                        "summary" to mapOf(
                            "n" to pcts.size,
                            "hit_rate_pct" to "%.1f".format(wins * 100.0 / pcts.size),
                            "avg_pct" to "%.2f".format(avg),
                            "best" to (best?.let { "${it.first} ${"%.2f".format(it.second)}%" } ?: "暂无数据"),
                            "worst" to (worst?.let { "${it.first} ${"%.2f".format(it.second)}%" } ?: "暂无数据"),
                            "source" to "local_kline",
                        )
                    )
                ),
            )
        )
    }

    /** 推股胜率：本地回测（MA5 上穿 MA20 信号，统计未来 5/10/20 日胜率）。 */
    private suspend fun computeLocalWinrate() {
        val codes = analyticsCodes(12)
        if (codes.isEmpty()) return
        val horizons = listOf(5, 10, 20)
        val buckets = horizons.associateWith { ArrayList<Double>() }
        var signals = 0
        for (code in codes) {
            val bars = runCatching { DataCache.kline(code, "qfq", 250) }.getOrNull() ?: continue
            if (bars.size < 60) continue
            val closes = bars.map { it.close }
            for (i in 20 until closes.size) {
                val ma5 = closes.subList(i - 4, i + 1).average()
                val ma5p = closes.subList(i - 5, i).average()
                val ma20 = closes.subList(i - 19, i + 1).average()
                val ma20p = closes.subList(i - 20, i).average()
                if (ma5p <= ma20p && ma5 > ma20) {
                    signals++
                    for (h in horizons) {
                        val fwd = closes.getOrNull(i + h) ?: continue
                        buckets.getValue(h).add((fwd / closes[i] - 1.0) * 100.0)
                    }
                }
            }
        }
        if (signals == 0) return
        val horizonsMap = horizons.associate { h ->
            val list = buckets.getValue(h)
            h.toString() to mapOf<String, Any?>(
                "overall" to if (list.isEmpty()) null else "%.1f".format(list.count { it > 0 } * 100.0 / list.size),
                "avg_ret_pct" to if (list.isEmpty()) null else "%.2f".format(list.average()),
                "n" to list.size,
            )
        }
        val today = java.time.LocalDate.now().toString()
        _state.value = _state.value.copy(
            winrateMatrix = com.skapp.app.data.WinrateMatrix(
                ts = java.time.OffsetDateTime.now().toString(),
                sourceTs = today,
                source = "本地回测：MA5 上穿 MA20（样本 ${codes.size} 只 / 信号 $signals 次）",
                horizons = horizonsMap,
            )
        )
    }

    /** 看板 */
    fun loadBoard(force: Boolean = false) {
        // 去重：启动预热与页面 LaunchedEffect 会各叫一次，同一份数据没必要拉两遍；
        // 手动刷新必须真重拉：先取消在途任务，否则会被旧任务挡住拿不到新数据
        if (force) boardJob?.cancel()
        else if (boardJob?.isActive == true) return
        boardJob = viewModelScope.launch {
            // 看板与宽度都走本地实时：全市场快照 × 板块映射现算，不再读云端 latest.json
            val boardD = async { runCatching { localBoardMap(force) }.getOrDefault(localBoardUnavailable()) }
            val breadthD = async {
                runCatching {
                    com.skapp.app.data.LocalSectors.breadth(
                        com.skapp.app.data.LivePulse.of(com.skapp.app.data.LiveMarket.snapshot()),
                    )
                }.getOrDefault(mapOf("status" to "unavailable", "source" to "local"))
            }
            _state.value = _state.value.copy(board = boardD.await(), marketBreadth = breadthD.await())
        }
    }

    /** 手动刷新实时总结：正文、消息异动和本地推股统一由一个入口触发。 */
    fun refreshRealtimeSummary(mode: String = "score") {
        if (_state.value.summaryRefreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                summaryRefreshing = true,
                summaryRefreshMessage = "正在刷新盘面、消息与结构化推股…",
            )
            var message = "刷新已取消"
            var fastOk = 0
            var fastTotal = 0
            try {
                // ── 快通道：盘面 / 消息 / 盘后 / 胜率（秒级~数秒）──
                // 本地全市场深算（几十秒~数分钟）故意拆到后面，不阻塞这里，
                // 否则用户看到的永远是长时间转圈 / 整次刷新“失败”。
                val refreshed = supervisorScope {
                    val summaryD = async { requestResult { refreshSummary() } }
                    val newsD = async { requestResult { refreshNewsCalcs() } }
                    val closeD = async { requestResult { DbTables.closeReview() } }
                    val winrateD = async { requestResult { DbTables.winrate() } }
                    listOf(
                        "盘面" to summaryD.await(),
                        "消息" to newsD.await(),
                        "盘后总结" to closeD.await(),
                        "胜率" to winrateD.await(),
                    )
                }
                val close = refreshed[2].second.getOrNull() as? com.skapp.app.data.CloseReview
                val winrate = refreshed[3].second.getOrNull() as? com.skapp.app.data.WinrateMatrix
                _state.value = _state.value.copy(
                    closeReview = close ?: _state.value.closeReview,
                    winrateMatrix = winrate ?: _state.value.winrateMatrix,
                )
                fastTotal = refreshed.size
                fastOk = refreshed.count { it.second.isSuccess }
                val failures = refreshed.filter { it.second.isFailure }
                message = if (failures.isEmpty()) "刷新完成（$fastOk/$fastTotal 项就绪），本地全市场深算进行中…" else {
                    val details = failures.joinToString("；") { (label, result) ->
                        "$label：${result.exceptionOrNull()?.message ?: "数据源不可达"}"
                    }
                    val prefix = if (failures.size == refreshed.size) "刷新失败" else "部分刷新完成"
                    "$prefix（$fastOk/$fastTotal）；$details"
                }
                // 快通道结果先放开 UI 转圈，让用户立即看到数据
                _state.value = _state.value.copy(summaryRefreshing = false, summaryRefreshMessage = message)

                // ── 慢通道：本地全市场实时选股（不阻塞上面的展示）──
                if (!_state.value.screening) {
                    val screenOk = runCatching { picks.refreshScreen(mode) }.isSuccess
                    message = message + if (screenOk) "；本地深算完成：${_state.value.screened.size} 个候选"
                    else "；本地深算失败：${_state.value.screenMsg ?: "行情接口不可达"}"
                }
                // 盘前/午盘本地推股：已有当日存档就复用，不重复拉 11 页快照
                com.skapp.app.data.LocalPicks.sessionFor(MarketHours.phase())?.let { session ->
                    runCatching { picks.rebuildLocalPicks(session, force = false) }
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

    suspend fun refreshSummary() {
        val cloud = requestResult { DbTables.summary() }.getOrNull()
        val cloudOk = cloud != null && cloud["status"]?.toString() !in setOf("insufficient", "unknown", "error")
        if (cloudOk) {
            _state.value = _state.value.copy(summary = cloud!!)
            return
        }
        // 云端产物不可达/过期时，用本地实时行情、消息与本地深算生成总结。
        // 关键：marketPulse 过去只在交易/竞价时段刷新，导致盘后总结永远 insufficient、
        // 用户点刷新一直“失败”。这里在构建总结前补拉一次全市场快照（盘后也能拿到）。
        if (_state.value.marketPulse.isEmpty()) {
            runCatching { market.refreshMarketPulse() }
        }
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
}
