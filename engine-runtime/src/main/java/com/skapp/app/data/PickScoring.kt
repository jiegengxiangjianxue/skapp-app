package com.skapp.app.data

/**
 * L3 编排层：把「跨引擎集成 → 市场 gate → 横截面分位 → 达标判定」串成一条纯函数链。
 *
 * 为什么单独抽出来：这段顺序以前散在 [LocalPicks] 里（先按 centralScore 过滤再排序），
 * 一旦加 gate 就只能改一处改一次、每改一次都要重跑全市场，没法单测。
 * 现在顺序写死在这一个函数里，[LocalPicks] 只负责取数与产物封装。
 *
 * ## 顺序不能乱
 * 1. **集成**：有云端权重就用 `Σw·score/Σw`（未登记引擎按 1.0 兜底，保持历史行为）；
 *    没有就用中枢分。集成分只决定**排序**，不参与达标判定。
 * 2. **gate**：`50 + (score-50)×gate`。gate 乘的是「对中性 50 的偏离」——直接把分数乘 0.5
 *    会把中性 50 压成 25，等于给全市场额外扣一次分，与云端 `S = 100×gate×Σv·D` 的量纲不等价。
 * 3. **分位**：在**本轮候选池**内算（不是全市场），池子口径由调用方写进产物文案。
 * 4. **达标**：`gated ≥ minScore 且 buyable 且 涨幅 < maxChg`。
 *    弱市里 gate=0.5 会让 62 分的票折到 56、55 分门槛因此更难过——这正是想要的效果。
 */
object PickScoring {

    /**
     * spec 引擎 id → 端上 [LocalEngineId] 的 key。只登记**两端都有**的那 8 个；
     * 其余（rsi/kdj/cmo/donchian/vwma/hull_ma/elder_force/connors_rsi/ultimate_osc）
     * 端上 12 引擎里没有对应实现，集成时**不参与**——不拿别的引擎顶替，
     * 否则「云端权重已接入」这句话就是假的了。
     */
    val CLOUD_TO_LOCAL: Map<String, String> = linkedMapOf(
        "supertrend" to "supertrend",
        "macd" to "macd",
        "vortex" to "vortex",
        "choppiness" to "choppiness",
        "boll_keltner" to "boll",
        "ma_golden" to "trend",
        "ad_line" to "obv",
        "pivot" to "position",
    )

    /** 云端权重（spec id）→ 端上引擎 key；没有对应端上引擎的进返回的 second。 */
    fun alignWeights(cloudWeights: Map<String, Double>): Pair<Map<String, Double>, List<String>> {
        val aligned = LinkedHashMap<String, Double>()
        val unmapped = mutableListOf<String>()
        for ((id, w) in cloudWeights) {
            val local = CLOUD_TO_LOCAL[id]
            if (local == null) unmapped += id else aligned[local] = w
        }
        return aligned to unmapped
    }

    /** 输入行：只保留判定需要的字段，避免把整个 Screened 拖进纯函数。 */
    data class Row(
        val code: String,
        val central: Double?,
        val buyable: Boolean?,
        val pctChg: Double?,
        /** 引擎 key（端上 [LocalEngineId.key]）→ 该引擎给这只票的分。 */
        val engineScores: Map<String, Double> = emptyMap(),
        /** 端上`effectiveScore`（中枢分 + 板块偏移）：只影响**排序**，不影响达标判定。 */
        val effective: Double? = null,
    )

    data class Scored(
        val code: String,
        val central: Double?,
        /** 跨引擎集成分（无权重时为 null，交给 central 兜底）。 */
        val integrated: Double?,
        /** gate 折价后的终审分（达标判定用这个）。 */
        val gated: Double?,
        /** gate 折价后的排序分（effective 优先，没有则同 gated）。 */
        val rankScore: Double?,
        /** 池内分位 0..1。 */
        val percentile: Double?,
        val tier: String?,
        val qualified: Boolean,
        val gate: Double,
    )

    /**
     * 跨引擎集成：`Σw·score / Σw`。
     * 权重取云端值，未登记的按 1.0（[EngineWeights.FALLBACK] 的历史行为），
     * 没有可用引擎分时返回 null（不拿中枢分冒充集成分）。
     */
    fun integrate(row: Row, weights: Map<String, Double>): Double? {
        var num = 0.0
        var den = 0.0
        for ((id, s) in row.engineScores) {
            val w = weights[id] ?: EngineWeights.FALLBACK
            num += w * s
            den += w
        }
        return if (den > 0.0) num / den else null
    }

    /**
     * 完整链路。返回按 gated 降序（null 排最后）。
     *
     * @param gate 市场环境系数，缺省 1.0（= 不折价，保持既有行为）
     */
    fun score(
        rows: List<Row>,
        weights: Map<String, Double> = emptyMap(),
        gate: Double = 1.0,
        minScore: Double,
        maxChgPct: Double,
    ): List<Scored> {
        if (rows.isEmpty()) return emptyList()
        // 权重为空时**不**做集成：否则「12 个引擎的等权平均」会被当成云端集成结果，
        // 悄悄改变达标名单（等于把 cold_start 演成 cloud）。这是本函数最容易踩的一脚。
        val integrated = if (weights.isEmpty()) rows.map { null }
        else rows.map { integrate(it, weights) }
        val base = rows.mapIndexed { i, r -> integrated[i] ?: r.central }
        val gated = base.map { it?.let { s -> RegimeGate.applyGate(s, gate) } }
        val ranked = rows.mapIndexed { i, r ->
            RegimeGate.applyGate(r.effective ?: integrated[i] ?: r.central, gate)
        }
        val pct = CrossSectionRank.percentilesWithMedianFill(ranked)
        return rows.mapIndexed { i, r ->
            val g = gated[i]
            Scored(
                code = r.code,
                central = r.central,
                integrated = integrated[i],
                gated = g,
                rankScore = ranked[i],
                percentile = pct[i],
                tier = CrossSectionRank.tier(pct[i]),
                qualified = g != null && g >= minScore &&
                    r.buyable == true &&
                    (r.pctChg ?: Double.POSITIVE_INFINITY) < maxChgPct,
                gate = gate,
            )
        }.sortedWith(compareByDescending<Scored> { it.rankScore ?: Double.NEGATIVE_INFINITY })
    }

}
