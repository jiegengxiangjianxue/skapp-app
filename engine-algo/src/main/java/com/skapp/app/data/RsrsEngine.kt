package com.skapp.app.data

import kotlin.math.sqrt

/**
 * RSRS 阻力支撑相对强度择时因子。
 *
 * 移植自 gitcode engine-core/algorithms/rsrs.py（其源自 Qbot / iQuant 的 indicator_rsrs.py）。
 * 做法：每根 K 线用前 N 根 high~low 做 OLS，取斜率 β 与拟合优度 R²；
 * β 高表示「阻力位相对支撑更强」即多头结构。再用最近 M 个 β 做 z-score，
 * 乘 R² 得到最终标准分。Qbot 轮动用的阈值是 >1 做多、<0.8 离场，本类只算值，
 * 阈值判断留给调用方（口径见 [RegimeGate]）。
 *
 * 数据不足 M+N 根时对应位置为 null，不补零、不外推。
 */
object RsrsEngine {

    const val N = 18
    const val M = 300

    /**
     * @param highs 高开线序列（按日期升序）
     * @param lows 低开线序列，长度须与 [highs] 一致
     * @return 与输入等长的标准分序列，算不出的位置为 null
     */
    fun series(highs: List<Double>, lows: List<Double>, n: Int = N, m: Int = M): List<Double?> {
        if (highs.size != lows.size || highs.size <= n) return List(highs.size) { null }
        val beta = arrayOfNulls<Double>(highs.size)
        val r2 = DoubleArray(highs.size)
        for (i in n until highs.size) {
            var mx = 0.0
            var my = 0.0
            for (j in i - n until i) {
                mx += highs[j]
                my += lows[j]
            }
            mx /= n
            my /= n
            var sxx = 0.0
            var sxy = 0.0
            var syy = 0.0
            for (j in i - n until i) {
                val dx = highs[j] - mx
                val dy = lows[j] - my
                sxx += dx * dx
                sxy += dx * dy
                syy += dy * dy
            }
            if (sxx == 0.0) continue
            beta[i] = sxy / sxx
            r2[i] = if (sxx * syy != 0.0) (sxy * sxy) / (sxx * syy) else 0.0
        }
        val out = MutableList<Double?>(highs.size) { null }
        for (i in m until highs.size) {
            val b = beta[i] ?: continue
            var cnt = 0
            var mu = 0.0
            for (j in i - m until i) {
                beta[j]?.let { mu += it; cnt++ }
            }
            if (cnt < m / 2) continue
            mu /= cnt
            var variance = 0.0
            for (j in i - m until i) {
                beta[j]?.let { val d = it - mu; variance += d * d }
            }
            val sd = sqrt(variance / cnt)
            if (sd > 0.0) out[i] = (b - mu) / sd * r2[i]
        }
        return out
    }

    /** 最新一根的标准分；数据不足返回 null。 */
    fun current(highs: List<Double>, lows: List<Double>, n: Int = N, m: Int = M): Double? =
        series(highs, lows, n, m).lastOrNull()
}
