package com.skapp.app.ui.state


import android.content.Context
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.DbTables
import com.skapp.app.data.NewsArchive
import com.skapp.app.data.IntradayPicks
import com.skapp.app.data.toPlainMap
import com.skapp.app.data.PicksArchiveQueue
import com.skapp.app.data.GitHubRepoWriter
import com.skapp.app.data.NewsCalc
import com.skapp.app.data.LocalScreener
import com.skapp.app.data.StructuredPickArtifact
import com.skapp.app.data.MarketHours
import com.skapp.app.data.PickSignal
import com.skapp.app.data.TodayPicks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.skapp.app.ui.Watchlist

/** 视为「当天已算完」的产物状态：ok=有票进名单，defensive=数据齐但无票（弱市，设计内结果）。 */
private val CACHED_PICK_STATUS = setOf("ok", "defensive")

/** 本地推股全链路：深算 / 当日存档 / 早盘草稿 / 归档队列 / 实时选股 */
internal class LocalPicksHolder(
    shared: SharedVm,
    private val market: MarketStateHolder,
) : StateHolder(shared) {

    private var picksJob: Job? = null

    private val sessionRequests = LatestRequestRunner<com.skapp.app.data.LocalPicks.Session>(viewModelScope) { session ->
        try {
            kotlinx.coroutines.withTimeout(90_000) { rebuildLocalPicks(session, force = true) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _state.value = _state.value.copy(localPicksLoading = false, localPicksMsg = "筛选超时，已保留上一轮记录；可重试。")
        }
    }

    /** 当前可用的本地推股产物：午盘优先（更近），其次盘前；都不是今天则视为没有。 */
    fun localPicksArtifact(): StructuredPickArtifact? {
        val session = com.skapp.app.data.LocalPicks.sessionAt(System.currentTimeMillis(), !MarketHours.isClosedDay())
            ?: return null
        return picksFor(session).takeIf { it.date == MarketHours.todayKey() }
    }

    fun ensureLocalPicks() {
        val session = com.skapp.app.data.LocalPicks.sessionAt(System.currentTimeMillis(), !MarketHours.isClosedDay())
            ?: return
        ensurePicksFor(session)
    }

    private fun picksFor(session: com.skapp.app.data.LocalPicks.Session) = when (session) {
        com.skapp.app.data.LocalPicks.Session.PREMARKET -> _state.value.localPremarketPicks
        com.skapp.app.data.LocalPicks.Session.REALTIME -> _state.value.localRealtimePicks
        com.skapp.app.data.LocalPicks.Session.MORNING -> _state.value.localMorningPicks
        com.skapp.app.data.LocalPicks.Session.MIDDAY -> _state.value.localMiddayPicks
    }

    fun ensurePicksFor(session: com.skapp.app.data.LocalPicks.Session) {
        startSession(session, false)
    }

    fun refreshLocalPicks(session: com.skapp.app.data.LocalPicks.Session) {
        startSession(session, true)
    }

    private fun startSession(session: com.skapp.app.data.LocalPicks.Session, force: Boolean) {
        val window = com.skapp.app.data.AnalysisSessionPolicy.window(session.key,
            System.currentTimeMillis(), !MarketHours.isClosedDay())
        if (!window.updating) {
            _state.value = _state.value.copy(localPicksMsg =
                if (window.frozen) "时段已结束，保留当时记录；不能事后重算冒充历史。" else "尚未进入该时段；不会借用其他时段名单。")
            return
        }
        val current = picksFor(session)
        val age = current.calculationTime?.let {
            runCatching { System.currentTimeMillis() - java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
        if (!force && current.date == window.date && age != null && age in 0..300_000L) return
        sessionRequests.submit(session, force)
    }

    /**
     * 本地盘前/午盘推股：设备直连行情 + 本地 12 引擎 / 因子库全量因子打分，两个 session 分开存档。
     *
     * 口径（不能混）：
     *   盘前 → 剔除当日未完成日 K（dropLastBar），只看截至上一交易日的指标，与打开 App 的时点无关；
     *   午盘 → 当日实时（含当日未完成日 K）。
     * 成本：一次全市场快照（约 11 页）+ 候选池≤40 只日 K；日 K 有 6 小时缓存，盘前/午盘各只跑一次。
     */
    suspend fun rebuildLocalPicks(session: com.skapp.app.data.LocalPicks.Session, force: Boolean = false) {
        val current = picksFor(session)
        val today = MarketHours.todayKey()
        val window = com.skapp.app.data.AnalysisSessionPolicy.window(session.key,
            System.currentTimeMillis(), !MarketHours.isClosedDay())
        if (!window.updating) return
        // 当天算过且**算完**就不再重算。注意空名单的 status 不是 ok：
        //   ok         = 有票进名单
        //   defensive  = 数据齐、但一只都没达标（弱市，设计内结果）
        //   insufficient = 数据不足（该重试）
        // 只认 ok 会让弱市每次进页都重跑一次全市场深算，所以 ok 与 defensive 都算算完。
        if (!force && current.date == today && current.status in CACHED_PICK_STATUS &&
            !shared.poolsMissingForPicks
        ) return

        _state.value = _state.value.copy(
            localPicksLoading = true,
            localPicksMsg = "正在本地深算${session.label}：${session.basis}",
        )
        try {
            val t0 = System.currentTimeMillis()
            val calcTime = java.time.OffsetDateTime.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString()
            val dropLast = session == com.skapp.app.data.LocalPicks.Session.PREMARKET
            val snapshot = requestResult { com.skapp.app.data.LiveMarket.snapshot() }.getOrDefault(emptyList())
            val snapshotSize = snapshot.size
            val tSnapshot = System.currentTimeMillis()
            // mode=all：保留未达标票，由 LocalPicks 决定谁进 picks、谁只当「未达标观察」
            val analyzed = LocalScreener.screen(
                onProgress = { msg -> _state.value = _state.value.copy(localPicksMsg = msg) },
                mode = "all",
                dropLastBar = dropLast,
                snapshotIn = snapshot,
                news = com.skapp.app.data.CentralAnalysis.newsOf(_state.value.newsCalcs),
                newsAvailable = com.skapp.app.data.CentralAnalysis.newsAvailable(_state.value.news),
                // 全市场快照不可得时退到自选池：没有这个，推股页在限流下永远是空的
                fallbackCodes = Watchlist.codes,
                // 深算档位按自然日推进：rotation=0 时 preselect 恒取预评分前 maxCandidates（40）只，
                // 其余五千多只永远进不了深算，「全市场扫描」实际只是同一小撮票反复算。
                // 用日期而不是进程内计数，是为了同一天重算得到同一份结果（可复现），跨天再换档。
                rotation = java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toEpochDay().toInt(),
            )
            val screened = analyzed.map { row ->
                val impacts = _state.value.newsCalcs.filter { n ->
                    row.code in n.impactCodes || n.stocks.any { it.code == row.code }
                }
                val adverse = impacts.filter { it.direction == "neg" || it.sentiment == "neg" }
                if (adverse.isEmpty()) row else row.copy(
                    riskFlag = "消息风险待核实：" + adverse.take(2).joinToString("；") { it.newsTitle },
                    reason = listOfNotNull(row.reason, adverse.first().negativeReason).joinToString("；"),
                )
            }
            val tScreen = System.currentTimeMillis()
            // 多池证据传给算法层参与排序：同一个池是另一套口径的独立判断，
            // 放在 UI 里二次排序等于让「显示顺序」和「算出来的名单」各说一套。
            // 必须在这里**同步现取**池，不能读 state.pools：那取决于名单页自己的
            // LaunchedEffect 有没有先跑完，两个协程赛跑的结果是「命中池却没加权」，
            // 而名单当天又不会重算，等于永久偏差。
            val poolsNow = runCatching {
                if (_state.value.pools.isNotEmpty()) _state.value.pools
                else DbTables.repricePools(DbTables.pools())
            }.getOrDefault(emptyList())
            if (poolsNow.isNotEmpty() && _state.value.pools.isEmpty()) {
                _state.value = _state.value.copy(pools = poolsNow, poolsLoadedAt = System.currentTimeMillis())
            }
            shared.poolsMissingForPicks = poolsNow.isEmpty()
            val poolHits = runCatching {
                poolsNow.flatMap { pool -> pool.items.map { it.code } }
                    .groupingBy { it }.eachCount()
            }.getOrDefault(emptyMap())
            val artifact = com.skapp.app.data.LocalPicks.buildWithMarket(
                screened = screened,
                session = session,
                calcTime = calcTime,
                scanMode = "all",
                droppedLastBar = dropLast,
                totalSnapshot = snapshotSize,
                poolHits = poolHits,
            )
            val tEngine = System.currentTimeMillis()
            // 趋势与退出计划复用筛选时点的 bars，不再按250根另取一次。
            // 本轮不足60根就显式降级，不用第二时点数据补洞。
            val trendTargets = artifact.picks
            val screenedByCode = screened.associateBy { it.code }
            class TrendRun(
                val code: String,
                val ms: Long,
                val result: com.skapp.app.data.TrendForecast.Result?,
                val fail: String?,
            )
            val trendRuns = withContext(Dispatchers.Default) { trendTargets.map { p ->
                val k0 = System.currentTimeMillis()
                val bars = screenedByCode[p.code]?.bars.orEmpty()
                if (bars.size < 60) {
                    TrendRun(p.code, 0L, null, "${p.code} 本轮仅 ${bars.size} 根")
                } else {
                    runCatching { com.skapp.app.data.TrendForecast.compute(bars) }.fold(
                        onSuccess = { r -> TrendRun(p.code, System.currentTimeMillis() - k0, r, null) },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            TrendRun(p.code, System.currentTimeMillis() - k0, null, "${p.code} compute ${e.javaClass.simpleName}")
                        },
                    )
                }
            } }
            val klineCalls = 0
            val klineMs = trendRuns.sumOf { it.ms }
            val trendFails = trendRuns.mapNotNull { it.fail }
            val trends = trendRuns.mapNotNull { r -> r.result?.let { r.code to it } }.toMap()
            val tTrend = System.currentTimeMillis()
            val timing = buildString {
                append("首屏 ${tTrend - t0}ms（快照 ${tSnapshot - t0} / 筛选 ${tScreen - tSnapshot} / 引擎 ${tEngine - tScreen}）")
                append(" · 复用本轮日K ${trendRuns.size} 只，额外请求 $klineCalls 次；趋势 ${klineMs}ms")
                if (trendFails.isNotEmpty()) {
                    append(" · 趋势失败 ${trendFails.size}/${trendRuns.size}：")
                    append(trendFails.take(3).joinToString("；"))
                }
            }
            if (!com.skapp.app.data.AnalysisSessionPolicy.mayPublish(session.key, t0,
                    System.currentTimeMillis(), !MarketHours.isClosedDay())) {
                _state.value = _state.value.copy(localPicksMsg = "本轮计算已跨时段截止点，已丢弃迟到结果并保留冻结记录。")
                return
            }
            _state.value = _state.value.copy(
                // 本地深算完成即回填首屏推股卡（TodayPicks 由本地产物映射，不经云端）
                picks = com.skapp.app.data.LocalSnapshot.todayPicks(artifact),
                picksTrend = trends,
                picksTrendAttempted = trendTargets.map { it.code }.toSet(),
                localRealtimePicks = if (session == com.skapp.app.data.LocalPicks.Session.REALTIME)
                    artifact else _state.value.localRealtimePicks,
                localPremarketPicks = if (session == com.skapp.app.data.LocalPicks.Session.PREMARKET)
                    artifact else _state.value.localPremarketPicks,
                localMorningPicks = if (session == com.skapp.app.data.LocalPicks.Session.MORNING)
                    artifact else _state.value.localMorningPicks,
                localMiddayPicks = if (session == com.skapp.app.data.LocalPicks.Session.MIDDAY)
                    artifact else _state.value.localMiddayPicks,
                localPicksMsg = artifact.reason,
                localPicksTiming = timing,
                screened = screened,
                screenedAt = calcTime,
                scanMode = "all",
            )
            persistLocalPicks(session, artifact)
            // 盘前/早盘/午盘各入队一轮，历史页才有四类时段可查；ts 去重由队列负责
            runCatching {
                PicksArchiveQueue.enqueuePushRound(
                    artifact.date ?: java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString(),
                    IntradayPicks.toRound(
                        artifact,
                        java.time.OffsetDateTime.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString(),
                        session.key,
                    ),
                )
            }
            // 推送历史入队：flush 交给启动/定时，不在这里等网络
            archivePushRound(artifact, calcTime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(localPicksMsg = "本地推股失败：${e.message ?: "行情接口不可达"}")
        } finally {
            _state.value = _state.value.copy(localPicksLoading = false)
        }
    }

    /** 当日存档落盘：重启 App、或盘后回来还能看到「今天盘前当时给的名单」。 */
    private fun persistLocalPicks(session: com.skapp.app.data.LocalPicks.Session, artifact: StructuredPickArtifact) {
        val ctx = shared.appContext ?: return
        val json = org.json.JSONObject().apply {
            put("generatedAt", artifact.generatedAt)
            put("calculationTime", artifact.calculationTime)
            put("date", artifact.date)
            put("session", artifact.session)
            put("engine", artifact.engine)
            put("status", artifact.status)
            put("reason", artifact.reason)
            put("metadata", org.json.JSONObject(artifact.metadata))
            put("picks", org.json.JSONArray().also { arr ->
                artifact.picks.forEach { p ->
                    arr.put(org.json.JSONObject().apply {
                        put("code", p.code)
                        put("name", p.name)
                        put("price", p.price)
                        put("pctChg", p.pctChg)
                        put("score", p.score)
                        put("buyable", p.buyable)
                        put("resonance", p.resonance)
                        put("reason", p.reason)
                        put("calculationTime", p.calculationTime)
                        put("why", org.json.JSONArray(p.why))
                    })
                }
            })
        }
        ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE).edit()
            .putString("local_picks_${session.key}", json.toString()).apply()
    }

    /** 启动时恢复早盘草稿：打开 App 先看到「当时给的名单」，异步算的是修正后的版本。 */
    fun restorePremarketDraft() {
        val ctx = shared.appContext ?: return
        val text = ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE)
            .getString("premarket_draft", null) ?: return
        runCatching {
            val o = org.json.JSONObject(text)
            val picks = o.optJSONArray("picks")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONObject(i) ?: return@mapNotNull null
                    val code = p.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    com.skapp.app.data.PremarketPicks.DraftPick(
                        code = code,
                        name = p.optString("name").takeIf { it.isNotBlank() },
                        price = p.optDouble("price", Double.NaN).takeIf { !it.isNaN() },
                        pctChg = p.optDouble("pctChg", Double.NaN).takeIf { !it.isNaN() },
                        baseScore = p.optDouble("baseScore", Double.NaN).takeIf { !it.isNaN() },
                        newsDelta = p.optDouble("newsDelta", 0.0),
                        finalScore = p.optDouble("finalScore", 0.0),
                        benefit = p.optBoolean("benefit"),
                        harm = p.optBoolean("harm"),
                        why = p.optJSONArray("why")?.let { w ->
                            (0 until w.length()).mapNotNull { idx ->
                                w.optString(idx).takeIf { s -> s.isNotBlank() }
                            }
                        } ?: emptyList(),
                        source = p.optString("source", "local"),
                    )
                }
            } ?: emptyList()
            _state.value = _state.value.copy(
                premarketDraft = com.skapp.app.data.PremarketPicks.Draft(
                    status = runCatching {
                        com.skapp.app.data.PremarketPicks.Status.valueOf(o.optString("status"))
                    }.getOrDefault(com.skapp.app.data.PremarketPicks.Status.DRAFT),
                    date = o.optString("date"),
                    updatedAt = o.optString("updatedAt"),
                    picks = picks,
                    dataGap = o.optBoolean("dataGap"),
                    coveredFrom = o.optString("coveredFrom").takeIf { it.isNotBlank() },
                    coveredUntil = o.optString("coveredUntil").takeIf { it.isNotBlank() },
                    note = o.optString("note").takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    /** 启动时恢复本地推股存档（只恢复，不重算）。 */
    fun restoreLocalPicks() {
        val ctx = shared.appContext ?: return
        val sp = ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE)
        fun num(o: org.json.JSONObject, key: String): Double? =
            o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }

        fun read(session: com.skapp.app.data.LocalPicks.Session): StructuredPickArtifact? {
            val text = sp.getString("local_picks_${session.key}", null) ?: return null
            return runCatching {
                val o = org.json.JSONObject(text)
                val picks = o.optJSONArray("picks")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val p = arr.optJSONObject(i) ?: return@mapNotNull null
                        val code = p.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        PickSignal(
                            code = code,
                            name = p.optString("name").takeIf { it.isNotBlank() },
                            price = num(p, "price"),
                            pctChg = num(p, "pctChg"),
                            score = num(p, "score"),
                            buyable = if (p.has("buyable")) p.optBoolean("buyable") else null,
                            engine = o.optString("engine").takeIf { it.isNotBlank() },
                            resonance = num(p, "resonance"),
                            reason = p.optString("reason").takeIf { it.isNotBlank() },
                            calculationTime = p.optString("calculationTime").takeIf { it.isNotBlank() },
                            why = p.optJSONArray("why")?.let { w ->
                                (0 until w.length()).mapNotNull { w.optString(it).takeIf { s -> s.isNotBlank() } }
                            } ?: emptyList(),
                        )
                    }
                } ?: emptyList()
                StructuredPickArtifact(
                    generatedAt = o.optString("generatedAt").takeIf { it.isNotBlank() },
                    calculationTime = o.optString("calculationTime").takeIf { it.isNotBlank() },
                    date = o.optString("date").takeIf { it.isNotBlank() },
                    session = session.key,
                    engine = o.optString("engine").takeIf { it.isNotBlank() },
                    status = o.optString("status", "insufficient"),
                    reason = o.optString("reason").takeIf { it.isNotBlank() },
                    picks = picks,
                    metadata = o.optJSONObject("metadata")?.toPlainMap().orEmpty(),
                )
            }.getOrNull()
        }

        val realtime = read(com.skapp.app.data.LocalPicks.Session.REALTIME)
        val pre = read(com.skapp.app.data.LocalPicks.Session.PREMARKET)
        val morning = read(com.skapp.app.data.LocalPicks.Session.MORNING)
        val mid = read(com.skapp.app.data.LocalPicks.Session.MIDDAY)
        _state.value = _state.value.copy(
            localRealtimePicks = realtime ?: _state.value.localRealtimePicks,
            localPremarketPicks = pre ?: _state.value.localPremarketPicks,
            localMorningPicks = morning ?: _state.value.localMorningPicks,
            localMiddayPicks = mid ?: _state.value.localMiddayPicks,
        )
    }

    /** 推股页数据补齐：多池 + 引擎产出（load() 已覆盖 today_picks/decision/news/sectors/board） */
    fun loadPicks(force: Boolean = false) {
        if (!force && picksJob?.isActive == true) return
        picksJob = viewModelScope.launch {
            val poolsD = async {
                val raw = runCatching { DbTables.pools() }.getOrElse { emptyList() }
                runCatching { DbTables.repricePools(raw) }.getOrDefault(raw)
            }
            val feedsD = async { runCatching { market.loadEngineFeeds() }.getOrNull() }
            _state.value = _state.value.copy(
                pools = poolsD.await(),
                engineFeeds = feedsD.await(),
            )
        }
    }

    /** 把本地归档队列刷到 GitHub；失败原样留着，不弹错、不阻断首屏。 */
    suspend fun flushPicksArchive() {
        if (!PicksArchiveQueue.isReady()) return
        if (!GitHubRepoWriter.isConfigured()) return
        val report = PicksArchiveQueue.flush()
        if (report.failures.isNotEmpty()) {
            _state.value = _state.value.copy(
                screenMsg = "归档待重试：" + report.failures.first(),
            )
        }
    }

    /** 把本轮算好的消息写入归档队列（只存当天：历史消息由云端库负责，端上不重复传）。 */
    fun archiveNewsCalcs(calcs: List<com.skapp.app.data.NewsCalc>) {
        if (!PicksArchiveQueue.isReady()) return
        val today = java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString()
        calcs.forEach { calc ->
            val ts = calc.ts?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!ts.startsWith(today)) return@forEach
            runCatching { PicksArchiveQueue.enqueueNews(today, NewsArchive.toJson(calc, today)) }
        }
    }

    /** 推送记录入队（成功与否都不影响名单展示）。 */
    private fun archivePushRound(artifact: com.skapp.app.data.StructuredPickArtifact, ts: String) {
        if (!PicksArchiveQueue.isReady()) return
        val today = java.time.LocalDate.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString()
        runCatching { PicksArchiveQueue.enqueuePushRound(today, IntradayPicks.toRound(artifact, ts)) }
    }

    /** 早盘推股草稿：本地深算为底、消息面修正，09:30 后冻结。 */
    suspend fun refreshPremarketDraft() {
        ensureLocalPicks()
    }

    private fun persistPremarketDraft(draft: com.skapp.app.data.PremarketPicks.Draft) {
        val ctx = shared.appContext ?: return
        val json = org.json.JSONObject().apply {
            put("status", draft.status.name)
            put("date", draft.date)
            put("updatedAt", draft.updatedAt)
            put("dataGap", draft.dataGap)
            draft.coveredFrom?.let { put("coveredFrom", it) }
            draft.coveredUntil?.let { put("coveredUntil", it) }
            draft.note?.let { put("note", it) }
            put("picks", org.json.JSONArray().also { arr ->
                draft.picks.forEach { p ->
                    arr.put(org.json.JSONObject().apply {
                        put("code", p.code)
                        put("name", p.name)
                        put("price", p.price)
                        put("pctChg", p.pctChg)
                        put("baseScore", p.baseScore)
                        put("newsDelta", p.newsDelta)
                        put("finalScore", p.finalScore)
                        put("benefit", p.benefit)
                        put("harm", p.harm)
                        put("why", org.json.JSONArray(p.why))
                        put("source", p.source)
                    })
                }
            })
        }
        ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE).edit()
            .putString("premarket_draft", json.toString()).apply()
    }

    /** 实时选股（设备直连行情 + 中枢引擎，不用云端快照）。同模式结果缓存，切 tab 不重扫。 */
    fun runScreen(mode: String = "score", force: Boolean = false): Job? {
        if (_state.value.screening) return null
        if (_state.value.scanMode == mode && _state.value.screened.isNotEmpty() && !force) return null
        return viewModelScope.launch {
            requestResult { refreshScreen(mode) }
        }
    }

    suspend fun refreshScreen(mode: String) {
        _state.value = _state.value.copy(screening = true, screenMsg = "正在实时拉取全市场行情…")
        try {
            val res = LocalScreener.screen(onProgress = { msg ->
                _state.value = _state.value.copy(screenMsg = msg)
            }, mode = mode, news = com.skapp.app.data.CentralAnalysis.newsOf(_state.value.newsCalcs),
                newsAvailable = com.skapp.app.data.CentralAnalysis.newsAvailable(_state.value.news))
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(
                screened = res,
                scanMode = mode,
                screenedAt = java.time.OffsetDateTime.now(com.skapp.app.data.AnalysisSessionPolicy.zone).toString(),
                screenMsg = if (res.isEmpty()) "预筛后无满足条件的股票" else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(screenMsg = "实时选股失败：${e.message ?: "行情接口不可达"}")
            throw e
        } finally {
            _state.value = _state.value.copy(screening = false)
        }
    }
}