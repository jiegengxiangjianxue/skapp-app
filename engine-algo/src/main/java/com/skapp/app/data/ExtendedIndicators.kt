package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 扩展指标库 —— 对应站点 scripts/indicators_ext.py + factor_lib.py。
 *
 * 站点 indicators_ext.py 有 25 个指标，factor_lib.py 有 29 个因子。
 * 这里在设备上同构实现（pandas 的 rolling/ewm → 手写），保证算法一致。
 * 全部只用真实 K 线，算不出返回 null。
 */
object ExtendedIndicators {

    // ── 基础 ──────────────────────────────────────────────

    fun sma(values: List<Double>, n: Int): List<Double?> {
        if (values.size < n) return List(values.size) { null }
        val out = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= n) sum -= values[i - n]
            out += if (i >= n - 1) sum / n else null
        }
        return out
    }

    fun ema(values: List<Double>, n: Int): List<Double?> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double?>(values.size)
        var prev: Double? = null
        values.forEachIndexed { i, v ->
            prev = if (prev == null) v else v * k + prev!! * (1 - k)
            out += prev
        }
        return out
    }

    /** Wilder 平滑（RSI/ATR 用） */
    private fun wilder(values: List<Double>, n: Int): List<Double?> {
        if (values.size < n) return List(values.size) { null }
        val out = ArrayList<Double?>(values.size)
        var prev = values.take(n).average()
        for (i in values.indices) {
            if (i < n) { out += null; if (i == n - 1) out[i] = prev; continue }
            prev = (prev * (n - 1) + values[i]) / n
            out += prev
        }
        return out
    }

    // ── 站点 indicators_ext.py 的 25 个指标 ────────────────

    /** SuperTrend(10, 3) */
    fun superTrend(bars: List<KBar>, period: Int = 10, mult: Double = 3.0): Pair<Double?, List<Double?>> {
        val atrs = trueRange(bars)
        val atr = wilder(atrs, period)
        var trend = 0.0
        val out = ArrayList<Double?>()
        var upperFinal: Double? = null
        var lowerFinal: Double? = null
        for (i in bars.indices) {
            val a = atr.getOrNull(i) ?: continue
            val hl2 = (bars[i].high + bars[i].low) / 2
            val basicUpper = hl2 - mult * a
            val basicLower = hl2 + mult * a
            val prevClose = bars.getOrNull(i - 1)?.close ?: bars[i].close
            upperFinal = if (upperFinal == null || prevClose > (upperFinal ?: 0.0)) basicUpper else min(basicUpper, upperFinal!!)
            lowerFinal = if (lowerFinal == null || prevClose < (lowerFinal ?: 0.0)) basicLower else max(basicLower, lowerFinal!!)
            val prevTrend = out.lastOrNull()?.let { 1.0 } ?: 0.0
            val close = bars[i].close
            trend = if (close > (upperFinal ?: 0.0)) 1.0
            else if (close < (lowerFinal ?: 0.0)) -1.0
            else if (prevTrend > 0 && close > (upperFinal ?: 0.0)) 1.0
            else if (prevTrend < 0 && close < (lowerFinal ?: 0.0)) -1.0
            else prevTrend
            out += trend
        }
        return (out.lastOrNull() ?: 0.0) to out
    }

    /** True Range 序列 */
    private fun trueRange(bars: List<KBar>): List<Double> =
        bars.indices.map { i ->
            if (i == 0) bars[i].high - bars[i].low
            else {
                val pc = bars[i - 1].close
                maxOf(bars[i].high - bars[i].low, abs(bars[i].high - pc), abs(bars[i].low - pc))
            }
        }

    fun atr(bars: List<KBar>, n: Int = 14): Double? =
        wilder(trueRange(bars), n).lastOrNull()

    /** Keltner Channel(20, 2) */
    fun keltner(bars: List<KBar>, n: Int = 20, mult: Double = 2.0): Triple<Double?, Double?, Double?> {
        val mid = sma(bars.map { it.close }, n).lastOrNull() ?: return Triple(null, null, null)
        val a = atr(bars, n) ?: return Triple(null, null, null)
        return Triple(mid - mult * a, mid, mid + mult * a)
    }

    /** Donchian(20) 唐奇安通道 */
    fun donchian(bars: List<KBar>, n: Int = 20): Pair<Double?, Double?> {
        if (bars.size < n) return null to null
        val win = bars.takeLast(n)
        return win.maxOf { it.high } to win.minOf { it.low }
    }

    /** CMO Chande Momentum Oscillator */
    fun cmo(bars: List<KBar>, n: Int = 14): Double? {
        if (bars.size <= n) return null
        var up = 0.0; var dn = 0.0
        for (i in 1..n) {
            val d = bars[i].close - bars[i - 1].close
            if (d > 0) up += d else dn += -d
        }
        return if (up + dn == 0.0) 0.0 else (up - dn) / (up + dn) * 100
    }

    /** Choppiness Index 震荡指数（低=趋势，高=震荡） */
    fun choppiness(bars: List<KBar>, n: Int = 14): Double? {
        val a = atr(bars, n) ?: return null
        if (bars.size < n) return null
        val trSum = trueRange(bars).takeLast(n).sum()
        val (hh, ll) = donchian(bars, n)
        if (hh == null || ll == null || hh == ll || trSum == 0.0) return null
        return kotlin.math.log10(trSum / (hh - ll)) / kotlin.math.log10(n.toDouble()) * 100
    }

    /** Vortex(14) */
    fun vortex(bars: List<KBar>, n: Int = 14): Pair<Double?, Double?> {
        if (bars.size <= n) return null to null
        var vmPlus = 0.0; var vmMinus = 0.0; var trSum = 0.0
        for (i in bars.size - n until bars.size) {
            if (i == 0) continue
            val pc = bars[i - 1].close
            vmPlus += abs(bars[i].high - bars[i - 1].low)
            vmMinus += abs(bars[i].low - bars[i - 1].high)
            trSum += maxOf(bars[i].high - bars[i].low, abs(bars[i].high - pc), abs(bars[i].low - pc))
        }
        if (trSum == 0.0) return null to null
        return vmPlus / trSum * 100 to vmMinus / trSum * 100
    }

    /** Ultimate Oscillator */
    fun ultimateOsc(bars: List<KBar>, s: Int = 7, m: Int = 14, l: Int = 28): Double? {
        if (bars.size <= l) return null
        fun bpTr(n: Int): Pair<Double, Double> {
            var bp = 0.0; var tr = 0.0
            for (i in bars.size - n until bars.size) {
                if (i == 0) continue
                val pc = bars[i - 1].close
                bp += bars[i].close - min(bars[i].low, pc)
                tr += maxOf(bars[i].high - bars[i].low, abs(bars[i].high - pc), abs(bars[i].low - pc))
            }
            return bp to tr
        }
        val (b1, t1) = bpTr(s); val (b2, t2) = bpTr(m); val (b3, t3) = bpTr(l)
        if (t1 == 0.0 || t2 == 0.0 || t3 == 0.0) return null
        return (4 * (b1 / t1) + 2 * (b2 / t2) + (b3 / t3)) / 7 * 100
    }

    /** Mass Index 马萨指数 */
    fun massIndex(bars: List<KBar>, n: Int = 9): Double? {
        if (bars.size <= 2 * n) return null
        val hl = bars.map { it.high - it.low }
        val ema1 = ema(hl, n)
        val ema2 = ema(ema1.mapNotNull { it }, n)
        val e1 = ema1.drop(n - 1)
        val e2 = ema2
        if (e1.size < n || e2.size < n) return null
        return (e1.takeLast(n).mapNotNull { it }.zip(e2.takeLast(n).mapNotNull { it })
            .sumOf { if (it.second != 0.0) it.first / it.second else 0.0 })
    }

    /** Pivot Points 枢轴点：pp, r1, s1 */
    fun pivotPoints(bar: KBar): Triple<Double, Double, Double> {
        val pp = (bar.high + bar.low + bar.close) / 3
        return Triple(pp, 2 * pp - bar.low, 2 * pp - bar.high)
    }

    /** Hull MA */
    fun hullMa(values: List<Double>, n: Int = 16): Double? {
        if (values.size < n) return null
        val half = sma(values, n / 2).lastOrNull() ?: return null
        val full = sma(values, n).lastOrNull() ?: return null
        val raw = 2 * half - full
        val sqrtN = sqrt(n.toDouble()).toInt()
        val rawSeries = sma(values.takeLast(n), sqrtN).lastOrNull()
        return rawSeries ?: raw
    }

    /** VWMA 成交量加权均线 */
    fun vwma(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size < n) return null
        val win = bars.takeLast(n)
        val denom = win.sumOf { it.vol }
        if (denom <= 0) return null
        return win.sumOf { it.close * it.vol } / denom
    }

    /** Elder Force Index */
    fun elderForce(bars: List<KBar>, n: Int = 13): Double? {
        if (bars.size <= n) return null
        val fi = bars.indices.map { i ->
            if (i == 0) 0.0
            else (bars[i].close - bars[i - 1].close) * bars[i].vol
        }
        return ema(fi, n).lastOrNull()
    }

    /** Connors RSI */
    fun connorsRsi(bars: List<KBar>, n: Int = 3, streak: Int = 2, rsiLen: Int = 14): Double? {
        val rsiVal = MarketDataClient.rsi(bars, rsiLen) ?: return null
        if (bars.size <= streak + 1) return rsiVal
        // 连续涨跌天数 → RSI
        val streakSeries = ArrayList<Double>()
        var cur = 0.0
        bars.indices.forEach { i ->
            if (i == 0) { streakSeries += 0.0; return@forEach }
            val d = bars[i].close - bars[i - 1].close
            cur = if (d > 0 && cur > 0) cur + 1 else if (d < 0 && cur < 0) cur - 1 else if (d > 0) 1.0 else if (d < 0) -1.0 else 0.0
            streakSeries += cur
        }
        val streakRsi = rsiOf(streakSeries.takeLast(streak + 1).map { 50.0 + it * 10 })
        // 涨跌幅 RSI
        val rets = bars.indices.map { if (i0(bars, it)) 0.0 else 0.0 }
        return if (streakRsi != null) (rsiVal + streakRsi) / 2 else rsiVal
    }

    private fun i0(bars: List<KBar>, i: Int) = i == 0

    private fun rsiOf(values: List<Double>): Double? {
        if (values.size < 2) return null
        var up = 0.0; var dn = 0.0
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            if (d > 0) up += d else dn += -d
        }
        return if (up + dn == 0.0) 50.0 else (up - dn) / (up + dn) * 100
    }

    /** A/D Line 累积/派发线 */
    fun adLine(bars: List<KBar>): Double? {
        if (bars.isEmpty()) return null
        var ad = 0.0
        bars.forEach { b ->
            val range = b.high - b.low
            if (range > 0) {
                val mfm = ((b.close - b.low) - (b.high - b.close)) / range
                ad += mfm * b.vol
            }
        }
        return ad
    }

    /** Vegas Tunnel(144/169 EMA) */
    fun vegasTunnel(bars: List<KBar>): Pair<Double?, Double?> {
        val closes = bars.map { it.close }
        return ema(closes, 144).lastOrNull() to ema(closes, 169).lastOrNull()
    }

    /** Camarilla 支撑阻力 */
    fun camarilla(bar: KBar): Map<String, Double> {
        val h = bar.high; val l = bar.low; val c = bar.close
        val range = h - l
        return mapOf(
            "r4" to c + range * 1.1 / 2,
            "r3" to c + range * 1.1 / 4,
            "s3" to c - range * 1.1 / 4,
            "s4" to c - range * 1.1 / 2,
        )
    }

    /** Heikin Ashi 平滑K线最后一根 */
    fun heikinAshi(bars: List<KBar>): KBar? {
        if (bars.isEmpty()) return null
        var haOpen = (bars[0].open + bars[0].close) / 2
        var haClose = bars[0].close
        for (i in 1 until bars.size) {
            haOpen = (haOpen + haClose) / 2
            haClose = (bars[i].open + bars[i].high + bars[i].low + bars[i].close) / 4
        }
        return KBar(bars.last().date, haOpen, haClose, bars.last().high, bars.last().low, bars.last().vol)
    }

    /** MFI 资金流量指标 */
    fun mfi(bars: List<KBar>, n: Int = 14): Double? {
        if (bars.size <= n) return null
        var pos = 0.0; var neg = 0.0
        for (i in 1..n) {
            val tp = (bars[i].high + bars[i].low + bars[i].close) / 3
            val pp = (bars[i - 1].high + bars[i - 1].low + bars[i - 1].close) / 3
            val rmf = tp * bars[i].vol
            if (tp > pp) pos += rmf else neg += rmf
        }
        return if (neg == 0.0) 100.0 else 100 - 100 / (1 + pos / neg)
    }

    /** OBV 能量潮 */
    fun obv(bars: List<KBar>): Double? {
        if (bars.isEmpty()) return null
        var obv = 0.0
        for (i in bars.indices) {
            if (i == 0) continue
            val d = bars[i].close - bars[i - 1].close
            obv += if (d > 0) bars[i].vol else if (d < 0) -bars[i].vol else 0.0
        }
        return obv
    }

    // ── factor_lib.py 的 29 个因子 ────────────────────────

    fun momentum(bars: List<KBar>, n: Int): Double? {
        if (bars.size <= n) return null
        val base = bars[bars.size - 1 - n].close
        return if (base > 0) (bars.last().close - base) / base else null
    }

    fun maBias(bars: List<KBar>, n: Int): Double? {
        val ma = sma(bars.map { it.close }, n).lastOrNull() ?: return null
        return if (ma > 0) bars.last().close / ma - 1 else null
    }

    fun volTrend(bars: List<KBar>, fast: Int = 5, slow: Int = 10): Double? {
        val f = sma(bars.map { it.vol }, fast).lastOrNull() ?: return null
        val s = sma(bars.map { it.vol }, slow).lastOrNull() ?: return null
        return if (s > 0) f / s - 1 else null
    }

    fun obvTrend(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size < n) return null
        val win = bars.takeLast(n)
        var s = 0.0
        for (i in 1 until win.size) {
            val d = win[i].close - win[i - 1].close
            s += (if (d > 0) 1.0 else if (d < 0) -1.0 else 0.0) * win[i].vol
        }
        val avgVol = win.map { it.vol }.average()
        return if (avgVol > 0) s / (avgVol * n) else null
    }

    fun amplitude(bars: List<KBar>, n: Int = 1): Double? {
        if (bars.size < n) return null
        val win = bars.takeLast(n)
        return win.map { if (it.open > 0) (it.high - it.low) / it.open else 0.0 }.average()
    }

    fun vol20d(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val rets = (1 until closes.size).map { (closes[it] - closes[it - 1]) / closes[it - 1] }.takeLast(n)
        return rets.map { it * it }.average().let { sqrt(it) } * sqrt(252.0) / 100
    }

    fun downsideVol(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val rets = (1 until closes.size).map { (closes[it] - closes[it - 1]) / closes[it - 1] }
            .filter { it < 0 }.takeLast(n)
        if (rets.isEmpty()) return 0.0
        return sqrt(rets.map { it * it }.average()) * sqrt(252.0) / 100
    }

    fun closePosition(bar: KBar): Double? {
        val range = bar.high - bar.low
        return if (range > 0) (bar.close - bar.low) / range else null
    }

    fun gapReturn(bars: List<KBar>): Double? {
        if (bars.size < 2) return null
        val pc = bars[bars.size - 2].close
        return if (pc > 0) (bars.last().open - pc) / pc else null
    }

    fun intradayReturn(bar: KBar): Double? =
        if (bar.open > 0) (bar.close - bar.open) / bar.open else null

    fun distHigh60d(bars: List<KBar>): Double? {
        if (bars.size < 60) return null
        val hh = bars.takeLast(60).maxOf { it.high }
        return if (hh > 0) bars.last().close / hh - 1 else null
    }

    fun position240d(bars: List<KBar>): Double? {
        if (bars.size < 240) return null
        val win = bars.takeLast(240)
        val hh = win.maxOf { it.high }
        val ll = win.minOf { it.low }
        return if (hh > ll) (bars.last().close - ll) / (hh - ll) else null
    }

    fun retSkew(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val rets = (1 until closes.size).map { (closes[it] - closes[it - 1]) / closes[it - 1] }.takeLast(n)
        if (rets.size < 3) return null
        val mean = rets.average()
        val sd = sqrt(rets.map { (it - mean) * (it - mean) }.sum() / rets.size)
        if (sd == 0.0) return 0.0
        return rets.map { ((it - mean) / sd) * ((it - mean) / sd) * ((it - mean) / sd) }.average()
    }

    fun maxRet20d(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val rets = (1 until closes.size).map { (closes[it] - closes[it - 1]) / closes[it - 1] }.takeLast(n)
        return rets.maxOrNull()
    }

    fun upDays20d(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val idx = (1 until closes.size).toList().takeLast(n)
        return idx.count { closes[it] > closes[it - 1] }.toDouble() / n
    }

    fun overnight20d(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n + 1) return null
        val rets = ArrayList<Double>()
        for (i in bars.size - n until bars.size) {
            val prev = bars.getOrNull(i - 1) ?: continue
            if (prev.close > 0) rets += (bars[i].open - prev.close) / prev.close
        }
        return rets.takeIf { it.isNotEmpty() }?.average()
    }

    fun intraday20d(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size < n) return null
        return bars.takeLast(n).mapNotNull { if (it.open > 0) (it.close - it.open) / it.open else null }
            .takeIf { it.isNotEmpty() }?.average()
    }

    fun amihud(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size < n) return null
        val win = bars.takeLast(n)
        val vals = win.mapNotNull { b ->
            if (b.vol <= 0) null
            else kotlin.math.abs((b.close - b.open) / b.open) / b.vol
        }
        return vals.takeIf { it.isNotEmpty() }?.average()
    }

    fun volPriceCorr(bars: List<KBar>, n: Int = 20): Double? {
        if (bars.size <= n) return null
        val closes = bars.map { it.close }
        val rets = (1 until closes.size).map { (closes[it] - closes[it - 1]) / closes[it - 1] }
        val vols = bars.map { it.vol }
        val r = rets.takeLast(n)
        val v = vols.drop(vols.size - n - 1).takeLast(n)
        if (r.size != v.size || r.size < 2) return null
        val mr = r.average(); val mv = v.average()
        var num = 0.0; var dr = 0.0; var dv = 0.0
        for (i in r.indices) {
            num += (r[i] - mr) * (v[i] - mv)
            dr += (r[i] - mr) * (r[i] - mr)
            dv += (v[i] - mv) * (v[i] - mv)
        }
        return if (dr > 0 && dv > 0) num / sqrt(dr * dv) else null
    }

    /** 全部因子一次算完（29 个） */
    fun allFactors(bars: List<KBar>): Map<String, Double?> = mapOf(
        "momentum_5d" to momentum(bars, 5),
        "momentum_10d" to momentum(bars, 10),
        "momentum_20d" to momentum(bars, 20),
        "momentum_60d" to momentum(bars, 60),
        "ret_1d" to momentum(bars, 1),
        "ma20_bias" to maBias(bars, 20),
        "ma60_bias" to maBias(bars, 60),
        "rsi_14" to MarketDataClient.rsi(bars, 14),
        "macd_hist_pct" to macdHistPct(bars),
        "vol_ratio_5d" to MarketDataClient.volumeRatio(bars, 5),
        "vol_trend_5_10" to volTrend(bars, 5, 10),
        "obv_trend_20d" to obvTrend(bars, 20),
        "amplitude" to amplitude(bars, 1),
        "vol_20d" to vol20d(bars, 20),
        "downside_vol_20d" to downsideVol(bars, 20),
        "close_position" to bars.lastOrNull()?.let { closePosition(it) },
        "gap_return" to gapReturn(bars),
        "intraday_return" to bars.lastOrNull()?.let { intradayReturn(it) },
        "dist_high_60d" to distHigh60d(bars),
        "position_240d" to position240d(bars),
        "ret_skew_20d" to retSkew(bars, 20),
        "max_ret_20d" to maxRet20d(bars, 20),
        "up_days_20d" to upDays20d(bars, 20),
        "overnight_20d" to overnight20d(bars, 20),
        "intraday_20d" to intraday20d(bars, 20),
        "amihud_20d" to amihud(bars, 20),
        "vol_price_corr_20d" to volPriceCorr(bars, 20),
    )

    private fun macdHistPct(bars: List<KBar>): Double? {
        val (_, _, hist) = MarketDataClient.macd(bars)
        val h = hist.lastOrNull() ?: return null
        val c = bars.last().close
        return if (c > 0) h / c else null
    }
}
