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

/** 视为「当天已算完」的产物状态：ok=有票进名单，defensive=数据齐但无票（弱市，设计内结果）。 */
private val CACHED_PICK_STATUS = setOf("ok", "defensive")

/** 本地推股全链路：深算 / 当日存档 / 早盘草稿 / 归档队列 / 实时选股 */
internal class LocalPicksHolder(
    shared: SharedVm,
    private val market: MarketStateHolder,
) : StateHolder(shared) {

    private var picksJob: Job? = null

    private var localPicksJob: Job? = null

    /** 当前可用的本地推股产物：午盘优先（更近），其次盘前；都不是今天则视为没有。 */
    fun localPicksArtifact(): com.skapp.app.data.StructuredPickArtifact? {
        val today = java.time.LocalDate.now().toString()
        val midday = _state.value.localMiddayPicks
        val premarket = _state.value.localPremarketPicks
        // 只要「今天跑过深算」就返回产物，不再要求 picks 非空：
        // 弱市/达标 0 只时 status=defensive，metadata 里还带着观察名单。
        // 旧写法要求 picks.isNotEmpty()，把「算完了但没达标」当成「尚未完成」，
        // 观察名单被丢掉，首屏报的是一个不存在的状态（待完成）。
        return when {
            midday.date == today -> midday
            premarket.date == today -> premarket
            else -> null
        }
    }

    /** 页面进入时调用：当前时段还没有当日存档才真的算一次。 */
    fun ensureLocalPicks() {
        if (_state.value.localPicksLoading || localPicksJob?.isActive == true) return
        val session = com.skapp.app.data.LocalPicks.sessionFor(MarketHours.phase())
        if (session == null) {
            // 收盘/休市时 sessionFor 返回 null。原先这里只写一句「非交易时段」就 return，
            // 推股页于是整屏无名单——用户看到的是「没有自动推荐」而不是「暂无盘中新名单」。
            // 盘前口径用上一交易日收盘数据，任何时段都算得出来，所以回落到它；
            // 当日已有存档时 ensurePicksFor 会直接复用，不会重复深算。
            if (localPicksArtifact() == null) {
                ensurePicksFor(com.skapp.app.data.LocalPicks.Session.PREMARKET)
            } else if (_state.value.localPicksMsg == null) {
                _state.value = _state.value.copy(
                    localPicksMsg = "当前非交易时段，显示的是最近一次存档（盘前口径）。",
                )
            }
            return
        }
        localPicksJob = viewModelScope.launch { requestResult { rebuildLocalPicks(session, force = false) } }
    }

    /**
     * 按**指定时段**保证名单可用（推股名单页专用）。
     *
     * 与 [ensureLocalPicks] 的区别：后者按当前交易时段挑一个会话，于是当天下午打开
     * 「盘前推股名单」时只会算午盘口径，盘前名单永远空着——而盘前口径用的是
     * 上一交易日收盘数据，任何时段都算得出来，没理由等到明天早上。
     * 实时口径则相反：非交易时段没有盘中数据，算了也是旧的，因此明确跳过并给出说明。
     */
    fun ensurePicksFor(session: com.skapp.app.data.LocalPicks.Session) {
        if (_state.value.localPicksLoading || localPicksJob?.isActive == true) return
        val current = if (session == com.skapp.app.data.LocalPicks.Session.PREMARKET)
            _state.value.localPremarketPicks else _state.value.localMiddayPicks
        val today = java.time.LocalDate.now().toString()
        if (current.date == today && current.status in CACHED_PICK_STATUS && !shared.poolsMissingForPicks) return
        if (session == com.skapp.app.data.LocalPicks.Session.MIDDAY &&
            com.skapp.app.data.LocalPicks.sessionFor(MarketHours.phase()) != session
        ) {
            _state.value = _state.value.copy(
                localPicksMsg = "当前非交易时段，实时名单不在此刻生成；显示的仍是最近一次存档。",
            )
            return
        }
        localPicksJob = viewModelScope.launch { requestResult { rebuildLocalPicks(session, force = false) } }
    }

    /** 卡片上的「重算」：用户显式要求重跑指定时段（盘前卡重算仍是盘前口径）。 */
    fun refreshLocalPicks(session: com.skapp.app.data.LocalPicks.Session) {
        if (_state.value.localPicksLoading || localPicksJob?.isActive == true) return
        localPicksJob = viewModelScope.launch { requestResult { rebuildLocalPicks(session, force = true) } }
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
        val current = if (session == com.skapp.app.data.LocalPicks.Session.PREMARKET)
            _state.value.localPremarketPicks else _state.value.localMiddayPicks
        val today = java.time.LocalDate.now().toString()
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
            val calcTime = java.time.OffsetDateTime.now().toString()
            val dropLast = session == com.skapp.app.data.LocalPicks.Session.PREMARKET
            val snapshotSize = runCatching { LocalScreener.marketSnapshot(pz = 500).size }.getOrDefault(0)
            val tSnapshot = System.currentTimeMillis()
            // mode=all：保留未达标票，由 LocalPicks 决定谁进 picks、谁只当「未达标观察」
            val screened = LocalScreener.screen(
                onProgress = { msg -> _state.value = _state.value.copy(localPicksMsg = msg) },
                mode = "all",
                dropLastBar = dropLast,
                // 全市场快照不可得时退到自选池：没有这个，推股页在限流下永远是空的
                fallbackCodes = Watchlist.codes,
                // 深算档位按自然日推进：rotation=0 时 preselect 恒取预评分前 maxCandidates（40）只，
                // 其余五千多只永远进不了深算，「全市场扫描」实际只是同一小撮票反复算。
                // 用日期而不是进程内计数，是为了同一天重算得到同一份结果（可复现），跨天再换档。
                rotation = java.time.LocalDate.now().toEpochDay().toInt(),
            )
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
            // 名单**全部**票算多日趋势：逐票走 DataCache 拉日 K（6 小时缓存，名单上限 10 只）；
            // 拉不到或不足 60 根就不给该票方向（不编、不拿同名单其他票的结果顶替）。
            // 之前只算前 5 只，第 6 只起恒显示「未计算」，被读成「名单只出了前 5 只」——
            // 而 MAX_PICKS 本是 10：性能取舍换来的是对名单规模的误判，不划算。
            val trendTargets = artifact.picks
            var klineCalls = 0
            var klineMs = 0L
            val trendFails = mutableListOf<String>()
            val trends = trendTargets.mapNotNull { p ->
                val k0 = System.currentTimeMillis()
                val fetched = runCatching {
                    com.skapp.app.data.DataCache.kline(p.code, "qfq", 250)
                }
                klineMs += System.currentTimeMillis() - k0
                klineCalls++
                val bars = fetched.getOrElse { e ->
                    trendFails += "${p.code} 抛 ${e.javaClass.simpleName}"
                    emptyList()
                }
                if (bars.size < 60) {
                    if (bars.isNotEmpty()) trendFails += "${p.code} 仅 ${bars.size} 根"
                    null
                } else {
                    runCatching { p.code to com.skapp.app.data.TrendForecast.compute(bars) }
                        .onFailure { trendFails += "${p.code} compute ${it.javaClass.simpleName}" }
                        .getOrNull()
                }
            }.toMap()
            val tTrend = System.currentTimeMillis()
            val timing = buildString {
                append("首屏 ${tTrend - t0}ms（快照 ${tSnapshot - t0} / 筛选 ${tScreen - tSnapshot} / 引擎 ${tEngine - tScreen}）")
                append(" · 日K $klineCalls 次 ${klineMs}ms")
                if (klineCalls > 0) append("，均 ${klineMs / klineCalls}ms")
                if (trendFails.isNotEmpty()) {
                    append(" · 趋势失败 ${trendFails.size}/$klineCalls：")
                    append(trendFails.take(3).joinToString("；"))
                }
            }
            _state.value = _state.value.copy(
                // 本地深算完成即回填首屏推股卡（TodayPicks 由本地产物映射，不经云端）
                picks = com.skapp.app.data.LocalSnapshot.todayPicks(artifact),
                picksTrend = trends,
                picksTrendAttempted = trendTargets.map { it.code }.toSet(),
                localPremarketPicks = if (session == com.skapp.app.data.LocalPicks.Session.PREMARKET)
                    artifact else _state.value.localPremarketPicks,
                localMiddayPicks = if (session == com.skapp.app.data.LocalPicks.Session.MIDDAY)
                    artifact else _state.value.localMiddayPicks,
                localPicksMsg = artifact.reason,
                localPicksTiming = timing,
            )
            persistLocalPicks(session, artifact)
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
                )
            }.getOrNull()
        }

        val pre = read(com.skapp.app.data.LocalPicks.Session.PREMARKET)
        val mid = read(com.skapp.app.data.LocalPicks.Session.MIDDAY)
        _state.value = _state.value.copy(
            localPremarketPicks = pre ?: _state.value.localPremarketPicks,
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
        val today = java.time.LocalDate.now().toString()
        calcs.forEach { calc ->
            val ts = calc.ts?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!ts.startsWith(today)) return@forEach
            runCatching { PicksArchiveQueue.enqueueNews(today, NewsArchive.toJson(calc, today)) }
        }
    }

    /** 推送记录入队（成功与否都不影响名单展示）。 */
    private fun archivePushRound(artifact: com.skapp.app.data.StructuredPickArtifact, ts: String) {
        if (!PicksArchiveQueue.isReady()) return
        val today = java.time.LocalDate.now().toString()
        runCatching { PicksArchiveQueue.enqueuePushRound(today, IntradayPicks.toRound(artifact, ts)) }
    }

    /** 早盘推股草稿：本地深算为底、消息面修正，09:30 后冻结。 */
    suspend fun refreshPremarketDraft() {
        val ctx = shared.appContext ?: return
        val now = java.time.OffsetDateTime.now().toString()
        val date = now.take(10)
        val sp = ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE)
        val frozenDate = sp.getString("premarket_frozen", null)
        val frozen = frozenDate == date || com.skapp.app.data.PremarketPicks.isFrozen(now)
        // 已冻结且今天已经有草稿：不再重算，也不再去读消息库（每次消息刷新都走一遍网络太重）
        if (frozen && _state.value.premarketDraft?.date == date) return

        // 消息库：有 PAT 才读；读失败按「无历史消息」处理并在 dataGap 里体现
        val stored = if (GitHubRepoWriter.isConfigured()) {
            runCatching {
                GitHubRepoWriter.readFile("${PicksArchiveQueue.NEWS_DB_DIR}/$date.json")?.body
                    ?.let { org.json.JSONObject(it).optJSONArray("items") }
                    ?.let { NewsArchive.readRows(it) }
            }.getOrNull().orEmpty()
        } else emptyList()

        val draft = com.skapp.app.data.PremarketPicks.build(
            base = _state.value.localPremarketPicks,
            stored = stored,
            live = _state.value.newsCalcs,
            date = date,
            now = now,
            frozen = frozen,
            coverage = NewsArchive.coverage(stored),
        )
        // 缺口补拉：消息库覆盖不到早盘窗口（前一夜没开 App）时，从消息源再拉一段历史
        val draftWithGap = if (!frozen && draft.dataGap) {
            val backfill = runCatching { MarketDataClient.news724(100) }.getOrDefault(emptyList())
            if (backfill.isEmpty()) draft else {
                val calcs = runCatching {
                    com.skapp.app.data.NewsCalcEngine.compute(backfill, _state.value.symbols)
                }.getOrDefault(emptyList())
                if (calcs.isEmpty()) draft else {
                    val merged = (_state.value.newsCalcs + calcs).distinctBy { it.newsTitle }
                    com.skapp.app.data.PremarketPicks.build(
                        base = _state.value.localPremarketPicks,
                        stored = stored,
                        live = merged,
                        date = date,
                        now = now,
                        frozen = frozen,
                        coverage = NewsArchive.coverage(stored),
                    )
                }
            }
        } else draft
        _state.value = _state.value.copy(premarketDraft = draftWithGap)
        persistPremarketDraft(draftWithGap)
        if (frozen && frozenDate != date) {
            sp.edit().putString("premarket_frozen", date).apply()
        }
    }

    /** 早盘草稿落盘：杀进程再开也能看到「当时给的名单」，而不是重算出一份新的。 */
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
            }, mode = mode)
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(
                screened = res,
                scanMode = mode,
                screenedAt = java.time.OffsetDateTime.now().toString(),
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
