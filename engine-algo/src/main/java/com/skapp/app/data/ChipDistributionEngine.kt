package com.skapp.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * CYQ 筹码分布引擎。
 *
 * 移植自 gitcode engine-core/algorithms/cyq.py（其参考 myhhub/stock 的 cyq 实现）。
 * 逐日把成交量按三角分布摊到 150 个价格档（顶点 = 当日均价），历史筹码按当日换手率
 * 衰减（保留 1 - turnover），累加得当前筹码分布。
 *
 * 换手率缺失时的退化路径与源实现一致：用「当日量 / 近 120 日均量」近似，且把天数
 * 记进 [Result.fallbackDays] 透出——它是数据质量标记，不是真实换手率，调用方不得
 * 把 fallbackDays > 0 的结果当成等精度数据。
 *
 * 与源实现的差异：源用 numpy 在 avg == low 时会产生除零 nan；这里该档计 0，
 * 保证一字板等退化形态仍能输出有限值。
 */
object ChipDistributionEngine {

    private const val ACCURACY = 150
    private const val RANGE_DAYS = 120
    private const val MIN_BARS = 2

    /** 日 K 一根；turnoverRate 为真实换手率（0~1），缺失传 null。 */
    data class Bar(
        val date: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val turnoverRate: Double? = null,
    )

    data class Result(
        val date: String,
        val close: Double,
        /** 获利盘比例：成本低于现价的筹码占比 0~1 */
        val profitRatio: Double,
        /** 90% 筹码集中度：5%~95% 分位区间宽 / 平均成本 */
        val conc90: Double,
        /** 成本乖离：现价 / 平均成本 - 1 */
        val costBias: Double,
        /** 用均量近似换手率的天数 */
        val fallbackDays: Int,
    )

    fun compute(bars: List<Bar>): Result? {
        if (bars.size < MIN_BARS) return null
        val d = bars.takeLast(RANGE_DAYS)
        val lowAll = d.minOf { it.low }
        val highAll = d.maxOf { it.high }
        if (highAll <= lowAll) return null
        val step = (highAll - lowAll) / ACCURACY
        val grid = DoubleArray(ACCURACY) { lowAll + (it + 0.5) * step }
        val chips = DoubleArray(ACCURACY)
        val meanV = d.sumOf { it.volume } / d.size
        var fallbackDays = 0
        for (b in d) {
            val avg = (b.open + b.high + b.low + b.close) / 4.0
            val tr = b.turnoverRate
            val turnover = if (tr != null && tr > 0) tr else {
                fallbackDays++
                b.volume / max(meanV, 1.0)
            }
            val t = min(1.0, max(0.0, turnover))
            val decay = 1.0 - t
            val day = DoubleArray(ACCURACY)
            for (j in 0 until ACCURACY) {
                val p = grid[j]
                day[j] = when {
                    p >= b.low && p <= avg -> if (avg > b.low) (p - b.low) / (avg - b.low) else 0.0
                    p > avg && p <= b.high -> if (b.high > avg) (b.high - p) / (b.high - avg) else 0.0
                    else -> 0.0
                }
            }
            val s = day.sum()
            if (s > 0) for (j in 0 until ACCURACY) day[j] /= s
            for (j in 0 until ACCURACY) chips[j] *= decay
            if (b.high <= b.low) {
                // 一字板：三角分布退化为点，按源实现注入 volume*turnover/2 到均价档
                val k = min(ACCURACY - 1, max(0, ((avg - lowAll) / step).toInt()))
                chips[k] += b.volume * t / 2.0
            } else if (s > 0) {
                for (j in 0 until ACCURACY) chips[j] += day[j] * b.volume * t
            }
        }
        val total = chips.sum()
        if (total <= 0) return null
        for (j in 0 until ACCURACY) chips[j] /= total
        val close = d.last().close
        var profit = 0.0
        for (j in 0 until ACCURACY) if (grid[j] < close) profit += chips[j]
        profit = profit.coerceIn(0.0, 1.0)
        var cum = 0.0
        var lo = grid[0]
        var hi = grid[ACCURACY - 1]
        var foundLo = false
        for (j in 0 until ACCURACY) {
            cum += chips[j]
            if (!foundLo && cum >= 0.05) {
                lo = grid[j]
                foundLo = true
            }
            if (cum >= 0.95) {
                hi = grid[j]
                break
            }
        }
        var cost = 0.0
        for (j in 0 until ACCURACY) cost += grid[j] * chips[j]
        return Result(
            date = d.last().date,
            close = close,
            profitRatio = profit,
            conc90 = (hi - lo) / max(cost, 1e-9),
            costBias = close / max(cost, 1e-9) - 1.0,
            fallbackDays = fallbackDays,
        )
    }
}
