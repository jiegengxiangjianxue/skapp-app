package com.skapp.app.data

/**
 * 市场环境闸门 —— 端上复刻云端 `regime_router.judge_regime`。
 *
 * 口径唯一真值：`stocks-ai-view/scripts/engines/regime_router.py:287-363`
 *
 * 为什么必须有它：云端终审分是 `S = 100 × gate × Σ v_j·D_j`，`gate` 由 regime 决定
 * （bull 1.0 / range 0.8 / bear 0.5，`regime_router.py:97`）。端上 `LocalScreener`
 * 一直用单一分数线（55 分）判进门，等于把这层统一折价丢了：同一只票在熊市里
 * 应当更难被选中，而端上口径下它与牛市同分。
 *
 * 只吃**指数收盘序列**，不需要任何外部快照 —— 所以端上能本地算，
 * 这也是它被优先补齐的原因（其余云端引擎多依赖全市场历史库）。
 *
 * 已知口径残余（云端自己就有，端上不「修」）：
 *  - 三条均线全部站上即可得 3 分，于是横盘微涨（如 300 根 ±0.05% 震荡）也会判 bull
 *    （实测 score=2.5 → bull）。这是云端行为，端上照抄，改判据要两边一起改。
 */
object RegimeGate {

    /** bull / range / bear，gate 与云端 `DEFAULT_GATES` 逐值一致。 */
    enum class Regime(val id: String, val label: String, val gate: Double) {
        BULL("bull", "多头", 1.0),
        RANGE("range", "震荡", 0.8),
        BEAR("bear", "空头", 0.5),
    }

    data class Point(val key: String, val value: Double, val points: Double)

    data class Result(
        val regime: Regime,
        /** 累加分，范围约 [-5.5, 5.5]。 */
        val score: Double,
        /** min(1, |score| / 5.5 + 0.3)，云端同式。 */
        val confidence: Double,
        val evidence: List<Point>,
    ) {
        val gate: Double get() = regime.gate
    }

    private const val MAX_SCORE = 5.5
    private const val BULL_AT = 2.0
    private const val BEAR_AT = -2.0
    private const val SLOPE_LAG = 5

    /**
     * @param closes 指数收盘序列，升序；`index` 缺省为最后一根（判定「今天」）。
     *   ≥250 根时三条均线与回撤全参与；60~249 根会少 2 个点，属正常降级。
     */
    fun judge(closes: List<Double>, index: Int = closes.lastIndex): Result? {
        if (closes.size < 21 || index < 0 || index >= closes.size) return null
        val c = closes[index]
        if (c <= 0.0) return null
        val evidence = mutableListOf<Point>()
        var score = 0.0

        fun smaAt(i: Int, n: Int): Double? {
            if (i + 1 < n) return null
            var s = 0.0
            for (j in i - n + 1..i) s += closes[j]
            val v = s / n
            return if (v == 0.0) null else v
        }

        val ma20 = smaAt(index, 20)
        val ma60 = smaAt(index, 60)
        val ma250 = smaAt(index, 250)

        fun slopePct(n: Int): Double? {
            val a = smaAt(index, n) ?: return null
            return if (index - SLOPE_LAG < n - 1) null
            else {
                val b = smaAt(index - SLOPE_LAG, n) ?: return null
                (a - b) / b * 100
            }
        }

        ma20?.let {
            val pts = if (c > it) 1.0 else -1.0
            score += pts
            evidence += Point("close_vs_ma20", CanonicalIndicators.pyRound((c / it - 1) * 100, 2), pts)
        }
        ma60?.let {
            val pts = if (c > it) 1.0 else -1.0
            score += pts
            evidence += Point("close_vs_ma60", CanonicalIndicators.pyRound((c / it - 1) * 100, 2), pts)
        }
        ma250?.let {
            val pts = if (c > it) 1.0 else -1.0
            score += pts
            evidence += Point("close_vs_ma250", CanonicalIndicators.pyRound((c / it - 1) * 100, 2), pts)
        }
        listOf(20, 60, 250).forEach { n ->
            slopePct(n)?.let { s ->
                val pts = if (s > 0) 0.5 else -0.5
                score += pts
                evidence += Point("ma${n}_slope_5d_pct", CanonicalIndicators.pyRound(s, 3), pts)
            }
        }

        val from = maxOf(0, index - 250)
        var hi = Double.NEGATIVE_INFINITY
        for (j in from..index) if (closes[j] > hi) hi = closes[j]
        val dd = if (hi > 0) (c - hi) / hi * 100 else 0.0
        val ddPts = when {
            dd >= -8.0 -> 1.0
            dd >= -15.0 -> 0.0
            else -> -1.0
        }
        score += ddPts
        evidence += Point("drawdown_from_250d_high_pct", CanonicalIndicators.pyRound(dd, 2), ddPts)

        val regime = when {
            score >= BULL_AT -> Regime.BULL
            score <= BEAR_AT -> Regime.BEAR
            else -> Regime.RANGE
        }
        val conf = minOf(1.0, kotlin.math.abs(score) / MAX_SCORE + 0.3)
        return Result(
            regime = regime,
            score = CanonicalIndicators.pyRound(score, 3),
            confidence = CanonicalIndicators.pyRound(conf, 3),
            evidence = evidence,
        )
    }

    /** 便捷入口：只要 gate 系数（拿不到指数序列时返回 null，调用方按 1.0 处理并标注）。 */
    fun gateOf(closes: List<Double>): Double? = judge(closes)?.gate

    /**
     * 把 gate 作用到个股分上。
     *
     * 云端是乘在**终审 S** 上（`central_orchestrator.py:441`），而端上 `centralScore`
     * 是 12 引擎均值、天然聚在 50 附近，直接乘 gate 会把 50 分压到 40（熊市）看似合理，
     * 但也会把 70 分压到 56 —— 与云端 S 的量纲并不等价。
     * 因此这里统一用「相对中性的偏离量折价」：`50 + (score-50) × gate`，
     * 既保留 gate 的方向，又不改变 50 分的中性语义。
     */
    fun applyGate(score: Double?, gate: Double): Double? =
        if (score == null) null else (50.0 + (score - 50.0) * gate).coerceIn(0.0, 100.0)
}
