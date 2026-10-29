package com.skapp.app.data

/**
 * 本地盘前/午盘推股产出层。
 *
 * 背景：实时总结里的「盘前推股」「午盘推股」原先只吃云端 CI 产物（数据停在上一轮产出，
 * 盘中打开经常是过期/别人的时间口径）。这里改成设备本地生成，只依赖两条真实链路：
 *   快照（东财 clist） + 日 K（腾讯→东财） → 本地 12 引擎 / 因子库全量因子打分
 *
 * 口径红线（两条必须守住，否则就是拿未来数据编结论）：
 *   1) 盘前口径：日 K 必须剔除最后一根（dropLastBar=true），只能看截至上一交易日收盘的指标；
 *      盘中打开也不会把当日未完成 bar 混进「盘前」这张卡。
 *   2) 达标才出：只有 detailStatus=ok 且 centralScore >= minScore 且可买（未涨停）才进 picks。
 *      不达标的票只以 metadata 的「观察」形式出现，并显式标注未达标 —— 不把 insufficient 伪装成推荐。
 */
object LocalPicks {

    /** 推股时段：决定口径与卡片文案，两者存档互不覆盖。 */
    enum class Session(
        val key: String,
        val label: String,
        val basis: String,
    ) {
        PREMARKET(
            key = "premarket",
            label = "盘前推股",
            basis = "本地深算 · 口径：截至上一交易日收盘（剔除当日未完成日 K）",
        ),
        MIDDAY(
            key = "midday",
            label = "午盘推股",
            basis = "本地深算 · 口径：当日实时行情（含当日未完成日 K）",
        ),
    }

    /** 达标门槛，与 LocalScreener.Criteria.minScore 保持一致，避免两处漂移。 */
    const val MIN_SCORE = 55.0

    /** 推股不追涨停：主板涨停附近(≥9.6%)不可买入 */
    const val MAX_CHG_PCT = 9.6

    /** 卡片上最多展示多少条 */
    const val MAX_PICKS = 10

    /** 未达标观察位最多保留几条 */
    const val MAX_OBSERVED = 3

    private fun buildWhy(s: LocalScreener.Screened): List<String> = buildList {
        // 排序就按板块强度加过权，理由里不写出来，用户会以为顺序还是纯个股分。
        // 放在 engine 判空之前：K 线不足的观察位同样要能看到板块归属。
        s.sector?.let { name ->
            val st = s.sectorStrength
            add(
                if (st != null) "板块 $name · 强度 ${"%.0f".format(st)}（已按 ±10 分参与排序）"
                else "板块 $name（强度数据不足，未参与排序）",
            )
        }
        val eng = s.engine ?: return@buildList
        if (eng.bullCount > 0) add("12 引擎看多 ${eng.bullCount} 个")
        if (eng.bearCount > 0) add("看空 ${eng.bearCount} 个")
        if (eng.nValid > 0) add("有效引擎 ${eng.nValid}/${eng.engines.size}")
        eng.resonanceRatio?.let { add("族级共振 ${"%.0f".format(it * 100)}%（按趋势/动量/通道/量能/波动/位置 6 族算，同族不重复计票）") }
        s.volRatio?.let { add("量比 ${"%.2f".format(it)}") }
        s.pctChg?.let { add("涨幅 ${"%+.2f%%".format(it)}") }
        eng.engines.filter { it.signal == "bullish" && it.score != null }
            .sortedByDescending { it.score ?: 0.0 }
            .take(2)
            .forEach { add("${it.engine} ${"%.0f".format(it.score!!)}") }
        // 退出纪律跟着推荐一起给：买点旁边必须放着卖点，否则用户只能靠感觉走
        s.exitPlan?.stopLine?.let { stop ->
            add("清仓线 " + "%.2f".format(stop) + "（20 日最低）")
        }
        s.exitPlan?.trailLine?.let { trail ->
            add("止盈线 " + "%.2f".format(trail) + "（回吐波幅一半）")
        }
        if (isEmpty()) add("本地引擎已跑通，但无单因子给出强信号")
    }

    private fun toSignal(s: LocalScreener.Screened, calcTime: String): PickSignal = PickSignal(
        code = s.code,
        name = s.name,
        price = s.price,
        pctChg = s.pctChg,
        score = s.centralScore,
        buyable = s.buyable,
        snapshotStatus = s.snapshotStatus,
        engine = "本地中枢引擎 · 12 引擎 / ${ExtendedIndicators.factorCount} 因子",
        resonance = s.resonanceRatio,
        reason = s.centralSummary ?: s.reason,
        calculationTime = calcTime,
        why = buildWhy(s),
    )

    /**
     * 把一次本地全市场深算的结果转成「盘前/午盘推股」产物。
     *
     * @param screened     LocalScreener.screen() 的原始输出（含未达标）
     * @param session      推股时段，决定卡片文案与口径说明
     * @param calcTime     计算时点（ISO 字符串）
     * @param scanMode     扫描模式，写进 metadata，便于排查
     * @param droppedLastBar 是否剔除过最后一根日 K（盘前口径必须为 true）
     */
    /**
     * 端到端入口：自己取指数日 K → 判 regime → 拉云参数 → 交给 [build]。
     *
     * 为什么要有它：`build` 是纯产物层（不联网、可单测），但真实调用点（ViewModel）
     * 不该为了一个 gate 系数去 import 三个新类。这里把「取数失败也要给出可解释的降级」
     * 收在一处：指数拿不到 → gate=1.0 且 gateLabel 写明原因，绝不假装已折价。
     *
     * 指数通道见 [IndexDaily]（holo 仓无指数，实测走腾讯/东财双源）。
     */
    suspend fun buildWithMarket(
        screened: List<LocalScreener.Screened>,
        session: Session,
        calcTime: String,
        scanMode: String = "all",
        droppedLastBar: Boolean = session == Session.PREMARKET,
        totalSnapshot: Int? = null,
        indexCode: String = IndexDaily.DEFAULT_INDEX,
    ): StructuredPickArtifact {
        val regime = runCatching {
            val closes = IndexDaily.closes(indexCode)
            if (closes.size < 21) null else RegimeGate.judge(closes)
        }.getOrNull()
        val cloud = runCatching { CloudParams.fromCloud() }.getOrNull()
        // 云端若已发布 gate 就用它（同一系数，避免两套），没发布才用端上自算。
        val gate = cloud?.regimeGate ?: regime?.gate ?: 1.0
        val label = when {
            cloud?.regimeGate != null -> "云端 regime gate $gate（${cloud.source}）"
            regime != null -> "${regime.regime.label} gate $gate（$indexCode 端上自算，置信 ${(regime.confidence * 100).toInt()}%）"
            else -> "gate 1.0（$indexCode 指数日 K 不可得，不折价）"
        }
        return build(
            screened = screened,
            session = session,
            calcTime = calcTime,
            scanMode = scanMode,
            droppedLastBar = droppedLastBar,
            totalSnapshot = totalSnapshot,
            gate = gate,
            gateLabel = label,
            cloud = cloud,
        )
    }

    fun build(
        screened: List<LocalScreener.Screened>,
        session: Session,
        calcTime: String,
        scanMode: String = "score",
        droppedLastBar: Boolean = session == Session.PREMARKET,
        totalSnapshot: Int? = null,
        /** 市场环境系数（[RegimeGate]）；1.0 = 不折价。拿不到指数时就传 1.0 并在 [gateLabel] 说明。 */
        gate: Double = 1.0,
        /** gate 的来历（如「多头/震荡/空头 + 指数代码」），写进产物供界面标注。 */
        gateLabel: String? = null,
        /** 云端参数板（[CloudParams.fromCloud]）；null = 全 cold_start。 */
        cloud: CloudParams.Board? = null,
    ): StructuredPickArtifact {
        val usable = screened.filter { it.detailStatus == "ok" && it.centralScore != null }
        // 跨引擎权重：云端 17 项里只有 8 项能在端上 12 引擎找到对应实现，其余不参与集成
        // （见 [PickScoring.CLOUD_TO_LOCAL]），未对齐的键如实写进 metadata。
        val aligned = PickScoring.alignWeights(cloud?.weights ?: emptyMap())
        val weights = aligned.first
        val scored = PickScoring.score(
            rows = usable.map { s ->
                PickScoring.Row(
                    code = s.code,
                    central = s.centralScore,
                    buyable = s.buyable,
                    pctChg = s.pctChg,
                    engineScores = s.engine?.engines
                        ?.mapNotNull { e -> e.score?.let { e.engine to it } }
                        ?.toMap()
                        ?: emptyMap(),
                    effective = s.effectiveScore,
                )
            },
            weights = weights,
            gate = gate,
            minScore = MIN_SCORE,
            maxChgPct = MAX_CHG_PCT,
        )
        val byCode = usable.associateBy { it.code }
        val scoredByCode = scored.associateBy { it.code }
        // 达标用 gated（gate 折价后的终审分），排序用 rankScore（effective 也折价）。
        // gate=1.0 且无权重时两者分别等于 centralScore / effectiveScore，与旧行为逐位一致。
        val qualified = scored
            .filter { it.qualified }
            .mapNotNull { byCode[it.code] }
            .sortedByDescending { scoredByCode[it.code]?.rankScore ?: 0.0 }
        val observed = usable
            .filter { it.code !in qualified.map { q -> q.code }.toSet() }
            .sortedByDescending { scoredByCode[it.code]?.rankScore ?: 0.0 }
            .take(MAX_OBSERVED)

        val picks = qualified.take(MAX_PICKS).map { toSignal(it, calcTime) }

        // 横截面分位（口径同云端 rank_score.quantile_score）：只在**本轮深算池**内算，
        // 不是全市场。池子口径必须写进 metadata，否则 0.9 会被读成「全市场前 10%」——
        // 那是另一件事，而且端上一轮只深算几十只，根本支撑不起全市场结论。
        val rankPct = scored.mapNotNull { sc -> sc.percentile?.let { sc.code to it } }.toMap()

        val metadata = buildMap<String, Any?> {
            put("session", session.key)
            put("basis", session.basis)
            put("scanMode", scanMode)
            put("droppedLastBar", droppedLastBar)
            put("analyzed", screened.size)
            put("usable", usable.size)
            put("qualified", qualified.size)
            put("minScore", MIN_SCORE)
            put("source", "local")
            put("gate", gate)
            put("gateLabel", gateLabel ?: "gate 1.0（无指数数据，不折价）")
            put("weightSource", cloud?.source ?: CloudParams.SOURCE_COLD_START)
            put("coldStart", cloud?.coldStart ?: true)
            put("weightApplied", weights.isNotEmpty())
            put("weightUnmapped", aligned.second)
            put("walkforward", cloud?.walkforward?.let { wf ->
                mapOf(
                    "isWin" to wf.isWin,
                    "oosWin" to wf.oosWin,
                    "overfit" to wf.overfit,
                    "verdict" to wf.verdict,
                )
            })
            put("cloudMissing", cloud?.missing ?: CloudParams.Board.COLD.missing)
            if (totalSnapshot != null) put("snapshot", totalSnapshot)
            if (rankPct.isNotEmpty()) {
                put("rankBasis", "横截面分位按本轮深算池 ${usable.size} 只算，非全市场")
                put(
                    "rank",
                    picks.mapNotNull { p ->
                        rankPct[p.code]?.let { pct ->
                            mapOf(
                                "code" to p.code,
                                "pct" to pct,
                                "tier" to CrossSectionRank.tier(pct),
                                "rankScore" to scoredByCode[p.code]?.rankScore,
                                "gated" to scoredByCode[p.code]?.gated,
                                "integrated" to scoredByCode[p.code]?.integrated,
                            )
                        }
                    },
                )
            }
            if (observed.isNotEmpty()) {
                put(
                    "observed",
                    observed.map {
                        mapOf(
                            "code" to it.code,
                            "name" to it.name,
                            "score" to it.centralScore,
                            "snapshotStatus" to it.snapshotStatus,
                        )
                    },
                )
            }
        }

        if (picks.isEmpty()) {
            val reason = when {
                totalSnapshot == 0 -> "本地行情快照为空（接口不可达或非交易时段无返回），不编造推股。"
                screened.isEmpty() -> "本地预筛命中 0 只，无候选可算。"
                usable.isEmpty() -> "候选池 ${screened.size} 只，但 K 线不足 30 根，无法本地算指标。"
                else -> "本地深算 ${usable.size} 只，达标（评分≥${MIN_SCORE.toInt()} 且未涨停）0 只：" +
                    "弱市不凑数，下方观察名单仅作跟踪，建议轻仓或空仓。"
            }
            // 有数据但一只都没达标 = 弱市（defensive），不是数据不足（insufficient）。
            // 两者在界面上含义完全不同：前者是「今天不该追」，后者是「还算不出来」。
            // 旧实现一律返回 insufficient，上层又把空 picks 当成「尚未完成」，
            // 于是已经算出来的观察名单被丢掉、首屏报的是一个不存在的状态。
            val status = if (usable.isNotEmpty()) "defensive" else "insufficient"
            return StructuredPickArtifact(
                generatedAt = calcTime,
                calculationTime = calcTime,
                date = calcTime.take(10),
                session = session.key,
                engine = "本地中枢引擎 · 12 引擎 / ${ExtendedIndicators.factorCount} 因子",
                status = status,
                reason = reason,
                picks = emptyList(),
                metadata = metadata,
            )
        }

        return StructuredPickArtifact(
            generatedAt = calcTime,
            calculationTime = calcTime,
            date = calcTime.take(10),
            session = session.key,
            engine = "本地中枢引擎 · 12 引擎 / ${ExtendedIndicators.factorCount} 因子",
            status = "ok",
            reason = "本地深算 ${usable.size} 只，达标 ${qualified.size} 只，展示前 ${picks.size} 只。",
            picks = picks,
            metadata = metadata,
        )
    }

    /** 当前时段该出哪张卡：盘前/竞价 → 盘前推股；午间/下午 → 午盘推股。 */
    fun sessionFor(phase: MarketHours.Phase): Session? = when (phase) {
        MarketHours.Phase.PRE_OPEN, MarketHours.Phase.AUCTION_OPEN -> Session.PREMARKET
        MarketHours.Phase.MORNING, MarketHours.Phase.LUNCH_BREAK,
        MarketHours.Phase.AFTERNOON, MarketHours.Phase.CLOSING -> Session.MIDDAY
        MarketHours.Phase.AFTER_HOURS, MarketHours.Phase.CLOSED_DAY -> null
    }
}
