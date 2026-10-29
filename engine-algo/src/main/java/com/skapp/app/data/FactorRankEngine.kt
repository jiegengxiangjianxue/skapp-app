package com.skapp.app.data

import kotlin.math.sqrt

/**
 * 横截面因子排序引擎：回答「同一批候选里该先买哪只」。
 *
 * ## 与 [CanonicalEngine] 的分工（不是二选一）
 *
 * - [CanonicalEngine]：**单票绝对评分**。21 个指标按云端 spec 标定投票聚合，回答「这只票现在强不强」，
 *   用绝对门槛（≥55 分才进候选）筛。
 * - 本引擎：**横截面相对排序**。在同一批候选内部做标准化后加权，回答「这几只里谁更强」。
 *
 * 两者互补：门槛由前者定，顺序由后者定。之前端上只有门槛没有排序——一批票都过线之后，
 * 界面上按涨幅或代码排，那是展示顺序，不是强度顺序。
 *
 * ## 为什么因子少而固定
 *
 * 用 5 个与 K 线直接相关的因子（动量/趋势/量能/位置/低波），每个都能一句话解释来源。
 * 不上更多因子是因为：端上没有全市场因子矩阵，做不了 IC 加权，硬堆因子只会引入共线噪声。
 * 真正需要重标定时，权重应来自云端 `engine_spec.json`（与指标权重同一处），而不是在这里散写。
 */
object FactorRankEngine {

    /** 因子权重。改动请同步云端规格；这里是端上默认值，云端给出权重时应覆盖。 */
    val DEFAULT_WEIGHTS: Map<String, Double> = linkedMapOf(
        "momentum" to 0.25,
        "trend" to 0.25,
        "volume" to 0.20,
        "position" to 0.15,
        "lowvol" to 0.15,
    )

    data class Input(val code: String, val bars: List<KBar>, val name: String? = null)

    data class Row(
        val code: String,
        val name: String?,
        /** 0~100，50 为横截面中位 */
        val score: Double,
        /** 1 为最强 */
        val rank: Int,
        /** 各因子的横截面 z 值（便于解释「凭什么是它」） */
        val factors: Map<String, Double>,
        val missing: List<String>,
    )

    data class Result(
        val rows: List<Row>,
        val missing: List<String>,
        val note: String,
    )

    /** 【因子】动量：近 20 日收盘涨幅。 */
    private const val MOMENTUM_N = 20

    /** 【因子】位置：近 60 日区间位置。高于此值视为追高，做扣分而不是加分。 */
    private const val CHASE_THRESHOLD = 0.95

    /** 至少要有几个因子有值才参与排序。少于 3 个的票排出来的名次没有意义。 */
    private const val MIN_FACTORS = 3

    fun rank(
        inputs: List<Input>,
        minBars: Int = 60,
        weights: Map<String, Double> = DEFAULT_WEIGHTS,
    ): Result {
        val missing = ArrayList<String>()
        if (inputs.isEmpty()) return Result(emptyList(), listOf("无候选"), "无候选")

        val raws = inputs.map { rawFactors(it, minBars) }
        raws.forEach { r ->
            if (r.values.count { it.value != null } < MIN_FACTORS) {
                missing += "${r.code} 可用因子不足 $MIN_FACTORS 个（${r.values.filterValues { it == null }.keys.joinToString("/")} 缺失）"
            }
        }
        val usable = raws.filter { it.values.count { v -> v.value != null } >= MIN_FACTORS }
        if (usable.isEmpty()) return Result(emptyList(), missing + "全部候选因子不足", "无可用排序")

        // 横截面标准化：每个因子内部算 mean / std，转成 z 值
        val stats = LinkedHashMap<String, Pair<Double, Double>>()
        for (f in weights.keys) {
            val vals = usable.mapNotNull { it.values[f] }
            if (vals.size < 2) {
                missing += "因子 $f 有效样本 ${vals.size} 个，本次不参与排序"
                continue
            }
            val mean = vals.average()
            val std = sqrt(vals.sumOf { (it - mean) * (it - mean) } / (vals.size - 1))
            if (std <= 0.0) {
                missing += "因子 $f 横截面无差异，本次不参与排序"
                continue
            }
            stats[f] = mean to std
        }
        if (stats.isEmpty()) return Result(emptyList(), missing + "所有因子均无区分度", "无可用排序")

        val scored = usable.map { r ->
            var sum = 0.0
            var wSum = 0.0
            val zs = LinkedHashMap<String, Double>()
            for ((f, ms) in stats) {
                val v = r.values[f] ?: continue
                val z = (v - ms.first) / ms.second
                zs[f] = round(z, 3)
                val w = weights[f] ?: 0.0
                sum += w * z
                wSum += w
            }
            // 50 为中位，15 约等于一个标准差的分值跨度；截断到 0~100 保持与其它评分同量纲
            val score = if (wSum <= 0.0) 50.0 else (50.0 + 15.0 * sum / wSum).coerceIn(0.0, 100.0)
            r to (round(score, 1) to zs)
        }

        val rows = scored
            .sortedByDescending { it.second.first }
            .mapIndexed { i, (r, sv) ->
                Row(
                    code = r.code,
                    name = r.name,
                    score = sv.first,
                    rank = i + 1,
                    factors = sv.second,
                    missing = r.missing,
                )
            }

        val note = "横截面排序：${rows.size} 只，因子 ${stats.keys.joinToString("/")}；" +
            "分数是相对强弱，不是买入理由"
        return Result(rows, missing, note)
    }

    // ── 单票因子 ────────────────────────────────────────────

    private data class Raw(val code: String, val name: String?, val values: Map<String, Double?>, val missing: List<String>)

    private fun rawFactors(input: Input, minBars: Int): Raw {
        val bars = input.bars
        val missing = ArrayList<String>()
        val f = LinkedHashMap<String, Double?>()

        if (bars.size < minBars) {
            missing += "K 线 ${bars.size} < $minBars"
            DEFAULT_WEIGHTS.keys.forEach { f[it] = null }
            return Raw(input.code, input.name, f, missing)
        }

        val closes = bars.map { it.close }

        // 动量
        f["momentum"] = if (closes.size > MOMENTUM_N && closes[closes.size - 1 - MOMENTUM_N] != 0.0) {
            closes.last() / closes[closes.size - 1 - MOMENTUM_N] - 1.0
        } else null

        // 趋势：MA5 / MA20 - 1
        val ma5 = ExtendedIndicators.sma(closes, 5).lastOrNull()
        val ma20 = ExtendedIndicators.sma(closes, 20).lastOrNull()
        f["trend"] = if (ma5 != null && ma20 != null && ma20 != 0.0) ma5 / ma20 - 1.0 else null

        // 量能：近 5 日均量 / 近 20 日均量 - 1
        val v5 = bars.takeLast(5).map { it.vol }.average()
        val v20 = bars.takeLast(20).map { it.vol }.average()
        f["volume"] = if (v20 > 0.0) v5 / v20 - 1.0 else null

        // 位置：近 60 日区间位置；追高（>0.95）按反向扣分
        val tail = bars.takeLast(60)
        val hi = tail.maxOf { it.high }
        val lo = tail.minOf { it.low }
        f["position"] = if (hi > lo) {
            val pos = (closes.last() - lo) / (hi - lo)
            if (pos > CHASE_THRESHOLD) 1.0 - pos else pos
        } else null

        // 低波：ATR14 / 收盘 取负（波动越低，这项越高）
        val atr = ExtendedIndicators.atr(bars, 14)
        f["lowvol"] = if (atr != null && closes.last() > 0.0) -(atr / closes.last()) else null

        f.forEach { (k, v) -> if (v == null) missing += "因子 $k 缺数据" }
        return Raw(input.code, input.name, f, missing)
    }

    private fun round(v: Double, digits: Int): Double {
        var mul = 1.0
        repeat(digits) { mul *= 10 }
        return Math.round(v * mul) / mul
    }
}
