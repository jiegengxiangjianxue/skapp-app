package com.skapp.app.data

/**
 * 本地盘前/午盘推股产出层。
 *
 * 背景：实时总结里的「盘前推股」「午盘推股」原先只吃云端 CI 产物（数据停在上一轮产出，
 * 盘中打开经常是过期/别人的时间口径）。这里改成设备本地生成，只依赖两条真实链路：
 *   快照（东财 clist） + 日 K（腾讯→东财） → 本地 12 引擎 / 29 因子打分
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
        val eng = s.engine ?: return@buildList
        if (eng.bullCount > 0) add("12 引擎看多 ${eng.bullCount} 个")
        if (eng.bearCount > 0) add("看空 ${eng.bearCount} 个")
        if (eng.nValid > 0) add("有效引擎 ${eng.nValid}/${eng.engines.size}")
        eng.resonanceRatio?.let { add("共振 ${"%.0f".format(it * 100)}%") }
        s.volRatio?.let { add("量比 ${"%.2f".format(it)}") }
        s.pctChg?.let { add("涨幅 ${"%+.2f%%".format(it)}") }
        eng.engines.filter { it.signal == "bullish" && it.score != null }
            .sortedByDescending { it.score ?: 0.0 }
            .take(2)
            .forEach { add("${it.engine} ${"%.0f".format(it.score!!)}") }
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
        engine = "本地中枢引擎 · 12 引擎 / 29 因子",
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
    fun build(
        screened: List<LocalScreener.Screened>,
        session: Session,
        calcTime: String,
        scanMode: String = "score",
        droppedLastBar: Boolean = session == Session.PREMARKET,
        totalSnapshot: Int? = null,
    ): StructuredPickArtifact {
        val usable = screened.filter { it.detailStatus == "ok" && it.centralScore != null }
        val qualified = usable
            .filter {
                (it.centralScore ?: 0.0) >= MIN_SCORE &&
                    it.buyable == true &&
                    (it.pctChg ?: Double.POSITIVE_INFINITY) < MAX_CHG_PCT
            }
            .sortedByDescending { it.centralScore ?: 0.0 }

        val observed = usable
            .filter { it.code !in qualified.map { q -> q.code }.toSet() }
            .sortedByDescending { it.centralScore ?: 0.0 }
            .take(MAX_OBSERVED)

        val picks = qualified.take(MAX_PICKS).map { toSignal(it, calcTime) }

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
            if (totalSnapshot != null) put("snapshot", totalSnapshot)
            if (observed.isNotEmpty()) {
                put(
                    "observed",
                    observed.map {
                        mapOf(
                            "code" to it.code,
                            "name" to it.name,
                            "score" to it.centralScore,
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
                else -> "本地深算 ${usable.size} 只，达标（评分≥${MIN_SCORE.toInt()} 且未涨停）0 只，不编造推荐。"
            }
            return StructuredPickArtifact(
                generatedAt = calcTime,
                calculationTime = calcTime,
                date = calcTime.take(10),
                session = session.key,
                engine = "本地中枢引擎 · 12 引擎 / 29 因子",
                status = "insufficient",
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
            engine = "本地中枢引擎 · 12 引擎 / 29 因子",
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
