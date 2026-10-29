package com.skapp.app.data

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 云端指标库（`stocks-ai-view/scripts/indicators_ext.py`）的逐函数移植。
 *
 * ## 为什么单独一份，不复用 [ExtendedIndicators] / [MarketDataClient]
 *
 * 那两个是端上历史实现：RSI 用 Wilder 但起点处理不同、`choppiness` 的窗口差一位、
 * `supertrend` 的 band 记忆逻辑与云端不一致。它们服务于画图与旧引擎，改动会牵动 UI。
 * 这里要的是**与云端逐位一致**：Python 侧 `round(x,2)` 是十进制舍入（不是二进制近似），
 * 所以本文件统一走 [pyRound]（BigDecimal HALF_EVEN，等价于 Python 的 round），
 * 只负责「同一份输入 → 同一条序列」，不参与任何业务判断。
 *
 * 判定标准只有一个：`CanonicalEngineParityTest` 用云端导出的黄金向量逐票比对。
 */
object CanonicalIndicators {

    /** Python 的 `round(x, digits)`：十进制、半偶进位（BigDecimal 精确值 + HALF_EVEN）。 */
    fun pyRound(x: Double, digits: Int): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    /** 简单移动平均，前 n-1 位 null（与 Python `_n()` 语义一致）。 */
    fun sma(values: List<Double>, n: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (n <= 0 || values.size < n) return out
        var s = values.take(n).sum()
        out[n - 1] = s / n
        for (i in n until values.size) {
            s += values[i] - values[i - n]
            out[i] = s / n
        }
        return out
    }

    /** 指数移动平均，种子 = 前 n 位均值（与 Python 一致）。 */
    fun ema(values: List<Double>, n: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (n <= 0 || values.size < n) return out
        val k = 2.0 / (n + 1)
        var e = values.take(n).sum() / n
        out[n - 1] = e
        for (i in n until values.size) {
            e = values[i] * k + e * (1 - k)
            out[i] = e
        }
        return out
    }

    /** 加权移动平均。注意权重方向与常见 WMA 相反：**最旧一根权重最大**（云端实现如此）。 */
    fun wma(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (period <= 0) return out
        for (i in period - 1 until values.size) {
            var s = 0.0
            var wsum = 0.0
            for (k in 0 until period) {
                val wgt = (period - k).toDouble()
                s += values[i - period + 1 + k] * wgt
                wsum += wgt
            }
            out[i] = if (wsum != 0.0) s / wsum else null
        }
        return out
    }

    fun trueRange(high: List<Double>, low: List<Double>, close: List<Double>): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        for (i in 1 until close.size) {
            out[i] = max(max(high[i] - low[i], abs(high[i] - close[i - 1])), abs(low[i] - close[i - 1]))
        }
        return out
    }

    /** ATR（Wilder 平滑，首值 = 前 n 个 TR 均值），与 Python `atr()` 同口径。 */
    fun atr(high: List<Double>, low: List<Double>, close: List<Double>, n: Int = 14): List<Double?> {
        val tr = trueRange(high, low, close)
        val out = MutableList<Double?>(close.size) { null }
        if (close.size < n + 1) return out
        val seed = (1..n).mapNotNull { tr.getOrNull(it) }
        if (seed.size < n) return out
        var prev = seed.sum() / n
        out[n] = prev
        for (i in n + 1 until close.size) {
            val t = tr[i] ?: continue
            prev = (prev * (n - 1) + t) / n
            out[i] = prev
        }
        return out
    }

    /** SuperTrend：返回 (趋势线, 方向[1/-1/0])，band 记忆逻辑照抄云端。 */
    fun superTrend(
        high: List<Double>,
        low: List<Double>,
        close: List<Double>,
        n: Int = 10,
        multiplier: Double = 3.0,
    ): Pair<List<Double?>, List<Int>> {
        val st = MutableList<Double?>(close.size) { null }
        val trend = MutableList(close.size) { 0 }
        val a = atr(high, low, close, n)
        var prevUpper: Double? = null
        var prevLower: Double? = null
        var prevSt: Double? = null
        var prevClose: Double? = null
        var prevTrend = 0
        for (i in close.indices) {
            val ai = a[i] ?: continue
            val hl2 = (high[i] + low[i]) / 2
            val bu = hl2 + multiplier * ai
            val bl = hl2 - multiplier * ai
            val fu = if (prevUpper == null || bu < prevUpper!! ||
                (prevClose != null && prevClose!! > prevUpper!!)
            ) bu else prevUpper!!
            val fl = if (prevLower == null || bl > prevLower!! ||
                (prevClose != null && prevClose!! < prevLower!!)
            ) bl else prevLower!!
            if (prevSt == null) {
                st[i] = if (close[i] >= hl2) fl else fu
                trend[i] = if (close[i] >= hl2) 1 else -1
            } else {
                if (prevTrend == 1) {
                    st[i] = fl
                    trend[i] = if (close[i] < fl) -1 else 1
                } else {
                    st[i] = fu
                    trend[i] = if (close[i] > fu) 1 else -1
                }
            }
            prevUpper = fu
            prevLower = fl
            prevSt = st[i]
            prevClose = close[i]
            prevTrend = trend[i]
        }
        return st to trend
    }

    /** Keltner 通道：中轨 EMA，上下轨 ±k·ATR。 */
    fun keltner(
        high: List<Double>,
        low: List<Double>,
        close: List<Double>,
        emaN: Int = 20,
        atrN: Int = 10,
        k: Double = 2.0,
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val mid = ema(close, emaN)
        val a = atr(high, low, close, atrN)
        val up = MutableList<Double?>(close.size) { null }
        val lo = MutableList<Double?>(close.size) { null }
        for (i in close.indices) {
            val m = mid[i] ?: continue
            val ai = a[i] ?: continue
            up[i] = m + k * ai
            lo[i] = m - k * ai
        }
        return Triple(mid, up, lo)
    }

    /** 唐奇安通道：(上轨, 中轨, 下轨)。 */
    fun donchian(high: List<Double>, low: List<Double>, n: Int = 20):
        Triple<List<Double?>, List<Double?>, List<Double?>> {
        val up = MutableList<Double?>(high.size) { null }
        val mid = MutableList<Double?>(high.size) { null }
        val lo = MutableList<Double?>(high.size) { null }
        for (i in n - 1 until high.size) {
            val h = high.subList(i - n + 1, i + 1).max()
            val l = low.subList(i - n + 1, i + 1).min()
            up[i] = h
            lo[i] = l
            mid[i] = (h + l) / 2
        }
        return Triple(up, mid, lo)
    }

    fun cmo(close: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        if (close.size < n + 1) return out
        for (i in n until close.size) {
            var su = 0.0
            var sd = 0.0
            for (j in i - n + 1..i) {
                val d = close[j] - close[j - 1]
                if (d > 0) su += d else sd -= d
            }
            val tot = su + sd
            out[i] = if (tot != 0.0) pyRound(100 * (su - sd) / tot, 2) else 0.0
        }
        return out
    }

    fun choppiness(high: List<Double>, low: List<Double>, close: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        val tr = trueRange(high, low, close)
        for (i in n until close.size) {
            val seg = tr.subList(i - n + 1, i + 1)
            if (seg.any { it == null }) continue
            val sumTr = seg.filterNotNull().sum()
            val hh = high.subList(i - n + 1, i + 1).max()
            val ll = low.subList(i - n + 1, i + 1).min()
            val rng = hh - ll
            if (rng <= 0 || sumTr <= 0) continue
            out[i] = pyRound(100 * log10(sumTr / rng) / log10(n.toDouble()), 2)
        }
        return out
    }

    fun vortex(high: List<Double>, low: List<Double>, close: List<Double>, n: Int = 14):
        Pair<List<Double?>, List<Double?>> {
        val tr = trueRange(high, low, close)
        val vip = MutableList<Double?>(close.size) { null }
        val vim = MutableList<Double?>(close.size) { null }
        for (i in n until close.size) {
            var svmp = 0.0
            var svmm = 0.0
            var str = 0.0
            for (j in i - n + 1..i) {
                svmp += abs(high[j] - low[j - 1])
                svmm += abs(low[j] - high[j - 1])
                str += tr[j] ?: 0.0
            }
            if (str <= 0) continue
            vip[i] = pyRound(svmp / str, 4)
            vim[i] = pyRound(svmm / str, 4)
        }
        return vip to vim
    }

    fun ultimateOsc(
        high: List<Double>,
        low: List<Double>,
        close: List<Double>,
        n1: Int = 7, n2: Int = 14, n3: Int = 28,
    ): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        val tr = trueRange(high, low, close)
        val bp = MutableList<Double?>(close.size) { null }
        for (i in 1 until close.size) {
            bp[i] = close[i] - min(low[i], close[i - 1])
        }
        for (i in n3 until close.size) {
            if (tr[i] == null || bp[i] == null) continue
            val d1 = (i - n1 + 1..i).sumOf { tr[it] ?: 0.0 }
            val d2 = (i - n2 + 1..i).sumOf { tr[it] ?: 0.0 }
            val d3 = (i - n3 + 1..i).sumOf { tr[it] ?: 0.0 }
            if (d1 == 0.0 || d2 == 0.0 || d3 == 0.0) continue
            val s1 = (i - n1 + 1..i).sumOf { bp[it] ?: 0.0 } / d1
            val s2 = (i - n2 + 1..i).sumOf { bp[it] ?: 0.0 } / d2
            val s3 = (i - n3 + 1..i).sumOf { bp[it] ?: 0.0 } / d3
            out[i] = pyRound(100 * (4 * s1 + 2 * s2 + s3) / 7, 2)
        }
        return out
    }

    fun hullMa(values: List<Double>, n: Int = 9): List<Double?> {
        val half = max(1, n / 2)
        val a = wma(values, half)
        val b = wma(values, n)
        val raw = values.indices.map { i ->
            val ai = a[i]
            val bi = b[i]
            if (ai != null && bi != null) 2 * ai - bi else null
        }
        val rawc = raw.filterNotNull()
        val res = wma(rawc, floor(sqrt(n.toDouble())).toInt())
        val off = raw.size - rawc.size
        val out = MutableList<Double?>(values.size) { null }
        res.forEachIndexed { i, v -> if (i + off < out.size) out[i + off] = v }
        return out
    }

    fun vwma(close: List<Double>, volume: List<Double>, n: Int = 20): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        for (i in n - 1 until close.size) {
            val sv = (i - n + 1..i).sumOf { volume[it] }
            if (sv <= 0) continue
            var s = 0.0
            for (k in 0 until n) s += close[i - n + 1 + k] * volume[i - n + 1 + k]
            out[i] = s / sv
        }
        return out
    }

    fun elderForce(close: List<Double>, volume: List<Double>, n: Int = 13): List<Double?> {
        val ef = MutableList<Double?>(close.size) { null }
        for (i in 1 until close.size) ef[i] = (close[i] - close[i - 1]) * volume[i]
        val efc = ef.filterNotNull()
        val r = ema(efc, n)
        val off = ef.size - efc.size
        val out = MutableList<Double?>(close.size) { null }
        r.forEachIndexed { i, v -> if (i + off < out.size) out[i + off] = v }
        return out
    }

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(closes.size) { null }
        if (closes.size < period + 1) return out
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gains += d else losses -= d
        }
        var ag = gains / period
        var al = losses / period
        out[period] = if (al == 0.0) 100.0 else pyRound(100 - 100 / (1 + ag / al), 2)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            ag = (ag * (period - 1) + if (d > 0) d else 0.0) / period
            al = (al * (period - 1) + if (d < 0) -d else 0.0) / period
            out[i] = if (al == 0.0) 100.0 else pyRound(100 - 100 / (1 + ag / al), 2)
        }
        return out
    }

    fun connorsRsi(
        close: List<Double>,
        rsiN: Int = 3,
        streakN: Int = 2,
        rankN: Int = 100,
    ): List<Double?> {
        val rsiC = rsi(close, rsiN)
        val streak = MutableList(close.size) { 0.0 }
        for (i in 1 until close.size) {
            streak[i] = when {
                close[i] > close[i - 1] -> if (streak[i - 1] >= 0) streak[i - 1] + 1 else 1.0
                close[i] < close[i - 1] -> if (streak[i - 1] <= 0) streak[i - 1] - 1 else -1.0
                else -> 0.0
            }
        }
        val rsiS = rsi(streak, streakN)
        val out = MutableList<Double?>(close.size) { null }
        for (i in rankN until close.size) {
            val roc = if (close[i - 1] != 0.0) (close[i] - close[i - 1]) / close[i - 1] else 0.0
            val hist = (i - rankN + 1..i).map { j ->
                if (close[j - 1] != 0.0) (close[j] - close[j - 1]) / close[j - 1] else 0.0
            }
            val rank = 100.0 * hist.count { it < roc } / hist.size
            val parts = listOfNotNull(rsiS[i], rsiC[i], rank)
            if (parts.size == 3) out[i] = pyRound(parts.sum() / 3, 2)
        }
        return out
    }

    fun adLine(high: List<Double>, low: List<Double>, close: List<Double>, volume: List<Double>): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        var cum = 0.0
        for (i in close.indices) {
            val rng = high[i] - low[i]
            val clv = if (rng != 0.0) ((close[i] - low[i]) - (high[i] - close[i])) / rng else 0.0
            cum += clv * volume[i]
            out[i] = pyRound(cum, 2)
        }
        return out
    }

    data class Pivot(val pp: Double, val r1: Double, val s1: Double, val r2: Double, val s2: Double)

    fun pivotPoints(high: List<Double>, low: List<Double>, close: List<Double>): List<Pivot> =
        close.indices.map { i ->
            val pp = (high[i] + low[i] + close[i]) / 3
            Pivot(
                pp = pyRound(pp, 2),
                r1 = pyRound(2 * pp - low[i], 2),
                s1 = pyRound(2 * pp - high[i], 2),
                r2 = pyRound(pp + (high[i] - low[i]), 2),
                s2 = pyRound(pp - (high[i] - low[i]), 2),
            )
        }
}
