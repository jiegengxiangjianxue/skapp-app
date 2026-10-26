package com.skapp.app.data

/**
 * 连板层原始统计（ban_prob.json 的 by_lianban 单层，单位：%）
 */
data class LianbanRaw(
    val lianban: Int,
    val nSamples: Int,
    val ztNextRate: Double?,      // 次日涨停率
    val zhabanNextRate: Double?,  // 次日炸板率
    val retNextAvg: Double?,      // 次日平均收益
)

/**
 * 收缩后的连板层统计
 */
data class LianbanBalanced(
    val lianban: Int,
    val nSamples: Int,
    val rawZtNextRate: Double?,
    val balZtNextRate: Double,
    val rawZhabanNextRate: Double?,
    val balZhabanNextRate: Double,
    val rawRetNextAvg: Double?,
    val balRetNextAvg: Double,
    val confidence: Double,   // n/(n+k)：样本越足越信原始值
    val sufficient: Boolean,  // n >= 100
    val priorUsed: Double,    // 本层使用的先验（= 低一层收缩后涨停率）
)

/**
 * 均衡总结果
 */
data class BalanceResult(
    val method: String,
    val k: Int,
    val minSufficient: Int,
    val globalPriorZt: Double,
    val globalPriorZb: Double,
    val globalPriorRet: Double,
    val byLianban: Map<String, LianbanBalanced>,
    val totalSamples: Int,
    val topTierSharePct: Double,
    val insufficientTiers: List<String>,
)

/**
 * 样本均衡引擎 — 经验贝叶斯收缩 + 层级先验
 * 移植自 stocks-ai-view/scripts/sample_balancer.py
 */
object SampleBalancer {

    const val K = 50            // EB 收缩强度
    const val MIN_SUFFICIENT = 100

    /** 层级顺序（低→高） */
    val TIER_ORDER = listOf("首板", "2连板", "3连板", "4连板", "5连板", "6连板")

    /**
     * EB 收缩：(n·rate + k·prior) / (n + k)
     */
    fun shrink(rate: Double?, n: Int, prior: Double, k: Int = K): Pair<Double, Double> {
        if (rate == null || n <= 0) return prior to 0.0
        val estimate = (n * rate + k * prior) / (n + k)
        return estimate to n.toDouble() / (n + k)
    }

    /**
     * 对 by_lianban 各层做层级 EB 收缩
     */
    fun balance(raw: Map<String, LianbanRaw>, k: Int = K): BalanceResult? {
        if (raw.isEmpty()) return null
        val total = raw.values.sumOf { it.nSamples }
        if (total <= 0) return null

        fun wavg(sel: (LianbanRaw) -> Double?): Double {
            val num = raw.values.sumOf { r -> r.nSamples * (sel(r) ?: 0.0) }
            return num / total
        }

        val priorZt = wavg { it.ztNextRate }
        val priorZb = wavg { it.zhabanNextRate }
        val priorRet = wavg { it.retNextAvg }

        val out = LinkedHashMap<String, LianbanBalanced>()
        var prevZt = priorZt
        var prevZb = priorZb
        var prevRet = priorRet

        for (tier in TIER_ORDER) {
            val v = raw[tier] ?: continue
            val n = v.nSamples
            val (zt, conf) = shrink(v.ztNextRate, n, prevZt, k)
            val (zb, _) = shrink(v.zhabanNextRate, n, prevZb, k)
            val ret = if (n > 0) (n * (v.retNextAvg ?: 0.0) + k * prevRet) / (n + k) else prevRet

            out[tier] = LianbanBalanced(
                lianban = v.lianban,
                nSamples = n,
                rawZtNextRate = v.ztNextRate,
                balZtNextRate = zt,
                rawZhabanNextRate = v.zhabanNextRate,
                balZhabanNextRate = zb,
                rawRetNextAvg = v.retNextAvg,
                balRetNextAvg = ret,
                confidence = conf,
                sufficient = n >= MIN_SUFFICIENT,
                priorUsed = prevZt,
            )
            prevZt = zt
            prevZb = zb
            prevRet = ret
        }

        val topN = raw.values.maxOf { it.nSamples }
        return BalanceResult(
            method = "empirical-Bayes shrinkage, k=$k, prior flows top-down",
            k = k,
            minSufficient = MIN_SUFFICIENT,
            globalPriorZt = priorZt,
            globalPriorZb = priorZb,
            globalPriorRet = priorRet,
            byLianban = out,
            totalSamples = total,
            topTierSharePct = if (total > 0) topN * 100.0 / total else 0.0,
            insufficientTiers = out.filterValues { !it.sufficient }.keys.toList(),
        )
    }
}
