package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 多日趋势推演：把日 K 上的均线结构、MACD、MFI、ATR 折算成 1/3/5/10 日的
 * 「方向 + 置信度 + 波动区间」，而不是给一个单点涨跌预测。
 *
 * 口径边界（必须随结果一起展示，不能只留下方向）：
 *  - 这是对历史结构的统计外推，不含事件因果，遇到公告/资金面突变即失效；
 *  - 样本只有该股自身历史，不存在跨股票验证，因此置信度上限压到 0.70；
 *  - 区间用 ATR% × √期数，隐含「日收益近独立」假设，波动聚集（连续放大波幅）时会低估。
 */
object TrendForecast {

    data class Macd(val dif: Double, val dea: Double, val hist: Double)

    data class Horizon(
        val days: Int,
        val direction: String,      // 偏多 / 偏空 / 震荡
        val confidence: Double,     // 0~0.70
        val lowPct: Double,         // 区间下沿（相对现价，%）
        val highPct: Double,        // 区间上沿
        val basis: List<String>,
    )

    data class Result(
        val ma5: Double?,
        val ma10: Double?,
        val ma20: Double?,
        val macd: Macd?,
        val mfi: Double?,
        val atrPct: Double?,
        val slope20Pct: Double?,
        val horizons: List<Horizon>,
        val notes: List<String>,
    )

    private val HORIZONS = listOf(1, 3, 5, 10)

    fun compute(bars: List<KBar>): Result {
        val closes = bars.map { it.close }
        val ma5 = meanOfLast(closes, 5)
        val ma10 = meanOfLast(closes, 10)
        val ma20 = meanOfLast(closes, 20)
        val macdVal = macd(closes)
        val mfi = runCatching { ExtendedIndicators.mfi(bars) }.getOrNull()
        val atr = runCatching { ExtendedIndicators.atr(bars) }.getOrNull()
        val atrPct = if (atr != null && closes.isNotEmpty() && closes.last() > 0) atr / closes.last() * 100 else null
        val slope20 = slopePct(closes, 20)

        if (closes.size < 60 || ma20 == null || macdVal == null) {
            return Result(
                ma5 = ma5, ma10 = ma10, ma20 = ma20, macd = macdVal, mfi = mfi,
                atrPct = atrPct, slope20Pct = slope20, horizons = emptyList(),
                notes = listOf("日 K 仅 ${closes.size} 根（需 ≥60），均线与 MACD 不足以给出多日方向"),
            )
        }

        val close = closes.last()
        val stack = when {
            ma5 != null && ma10 != null && ma5 > ma10 -> 1
            ma5 != null && ma10 != null && ma5 < ma10 -> -1
            else -> 0
        }
        val basis = buildList {
            if (ma5 != null) add("MA5 ${fmt(ma5)}" + if (ma10 != null) " / MA10 ${fmt(ma10)}" else "")
            if (ma20 != null) add("现价${if (close >= ma20) "在" else "低于"} MA20(${fmt(ma20)})")
            add("MACD 柱 ${fmt(macdVal.hist)}（DIF ${fmt(macdVal.dif)} / DEA ${fmt(macdVal.dea)}）")
            if (mfi != null) add("MFI ${fmt(mfi)}")
            if (slope20 != null) add("20 日斜率 ${fmt(slope20)}%")
        }
        val votes = listOfNotNull(
            stack.takeIf { it != 0 }?.let { if (it > 0) "多" else "空" },
            if (macdVal.hist > 0) "多" else if (macdVal.hist < 0) "空" else null,
            slope20?.let { if (it > 0.3) "多" else if (it < -0.3) "空" else null },
            mfi?.let { if (it >= 65) "多" else if (it <= 35) "空" else null },
        )
        val net = votes.count { it == "多" } - votes.count { it == "空" }
        val direction = if (net >= 1) "偏多" else if (net <= -1) "偏空" else "震荡"

        val horizons = HORIZONS.map { d ->
            val range = (atrPct ?: 0.0) * sqrt(d.toDouble())
            // 期数越长，同一批依据的解释力越弱：置信度按期数折减，方向不变但不再加码
            val conf = (0.34 + 0.10 * abs(net) - 0.02 * (d - 1)).coerceIn(0.30, 0.70)
            val skew = range * 0.25 * net.coerceIn(-2, 2) / 2.0
            Horizon(
                days = d,
                direction = direction,
                confidence = conf,
                lowPct = -range + skew,
                highPct = range + skew,
                basis = basis,
            )
        }
        return Result(
            ma5 = ma5, ma10 = ma10, ma20 = ma20, macd = macdVal, mfi = mfi,
            atrPct = atrPct, slope20Pct = slope20, horizons = horizons,
            notes = listOf(
                "依据命中 ${votes.count { it == "多" }} 多 / ${votes.count { it == "空" }} 空（MA 结构、MACD 柱、20 日斜率、MFI）",
                "区间 = ATR%×√期数（ATR ${atrPct?.let { fmt(it) } ?: "不可得"}%），假设日收益近独立，波动聚集时低估",
                "统计外推，非保证；样本为自身历史，遇公告/资金面突变失效",
            ),
        )
    }

    /** MACD(12,26,9)：DIF = EMA12 − EMA26，DEA = EMA(DIF,9)，柱 = 2×(DIF−DEA)（A 股惯例）。 */
    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd? {
        if (closes.size < slow + signal) return null
        val f = ema(closes, fast)
        val s = ema(closes, slow)
        val dif = closes.indices.map { i ->
            val a = f.getOrNull(i)
            val b = s.getOrNull(i)
            if (a == null || b == null) null else a - b
        }
        val difClean = dif.mapNotNull { it }
        val deaTail = ema(difClean, signal)
        val difLast = difClean.lastOrNull() ?: return null
        val deaLast = deaTail.lastOrNull() ?: return null
        return Macd(dif = difLast, dea = deaLast, hist = 2 * (difLast - deaLast))
    }

    private fun ema(values: List<Double>, n: Int): List<Double?> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double?>(values.size)
        var prev: Double? = null
        values.forEach { v ->
            prev = if (prev == null) v else v * k + prev!! * (1 - k)
            out.add(prev)
        }
        return out
    }

    private fun meanOfLast(values: List<Double>, n: Int): Double? =
        if (values.size < n) null else values.takeLast(n).average()

    /** 最近 n 日收盘对时间做最小二乘，斜率折算成「日均 %」。 */
    private fun slopePct(closes: List<Double>, n: Int): Double? {
        if (closes.size < n) return null
        val ys = closes.takeLast(n)
        val meanX = (n - 1) / 2.0
        val meanY = ys.average()
        if (meanY <= 0) return null
        var num = 0.0
        var den = 0.0
        ys.forEachIndexed { i, y ->
            num += (i - meanX) * (y - meanY)
            den += (i - meanX) * (i - meanX)
        }
        if (den == 0.0) return null
        return num / den / meanY * 100
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
