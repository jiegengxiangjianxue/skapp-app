package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地实时选股引擎 —— 在设备上完成「实时收集数据 → 算法分析 → 出结果」。
 *
 * 用户核心诉求：推股/决策不能再用云端旧快照，要在手机上实时算。
 *
 * 数据链（全部实时，无云端快照）：
 *   1) 东财 clist 批量拉全市场实时行情（价格/涨跌幅/量比/市值/PE，一次 5000 只）
 *   2) 预筛（放量 + 涨幅区间 + 非 ST）→ 候选池
 *   3) 对候选池前 N 只逐只拉日 K（腾讯→东财），本地 12 引擎 + 因子库全量因子
 *   4) 按 composite 排序输出
 *
 * 红线：只用真实拉到的数据；拉不到的标 insufficient，不编造。
 */
object LocalScreener {

    /** 筛选条件 */
    data class Criteria(
        val minChgPct: Double = 1.0,
        val maxChgPct: Double = MarketLimits.LIMIT_UP_PCT,   // 推股不追涨停：主板涨停附近(≥9.6%)不可买入
        val minVolRatio: Double = 1.2,
        val minScore: Double = 55.0,
        /** 规格聚合分门槛。与 [minScore] 分开：切换期可以只开一个，不用二选一。 */
        val minCanonical: Double = 55.0,
        /** 资金/盘口双弱的票是否一票否决（默认开；关掉只用于回测对照） */
        val vetoByRiskFlag: Boolean = true,
        val excludeST: Boolean = true,
        val maxCandidates: Int = 40,   // 逐只拉 K 线的上限（控制耗时/流量）
        val klineCount: Int = 120,
    )

    /** 调用方必须提供真实财报/复权证据与显式有效期；不从价格或PE反推。 */
    data class DataEvidence(
        val eps: Double?,
        val dps: Double?,
        val reportPeriod: String?,
        val adjustmentEvidence: String?,
        val source: String?,
        val snapshotAtMs: Long?,
        val expiresAtMs: Long?,
        val code: String? = null,
        val barsFingerprint: String? = null,
        val missing: List<String> = emptyList(),
    ) {
        fun validFor(expectedCode: String, bars: List<KBar>, nowMs: Long): Boolean =
            validAt(nowMs) && code == expectedCode && barsFingerprint != null &&
                barsFingerprint == StockAnalysisInputs.barsFingerprint(bars)

        fun validAt(nowMs: Long): Boolean {
            val period = try { java.time.LocalDate.parse(reportPeriod) } catch (_: Exception) { null }
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(AnalysisSessionPolicy.zone).toLocalDate()
            return eps?.isFinite() == true && dps?.isFinite() == true && period != null && period <= today &&
                adjustmentEvidence?.startsWith("cnfin:hfq_factor:") == true && !source.isNullOrBlank() &&
                snapshotAtMs != null && snapshotAtMs in 1..nowMs && expiresAtMs != null && expiresAtMs >= nowMs
        }
    }

    /** 一只股票的实时分析结果 */
    data class Screened(
        val code: String,
        val name: String?,
        val price: Double?,
        val pctChg: Double?,
        val volRatio: Double?,
        val turnover: Double?,
        val pe: Double?,
        val mv: Double?,
        val engine: LocalEngineResult?,
        /**
         * 规格驱动链路结果（[EngineChain]）：含 flow/tape 外部票与规格聚合分。
         * 与 [engine] 并存而不是替换：`engine` 是 12 引擎老口径，历史样本可比；
         * `chain` 是与云端 `engine_v5` 同构的那一份，新逻辑一律读它。
         */
        val chain: EngineChain.ChainResult? = null,
        /** 规格聚合分（含外部票）。bars < 30 或规格不可用时为 null，**不回落成 0**。 */
        val canonicalScore: Double? = chain?.canonicalScore,
        /** 新引擎的风控否决标记（资金与盘口双弱等）；null = 无否决 */
        val riskFlag: String? = chain?.riskFlag,
        val centralScore: Double? = engine?.centralScore ?: engine?.composite,
        val resonanceRatio: Double? = engine?.resonanceRatio,
        val centralSummary: String? = engine?.summary,
        val detailStatus: String,     // ok | insufficient
        val reason: String? = null,
        val buyable: Boolean? = null,
        /**
         * 退出纪律（[ExitPlan]）：清仓线/止盈线/今日形态/动作。
         *
         * 放在这里是因为**候选池的日 K 本来就在手上**（同一次 deep calc），
         * 加这一项零额外请求；也让「破位的票不进推荐」成为硬规则而不是界面提示。
         */
        val exitPlan: ExitPlan.Result? = null,
        val snapshotStatus: String = "live",
        /** 所属板块（SectorLink 查表）；未映射为 null */
        val sector: String? = null,
        /** 板块强度（0~100，见 [LocalSectors.Row.strength]）；板块未映射或数据不足为 null */
        val sectorStrength: Double? = null,
        /** 排序分 = canonicalScore + 板块偏移，见 [effectiveScoreOf]；只影响排序，不影响 minScore 过滤 */
        val effectiveScore: Double? = null,
        val dataEvidence: DataEvidence? = null,
        val earlyTomorrow: EarlyVoteEngine.Result? = null,
        val earlyMorning: EarlyVoteEngine.Result? = null,
        /** 本轮已过滤日期的 K 线，退出计划复用，禁止另取一轮混合时点。 */
        val bars: List<KBar> = emptyList(),
        val safetyInputsReady: Boolean = detailStatus == "ok",
        val snapshotAtMs: Long? = null,
        val snapshotSource: String? = null,
    )

    /**
     * 全市场实时快照（Map 形态，兼容既有调用方）。
     *
     * 实现在 [LiveMarket]：全市场只拉一次、多处共享同一时点。
     * 新代码请直接用 [LiveMarket.snapshot] 的强类型 [LiveMarket.Row]。
     */
    suspend fun marketSnapshot(pn: Int = 1, pz: Int = 100): List<Map<String, Any?>> =
        LiveMarket.snapshot().map { r ->
            mapOf<String, Any?>(
                "code" to r.code,
                "name" to r.name,
                "price" to r.price,
                "pctChg" to r.pctChg,
                "volRatio" to r.volRatio,
                "turnover" to r.volumeLots,   // 历史字段名：当年映射的是 f5 成交量（手）
                "volume" to r.amount,         // 历史字段名：当年映射的是 f6 成交额（元）
                "amplitude" to r.amplitude,
                "turnoverRate" to r.turnoverRate,
                "pe" to r.pe,
                "mv" to r.mv,
                "mainNet" to r.mainNet,
                // 异动榜首轮的动量兜底（东财 f22 涨速）；缺了它首轮只能全标 insufficient
                "speed" to r.speed,
            )
        }

    /**
     * 实时选股：市场快照 → 预筛 → 逐只本地引擎 → 排序。
     * mode: "score" 常规（评分达标池） | "all" 全分析池（共振/评分页用） | "limitup" 涨停扫描
     * dropLastBar: 按CST日期只排除当日bar（以及非法/未来日期）。
     *   盘前口径必须置 true —— 盘前推股只能看「截至上一交易日收盘」的指标，
     *   否则会把盘中实时当收盘值，等于用未来数据推过去。
     */
    /**
     * @param rotation 候选池轮换档位：0=按预评分取前列；n>0 表示在预评分排序里整段后移 n 段，
     *        用于心跳多轮之间把深算名额摊到全市场（详见 [preselect]）。
     */
    /**
     * 全市场快照不可得时的候选池：给定代码（自选/推股池）走腾讯报价拼成与 clist 同形状的行。
     * 只填得出价格与涨跌幅，量比/换手/资金流为 null —— 依赖它们的因子会自然落空，
     * 这比用零值凑出一个假候选强。
     */
    private suspend fun poolSnap(codes: List<String>): List<LiveMarket.Row> {
        val clean = codes.mapNotNull { Code.normalize(it) }.distinct()
        if (clean.isEmpty()) return emptyList()
        val ts = System.currentTimeMillis()
        return TencentQuote.batch(clean).values.map { q ->
            LiveMarket.Row(
                code = q.code,
                name = q.name,
                price = q.price,
                pctChg = q.pctChg,
                volumeLots = null,
                amount = null,
                amplitude = null,
                turnoverRate = null,
                pe = null,
                volRatio = null,
                mv = null,
                mainNet = null,
                ts = ts,
            )
        }
    }

    private suspend fun <T> cancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Full value equality, not a serialized digest; only in-flight work is shared. */
    internal data class RequestKey(
        val criteria: Criteria = Criteria(),
        val mode: String = "score",
        val dropLastBar: Boolean = false,
        val rotation: Int = 0,
        val fallbackCodes: List<String> = emptyList(),
        val evidenceByCode: Map<String, DataEvidence> = emptyMap(),
        val news: List<EngineChain.NewsEvidence> = emptyList(),
        val newsAvailable: Boolean = false,
        val snapshotIn: List<LiveMarket.Row>? = null,
    )

    internal class RequestCoordinator {
        private val mutex = Mutex()
        private val pending = mutableMapOf<RequestKey, CompletableDeferred<List<Screened>>>()

        suspend fun run(key: RequestKey, compute: suspend () -> List<Screened>): List<Screened> {
            val (result, owner) = mutex.withLock {
                val existing = pending[key]?.takeIf { it.isActive }
                if (existing != null) existing to false
                else CompletableDeferred<List<Screened>>().also { pending[key] = it } to true
            }
            // The deferred has no parent: cancelling a waiter never cancels the owner.
            if (!owner) return result.await()
            try {
                val rows = compute()
                currentCoroutineContext().ensureActive()
                result.complete(rows)
                return rows
            } catch (error: Throwable) {
                result.completeExceptionally(error)
                throw error
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (pending[key] === result) pending.remove(key)
                    }
                }
            }
        }
    }

    private val requests = RequestCoordinator()

    /** An explicitly empty snapshot is authoritative, never a request for stale/network data. */
    internal suspend fun snapshotForRequest(
        snapshotIn: List<LiveMarket.Row>?,
        fetch: suspend () -> List<LiveMarket.Row>,
    ): List<LiveMarket.Row> = snapshotIn ?: fetch()

    suspend fun screen(c: Criteria = Criteria(), onProgress: (String) -> Unit = {},
                       mode: String = "score", dropLastBar: Boolean = false,
                       rotation: Int = 0, fallbackCodes: List<String> = emptyList(),
                       evidenceByCode: Map<String, DataEvidence> = emptyMap(),
                       news: List<EngineChain.NewsEvidence> = CentralAnalysis.newsOf(emptyList()),
                       newsAvailable: Boolean = CentralAnalysis.hasNewsContext,
                       snapshotIn: List<LiveMarket.Row>? = null): List<Screened> {
        // Freeze collection containers so caller mutation cannot corrupt the in-flight map key.
        val key = RequestKey(c, mode, dropLastBar, rotation, fallbackCodes.toList(),
            evidenceByCode.toMap(), news.toList(), newsAvailable, snapshotIn?.toList())
        return requests.run(key) {
            screenOnce(key.criteria, onProgress, key.mode, key.dropLastBar, key.rotation,
                key.fallbackCodes, key.evidenceByCode, key.news, key.newsAvailable, key.snapshotIn)
        }
    }

    private suspend fun screenOnce(c: Criteria, onProgress: (String) -> Unit,
                       mode: String = "score", dropLastBar: Boolean = false,
                       rotation: Int = 0, fallbackCodes: List<String> = emptyList(),
                       evidenceByCode: Map<String, DataEvidence> = emptyMap(),
                       news: List<EngineChain.NewsEvidence> = CentralAnalysis.newsOf(emptyList()),
                       newsAvailable: Boolean = CentralAnalysis.hasNewsContext,
                       snapshotIn: List<LiveMarket.Row>? = null): List<Screened> = coroutineScope {
        onProgress(if (snapshotIn == null) "正在实时拉取全市场行情…" else "复用本轮全市场快照…")
        // 全市场快照被东财风控拒绝时退回代码池（自选），否则候选永为 0、推股名单恒空，
        // 而用户只看到「暂无数据」——不知道是被限流。
        val snap = snapshotForRequest(snapshotIn) {
            LiveMarket.snapshot().ifEmpty {
                onProgress("全市场行情不可用，改用自选池（${fallbackCodes.size} 只）深算；量比/资金流不可得")
                poolSnap(fallbackCodes)
            }
        }
        if (snap.isEmpty()) return@coroutineScope emptyList()

        // 板块强度表：只用于排序加权，映射表拿不到就整块降级（不拖垮筛选，也不编强度）
        val board = cancellable { SectorLink.load() }.getOrNull()
        // 板块聚合只算一次，结果同时给「排序加权」和 rotation 引擎：分两次算会得到两个时点的热度。
        val sectorsRes: LocalSectors.Result? = board?.let { b ->
            cancellable { LocalSectors.aggregate(snap, sectorOf = { b.sectorNameOf(it) }) }.getOrNull()
        }
        val strengthByName: Map<String, Double> = sectorsRes?.rows
            ?.mapNotNull { r -> r.strength?.let { s -> r.name to s } }?.toMap().orEmpty()

        // 横截面（flow 分位）与广度一轮只算一次：逐票重算 = 5000×5000 次比较。
        val breadth: Double? = cancellable {
            val up = snap.count { (it.pctChg ?: 0.0) > 0.0 }
            val down = snap.count { (it.pctChg ?: 0.0) < 0.0 }
            if (up + down > 0) up.toDouble() / (up + down) else null
        }.getOrNull()
        // 盘中早期换手率是「已交易分钟」口径，按已过分钟还原成全天预估，否则一律判低换手。
        val turnScale: Double = cancellable {
            val m = TapeEngine.minuteOfDay(System.currentTimeMillis())
            if (m > 570 && m < 897) (240.0 / (m - 570).toDouble()).coerceIn(1.0, 16.0) else 1.0
        }.getOrDefault(1.0)
        val parameters = CentralAnalysis.loadParameters()
        val flowOf: Map<String, FlowEngine.Result> = cancellable {
            FlowEngine.screen(snap, emptyMap(), emptyMap(), breadth, turnScale, EngineChain.flowParams(parameters.spec)).associateBy { it.code }
        }.getOrDefault(emptyMap())
        val limitup = mode == "limitup"
        onProgress("全市场快照 ${snap.size} 只，预筛中（${if (limitup) "涨停扫描" else "涨幅/量比/非ST/主板"}）…")

        // 预筛（含主板前缀红线过滤，排除 300/301/688/北交所）
        val pool = snap.filter { row ->
            val chg = row.pctChg ?: return@filter false
            val name = row.name.orEmpty()
            val st = c.excludeST && NameRules.isSTOrDelisting(name)
            val main = Code.isMainBoard(row.code) && !st
            when {
                limitup -> main && chg >= MarketLimits.LIMIT_UP_SCAN_PCT
                else -> main && chg >= c.minChgPct && chg < c.maxChgPct &&
                    // 跨模块属性没法智能转换，先落局部值
                    (row.volRatio?.let { it >= c.minVolRatio } != false)
            }
        }
        // 预筛排序：多维度秩和，不再只用「主力净流入」一票定生死。
        //   旧做法：按 mainNet 降序取前 N —— 候选池会被「大市值 + 已大额流入」的票锁死，
        //   量比很高但主力净流出（换手/洗盘）的票永远进不了深算，推股结果偏得很有规律。
        val cands = if (limitup) pool.sortedByDescending { it.pctChg ?: 0.0 }.take(50)
        else preselect(pool, c.maxCandidates, rotation)

        // 市况闸门只吃指数序列：全轮拉一次，不随个股变化。
        // 拿不到就传 null，链路会记 degraded 并把闸门按 1.0，不拿个股 K 线充数。
        val indexBars = cancellable { IndexDaily.fetch()?.bars.orEmpty() }.getOrDefault(emptyList())
        val regimeCloses = indexBars.map { it.close }

        onProgress("预筛命中 ${cands.size} 只，逐只拉 K 线 + 本地引擎计算…")

        // 逐只拉 K 线 + 本地引擎（并发 4，避免打满）
        val out = ArrayList<Screened>()
        cands.chunked(4).forEach { batch ->
            val results = batch.map { m ->
                async(Dispatchers.IO) {
                    // 单只超时：一只票的 K 线接口卡住不该拖垮整轮全市场扫描
                    kotlinx.coroutines.withTimeoutOrNull(PER_STOCK_TIMEOUT_MS) {
                    analyzeCandidate(m, c, dropLastBar,
                        evidenceOverride = evidenceByCode[m.code], indexBars = indexBars,
                        sectorName = board?.sectorNameOf(m.code),
                        sectorStrength = board?.sectorNameOf(m.code)?.let { strengthByName[it] },
                        evaluate = { bars, nowMs ->
                            if (bars.size >= 30) cancellable {
                        EngineChain.analyze(
                            EngineChain.Inputs(
                                bars = bars,
                                row = m,
                                precomputedFlow = flowOf[m.code],
                                breadth = breadth,
                                sectors = sectorsRes,
                                turnScale = turnScale,
                                regimeCloses = regimeCloses,
                                nowMs = nowMs, code = m.code, news = news, newsAvailable = newsAvailable,
                            ), parameters.spec
                        ).let { result -> result.copy(degraded = result.degraded + parameters.notes,
                            status = if (parameters.notes.isNotEmpty() && result.status == "ok") "partial" else result.status) }
                    }.getOrNull() else null
                        })
                    }
                }
            }.mapNotNull { cancellable { it.await() }.getOrNull() }
            out.addAll(results)
            onProgress("本地引擎已分析 ${out.size}/${cands.size} 只…")
        }

        when (mode) {
            // 涨停仅观察/归因，不进入普通推荐出口。
            "limitup" -> return@coroutineScope out.filter { (it.pctChg ?: 0.0) >= MarketLimits.LIMIT_UP_SCAN_PCT }
                .sortedByDescending { it.pctChg ?: 0.0 }
            // 共振页返回全部分析结果（含未达标），由 UI 自行排序。
            "all" -> return@coroutineScope out.sortedByDescending { it.canonicalScore ?: Double.NEGATIVE_INFINITY }
        }
        val scored = out.filter {
            // 缺失主链分就是证据不足，绝不拿旧 central 填洞。
            val canonicalOk = it.canonicalScore?.let { score -> score.isFinite() && score >= c.minCanonical } == true
            val riskOk = !c.vetoByRiskFlag || it.riskFlag == null
            it.detailStatus == "ok" && (it.canonicalScore ?: Double.NEGATIVE_INFINITY) >= c.minScore &&
                it.snapshotStatus == "live" && canonicalOk && riskOk && it.buyable == true &&
                (it.pctChg ?: Double.POSITIVE_INFINITY) < c.maxChgPct &&
                // 普通推荐不接受涨停扫描结果，即使上游误标了可买。
                (it.pctChg ?: Double.POSITIVE_INFINITY) < MarketLimits.LIMIT_UP_SCAN_PCT
        }.sortedByDescending { it.effectiveScore ?: it.canonicalScore ?: 0.0 }
        if (scored.isNotEmpty()) return@coroutineScope scored
        // 评分不足时只展示可买且有可用本地引擎的实时结果，不把 insufficient 伪装成推荐。
        onProgress("评分达标 0 只，暂无可买推荐（数据不足或过滤涨停）")
        emptyList()
    }
    internal suspend fun analyzeCandidate(
        m: LiveMarket.Row, c: Criteria, dropLastBar: Boolean,
        evidenceOverride: DataEvidence? = null,
        indexBars: List<KBar> = emptyList(), sectorName: String? = null, sectorStrength: Double? = null,
        barsProvider: suspend (String, Int) -> List<KBar> = { code, count -> DataCache.kline(code, "qfq", count) },
        nowMs: Long = System.currentTimeMillis(),
        inputTransport: StockInputTransport? = null,
        inputProvider: suspend (String, List<KBar>) -> StockAnalysisInputs = { code, bars ->
            if (inputTransport == null) Db.stockAnalysisInputs(code, bars, nowMs)
            else Db.stockAnalysisInputs(code, bars, nowMs, inputTransport)
        },
        evaluate: suspend (List<KBar>, Long) -> EngineChain.ChainResult? = { _, _ -> null },
    ): Screened {
        val code = m.code
        val barsRaw = barsProvider(code, c.klineCount)
        val original = barsRaw.filter { AnalysisSessionPolicy.acceptsBar(it.date, nowMs, dropLastBar) }
        val input = cancellable { inputProvider(code, original) }.getOrElse {
            StockAnalysisInputs(code, bars = original, barsFingerprint = StockAnalysisInputs.barsFingerprint(original),
                missing = listOf("input_provider_unavailable"))
        }
        // 只剔除 CST 当日（以及非法/未来日期），不能误删昨日已完成 bar。
        val bars = input.bars.filter {
            AnalysisSessionPolicy.acceptsBar(it.date, nowMs, dropLastBar)
        }
        val eng = if (bars.size >= 30) DataCache.engine(code, "qfq", bars) else null
        val chain = evaluate(bars, nowMs)
        // 仅使用同轮已取到的真实上下文；没有5日板块/外围数据就保留缺项，不拿1日值冒充。
        val earlyContext = EarlyVoteEngine.Context(
            indexCloses = indexBars.map { it.date to it.close },
            mainNetToday = m.mainNet,
        )
        val earlyTomorrow = EarlyVoteEngine.tomorrow(bars, earlyContext)
        val earlyMorning = EarlyVoteEngine.morning(bars, earlyContext)
        val primary = chain?.canonicalScore?.takeIf { it.isFinite() }
        val evidence = evidenceOverride ?: DataEvidence(input.eps, input.dps, input.reportPeriod,
            input.adjustmentEvidence, input.source, input.snapshotAtMs, input.expiresAtMs,
            input.code, input.barsFingerprint, input.gaps(nowMs))
        val evidenceOk = evidence.validFor(code, bars, nowMs) && evidence.missing.isEmpty()
        val safetyInputsReady = bars.size >= 30 && evidenceOk &&
            chain?.flow?.score?.isFinite() == true && chain.tape?.score?.isFinite() == true
        val chainUsable = primary != null && chain?.status != "insufficient" && safetyInputsReady
        // 退出纪律：破位（收盘跌破 20 日最低）的票一票否决，不再进推荐
        val exit = if (bars.size >= ExitPlan.LOOKBACK + 1) {
            cancellable { ExitPlan.compute(bars) }.getOrNull()
        } else null
        return Screened(
            code = code,
            name = m.name,
            price = m.price,
            pctChg = m.pctChg,
            volRatio = m.volRatio,
            // 对外语义保持「成交额」：界面上的「成交」列读的是 amount
            turnover = m.amount,
            pe = m.pe,
            mv = m.mv,
            engine = eng,
            chain = chain,
            detailStatus = if (chainUsable) "ok" else "insufficient",
            reason = when {
                !evidenceOk -> "insufficient_data：" + (evidence.missing.ifEmpty { listOf("code/bars_binding/财务或同源复权证据") }).joinToString("；")
                !chainUsable -> "多输入链路证据不足：" + (chain?.degraded?.joinToString("；") ?: "K线或链路不可得")
                exit?.action == ExitPlan.ACT_CLEAR -> "已破位：" + exit.reasons.first()
                exit?.action == ExitPlan.ACT_TRIM -> "高位回吐过半：" + (exit.reasons.firstOrNull() ?: "")
                else -> null
            },
            exitPlan = exit,
            buyable = (m.price?.let { it > 0.0 } == true) &&
                (m.pctChg ?: Double.POSITIVE_INFINITY) < c.maxChgPct &&
                // 破位票不可买：跌穿 20 日最低的票，「评分高」只是下跌惯性
                exit?.action != ExitPlan.ACT_CLEAR,
            // 快照新鲜度如实标出来：LiveMarket 拉不到新数据时会退回上一份缓存，
            // 那这时结果就不是「实时」而是「旧值」，界面必须能区分（5 分钟内算实时）
            snapshotStatus = FreshnessPolicy.snapshotStatus(m.ts, nowMs),
            sector = sectorName,
            sectorStrength = sectorStrength,
            effectiveScore = effectiveScoreOf(primary, sectorStrength),
            dataEvidence = evidence,
            earlyTomorrow = earlyTomorrow,
            earlyMorning = earlyMorning,
            bars = bars,
            safetyInputsReady = safetyInputsReady,
            snapshotAtMs = m.ts.takeIf { it > 0L },
            snapshotSource = "LocalScreener/LiveMarket.Row",
        )
    }

    /** 单只股票的 K 线 + 引擎计算超时（毫秒）。 */
    private const val PER_STOCK_TIMEOUT_MS = 20_000L

    /** 板块强度对排序分的最大影响：±10 分（板块强度 0 / 100 时）——够挪一档，改不动「够不够格」。 */
    private const val SECTOR_BOOST_K = 0.2

    /**
     * 排序分 = 个股综合分 + 板块偏移。
     *
     * 板块未知时原样返回个股分，不拿 50 分（中性）顶替：本项目板块映射只覆盖 2706/5900，
     * 拿中性垫底等于「没映射到的票不动」，但拿 0 分顶替会让 46% 的票被系统性降权。
     */
    internal fun effectiveScoreOf(central: Double?, sectorStrength: Double?): Double? =
        central?.let { if (sectorStrength != null) it + (sectorStrength - 50.0) * SECTOR_BOOST_K else it }

    /**
     * 候选池预选：涨幅 / 量比 / 成交额 / 主力净流入 四维**秩和**，各占权重。
     *
     * 只用秩（percentile）不用原值，是因为四个量纲没法直接比：量比 3 倍与主力净流入 3 亿
     * 谁更值得深算，只有「各自在本池里排第几」可以相加。权重上量比最高——
     * 放量是本地引擎最能确认的先行信号，涨幅已经被预筛区间框住了。
     */
    internal fun preselect(
        pool: List<LiveMarket.Row>,
        limit: Int,
        rotation: Int = 0,
    ): List<LiveMarket.Row> {
        if (pool.size <= limit) return pool
        fun pct(get: (LiveMarket.Row) -> Double?): DoubleArray {
            val idx = pool.indices.filter { get(pool[it]) != null }
                .sortedByDescending { get(pool[it])!! }
            val out = DoubleArray(pool.size) { 0.0 }
            if (idx.size <= 1) {
                idx.forEach { out[it] = 1.0 }
                return out
            }
            idx.forEachIndexed { rank, i -> out[i] = 1.0 - rank.toDouble() / (idx.size - 1) }
            return out
        }
        val vol = pct { it.volRatio }
        val chg = pct { it.pctChg }
        val amt = pct { it.amount }
        val flow = pct { it.mainNet }
        val scored = pool.mapIndexed { i, row ->
            row to (0.35 * vol[i] + 0.25 * chg[i] + 0.20 * amt[i] + 0.20 * flow[i])
        }
        val ordered = scored.sortedByDescending { it.second }.map { it.first }
        if (rotation <= 0) return ordered.take(limit)

        // 轮换：每轮只深算 30 只，若永远取预评分前列，其余 5000 只**永远不会被深算**。
        // 按档位把名额整体后移（环形），N 轮之内全市场都能轮到一次；
        // 档位用整数而不是随机，保证同一档结果可复现、可测。
        val start = (rotation.toLong() * limit % ordered.size).toInt()
        val rotated = ordered.drop(start) + ordered.take(start)
        return rotated.take(limit)
    }

    /** 全市场铺开需要多少轮（预选上限 × 轮数 ≥ 池子大小）。供心跳记录「覆盖进度」。 */
    internal fun rotationSpan(poolSize: Int, limit: Int): Int =
        if (limit <= 0 || poolSize <= limit) 1 else ((poolSize + limit - 1) / limit)
}