package com.skapp.app.data

/**
 * 实时回本率计算器 — 软件端直接算, 不依赖硬编码表 (2026-10-22)
 * 口径与云端一致: 任意历史日买入, 22/60 交易日内最高价 >= 买入价 的占比
 * 输入: 日 K (复权), 输出: 回本率 + 平均最高涨幅 + 一年位置分位
 */
object RecoupCalc {
    data class Result(
        val n: Int,
        val recoup22: Double?,      // % 
        val recoup60: Double?,
        val avgMaxGain22: Double?,  // %
        val posInYear: Double?,     // 0-100 分位
        val sample: String          // 样本说明
    )

    fun compute(bars: List<KBar>): Result {
        if (bars.size < 80) return Result(bars.size, null, null, null, null, "历史不足80日")
        val closes = bars.map { it.close }
        val highs = bars.map { it.high }

        fun recoup(window: Int): Pair<Double, Double>? {
            var hit = 0; var total = 0; var gSum = 0.0
            for (i in 0 until bars.size - window) {
                val b = closes[i]
                var hi = Double.MIN_VALUE
                for (j in i + 1..i + window) hi = maxOf(hi, highs[j])
                if (hi >= b) hit++
                gSum += (hi - b) / b
                total++
            }
            return if (total > 0) Pair(hit * 100.0 / total, gSum / total * 100) else null
        }

        val r22 = recoup(22)
        val r60 = recoup(60)
        // 一年位置: 现价在近 250 日 high-low 区间的分位
        val yr = closes.takeLast(minOf(250, closes.size))
        val lo = yr.min(); val hiY = yr.max()
        val pos = if (hiY > lo) (closes.last() - lo) / (hiY - lo) * 100 else 50.0
        val sample = if (bars.size >= 250) "近一年" else "近${bars.size}日"
        return Result(bars.size, r22?.first, r60?.first, r22?.second, pos,
            "$sample ${r22?.first?.let { "22日回本${"%.1f".format(it)}%" } ?: ""}")
    }

    // 门槛: 回本率<70% 或 (位置>90 且 均最高涨幅<2%) → 票单里降级提示
    fun gatePass(r: Result): Boolean =
        (r.recoup22 ?: 0.0) >= 70.0 && !((r.posInYear ?: 0.0) > 90.0 && (r.avgMaxGain22 ?: 0.0) < 2.0)
}
