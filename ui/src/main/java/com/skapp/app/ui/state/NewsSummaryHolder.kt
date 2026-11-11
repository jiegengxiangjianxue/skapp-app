package com.skapp.app.ui.state


import androidx.lifecycle.viewModelScope
import com.skapp.app.data.DbTables
import com.skapp.app.data.DataCache
import com.skapp.app.data.NewsCalcEngine
import com.skapp.app.data.LocalNewsResult
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.MarketHours
import com.skapp.app.data.SiteBoardClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** 消息摘要 / 看板 / 多池 / 本地盘后分析 / 实时总结 */
internal class NewsSummaryHolder(
    shared: SharedVm,
    private val market: MarketStateHolder,
    private val picks: LocalPicksHolder,
) : StateHolder(shared) {

    private var newsJob: Job? = null

    private var boardJob: Job? = null
    private var summaryJob: Job? = null

    /** 本地消息刷新：交易时段约30秒，非交易时段约3分钟；云端快照只作低频补偿。 */
    fun startNewsLoop() {
        newsJob?.cancel()
        newsJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.newsCalcLoading) {
                    delay(5_000)
                    continue
                }
                loadNewsCalcs().join()
                loadSummary().join()
                val wait = if (MarketHours.isTrading() || MarketHours.isAuction()) 30_000L else 180_000L
                delay(wait)
            }
        }
    }

    /** 快讯 + 主线：逐条标题下实时算（标题命中的股票 → 实时行情 + 中枢引擎） */
    fun loadNewsCalcs(): Job = shared.sections.load("消息事件", timeoutMs = 45_000,
        fetch = { refreshNewsCalcs() }) { s, _ -> s }


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
            com.skapp.app.data.CentralAnalysis.recordNews(_state.value.news, symbols)
            viewModelScope.launch { market.refreshDetailCentral() }
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
            market.refreshDetailCentral()
            // 即采即传：不管开 App 是几点，算好的消息都进归档队列；flush 由启动/定时触发
            picks.archiveNewsCalcs(calcs)
            // 消息变了早盘名单也要跟着变（09:00-09:30 可变，冻结后内部直接跳过）
            runCatching { picks.refreshPremarketDraft() }
        } finally {
            _state.value = _state.value.copy(newsCalcLoading = false)
        }
    }

    /** 本地看板保留行情源时点；休市完整同日行情仅用于历史复盘。 */
    suspend fun localBoardMap(force: Boolean = false): Map<String, Any?> {
        val snap = com.skapp.app.data.LiveMarket.snapshot(force = force)
        if (snap.isEmpty()) return localBoardUnavailable()
        val now = System.currentTimeMillis()
        val live = snap.all { now - it.ts in 0..300_000L }
        val coverage = com.skapp.app.data.LiveMarket.completeCoverage(snap)
        val sourceDate = WorkbenchSummary.historicalSourceDate(snap.map { it.ts }, now)
        val clock = java.util.Date(now)
        val historical = !live && sourceDate != null && coverage && snap.size >= 3000 &&
            !MarketHours.isTrading(clock) && !MarketHours.isAuction(clock)
        if (!live && !historical) return localBoardUnavailable() + mapOf(
            "reason" to "行情源时间未知或已过期；抓取成功不等于实时行情。",
            "sampleSize" to snap.size,
            "unknownTimeCount" to snap.count { it.ts <= 0L },
            "staleCount" to snap.count { it.ts > 0L && now - it.ts !in 0..300_000L },
        )
        val pulse = com.skapp.app.data.LivePulse.of(snap)
        val mapping = requestResult { com.skapp.app.data.SectorLink.load() }.getOrNull()
        val res = mapping?.let { board ->
            com.skapp.app.data.LocalSectors.aggregate(snap, sectorOf = { board.sectorNameOf(it) })
        }
        val board = com.skapp.app.data.LocalSectors.boardMap(res, pulse, snap)
        val market = if (res == null) mapOf(
            "market" to mapOf(
                "涨" to pulse.up, "跌" to pulse.down, "平" to pulse.flat,
                "涨停" to pulse.limitUp, "跌停" to pulse.limitDown,
                "中位涨幅" to pulse.medianPct,
                "成交额(亿)" to pulse.totalAmount?.let { it / 1e8 },
                "主力净额(亿)" to pulse.mainNetTotal?.let { it / 1e8 },
            ),
            "sectors" to emptyList<Map<String, Any?>>(),
            "reason" to "板块映射未就绪；市场统计与板块主线分别核验",
            "status" to if (coverage && snap.size >= 3000) "ok" else "insufficient",
        ) else emptyMap()
        return board + market + mapOf(
            "ts" to java.time.Instant.ofEpochMilli(snap.minOf { it.ts }).toString(),
            "coverageVerified" to coverage,
            "snapshotMode" to if (historical) "historical" else "live",
            "sourceDate" to sourceDate,
            "minTs" to snap.minOf { it.ts },
            "maxTs" to snap.maxOf { it.ts },
            "sampleSize" to snap.size,
            "expectedTotal" to snap.mapNotNull { it.expectedTotal }.distinct().singleOrNull(),
        ) + if (historical) mapOf(
            "status" to "historical",
            "reason" to "$sourceDate 历史行情复盘，非实时、不用于当前推荐",
        ) else emptyMap()
    }

    fun localBoardUnavailable(): Map<String, Any?> = mapOf(
        "status" to "insufficient",
        "source" to "local",
        "reason" to "全市场实时行情不可达（未使用云端归档冒充当日看板）",
    )

    /** 多池选股：库里池子名单 + 本地实时补价。force=true 时忽略间隔强制重拉。 */
    fun loadPools(force: Boolean = true) {
        if (_state.value.poolsLoading) return
        val last = _state.value.poolsLoadedAt
        val now = System.currentTimeMillis()
        if (!force && now - last < 60_000L) return
        _state.value = _state.value.copy(poolsLoading = true)
        viewModelScope.launch {
            try {
                val ps = requestResult { DbTables.pools() }.getOrDefault(emptyList())
                val enriched = requestResult { DbTables.repricePools(ps) }.getOrDefault(ps)
                _state.value = _state.value.copy(
                    pools = enriched.ifEmpty { _state.value.pools },
                    poolsLoadedAt = if (enriched.isNotEmpty()) System.currentTimeMillis() else _state.value.poolsLoadedAt,
                    dataGaps = if (ps.isEmpty() || ps.all { it.status == "insufficient" })
                        _state.value.dataGaps + ("多池" to "来源不可达或没有可用池，保留的旧名单仅供观察")
                    else _state.value.dataGaps - "多池",
                )
            } finally {
                _state.value = _state.value.copy(poolsLoading = false)
            }
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
    /** 看板 */
    fun loadBoard(force: Boolean = false) {
        shared.sections.load("看板", force = force, fetch = { localBoardMap(force) }) { s, v ->
            WorkbenchSummary.applyBoard(s, v)
        }
    }

    /** 手动刷新实时总结：正文、消息异动和本地推股统一由一个入口触发。 */
    fun refreshRealtimeSummary(mode: String = "score") {
        if (summaryJob?.isActive == true) return
        summaryJob = viewModelScope.launch {
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
                    val summaryD = async { requestResult {
                        val board = localBoardMap(true)
                        _state.update { WorkbenchSummary.applyBoard(it, board) }
                        check(board["status"] == "ok" || WorkbenchSummary.historicalBoard(board, System.currentTimeMillis())) {
                            board["reason"]?.toString() ?: "全市场快照不足"
                        }
                    } }
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
                    summary = WorkbenchSummary.asMap(_state.value),
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

                val session = com.skapp.app.data.LocalPicks.sessionAt(System.currentTimeMillis(), !MarketHours.isClosedDay())
                if (session != null) {
                    picks.refreshLocalPicks(session)
                    message += "；已请求当前时段强制重算，执行状态见候选区。"
                } else {
                    message += "；当前不在可更新时段，保留冻结推股记录。"
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
    fun loadSummary(): Job = shared.sections.load("市场解读", fetch = {
        localBoardMap(false)
    }) { s, board -> WorkbenchSummary.applyBoard(s, board) }

    suspend fun refreshSummary() {
        val board = localBoardMap(true)
        _state.update { WorkbenchSummary.applyBoard(it, board) }
        val local = _state.value.summary
        if (local["status"]?.toString() != "ok" && !WorkbenchSummary.historicalBoard(board, System.currentTimeMillis())) {
            throw IllegalStateException(
                local["reason"]?.toString()
                    ?: "暂无盘面数据"
            )
        }
    }

    // ── GitCode AI ─────────────────────────────────────
}