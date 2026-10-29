package com.skapp.app.data

/**
 * 价位引擎（POC / 分形聚类）—— 端上复刻云端 `price_level_engine.py` 的核心部分。
 *
 * 口径唯一真值：`stocks-ai-view/scripts/engines/price_level_engine.py:176-340`
 *   `fractals(wing=2)` / `cluster(band_pct=0.015)` / `volume_profile(bins=40)` /
 *   `compute_levels` 的支撑阻力选择与 POC 补强逻辑。
 *
 * 为什么补这个：`KeyLevels` 只给「20 日最低/波幅一半」这类止损止盈线，没有任何
 * **筹码密集区**概念。云端用成交量分布找 POC、用分形聚类找真实触碰过的价位，
 * 端上一直缺这两样，所以「支撑位」只能靠 MA 猜。
 *
 * 刻意的取舍（与云端不同且必须写清）：
 *  - 不实现筹码衰减位（`chip`）、波段黄金分割（`swing_fib`）、整数关口（`round_number`）；
 *    它们依赖更多云端上下文，本轮只补 POC 与分形聚类 + 枢轴；
 *  - POC 补强项的 `touches` 在云端是字符串 `"poc"`，端上用 null 表示「不是触碰次数」，
 *    不硬编成 1（否则和「真实触碰过 1 次」不可区分）。
 */
object PriceLevelEngine {

    private const val WING = 2
    private const val BAND_PCT = 0.015
    private const val BINS = 40
    private const val ZONE_MIN_SHARE = 0.03

    /** 一个价位簇。`touches == null` 表示来自成交量分布（POC）而非分形触碰。 */
    data class Cluster(val price: Double, val touches: Int?, val src: String, val lastTouchIdx: Int? = null)

    data class Zone(val low: Double, val high: Double, val poc: Double, val volShare: Double)

    data class Pivot(val p: Double, val r1: Double, val s1: Double, val r2: Double, val s2: Double)

    data class Result(
        val current: Double,
        val poc: Double?,
        val zones: List<Zone>,
        val supports: List<Cluster>,
        val resistances: List<Cluster>,
        val pivot: Pivot,
    )

    private fun r3(x: Double) = CanonicalIndicators.pyRound(x, 3)

    /** 分形高低点：前后 wing 根内的局部极值（等值也算，云端 `<=` / `>=`）。 */
    fun fractals(bars: List<KBar>): Pair<List<Pair<Int, Double>>, List<Pair<Int, Double>>> {
        val highs = mutableListOf<Pair<Int, Double>>()
        val lows = mutableListOf<Pair<Int, Double>>()
        for (i in WING until bars.size - WING) {
            val h = bars[i].high
            val l = bars[i].low
            val isHigh = (i - WING..i + WING).all { j -> j == i || bars[j].high <= h }
            val isLow = (i - WING..i + WING).all { j -> j == i || bars[j].low >= l }
            if (isHigh) highs += i to h
            if (isLow) lows += i to l
        }
        return highs to lows
    }

    /** 价位聚类：±band 带宽按「当前簇均值」合并，按触碰次数降序、同次数按价格升序。 */
    fun cluster(points: List<Pair<Int, Double>>, bandPct: Double = BAND_PCT): List<Cluster> {
        if (points.isEmpty()) return emptyList()
        val pts = points.sortedBy { it.second }
        val groups = mutableListOf<MutableList<Pair<Int, Double>>>()
        var cur = mutableListOf(pts.first())
        for (p in pts.drop(1)) {
            val base = cur.sumOf { it.second } / cur.size
            if (base != 0.0 && kotlin.math.abs(p.second - base) / base <= bandPct) {
                cur += p
            } else {
                groups += cur
                cur = mutableListOf(p)
            }
        }
        groups += cur
        return groups.map { g ->
            Cluster(
                price = r3(g.sumOf { it.second } / g.size),
                touches = g.size,
                src = "fractal",
                lastTouchIdx = g.maxOf { it.first },
            )
        }.sortedWith(compareByDescending<Cluster> { it.touches ?: 0 }.thenBy { it.price })
    }

    /** 量价剖面：按典型价 (H+L+C)/3 归 bin，返回 POC 与成交占比 ≥3% 的密集区。 */
    fun volumeProfile(bars: List<KBar>, bins: Int = BINS): Pair<Double?, List<Zone>> {
        if (bars.isEmpty()) return null to emptyList()
        val hi = bars.maxOf { it.high }
        val lo = bars.minOf { it.low }
        if (hi <= lo) return null to emptyList()
        val width = (hi - lo) / bins
        val volBins = DoubleArray(bins)
        for (b in bars) {
            val tp = (b.high + b.low + b.close) / 3
            val i = kotlin.math.min(((tp - lo) / width).toInt(), bins - 1)
            if (i >= 0) volBins[i] += b.vol
        }
        val total = volBins.sum().takeIf { it != 0.0 } ?: 1.0
        var pocI = 0
        for (i in 1 until bins) if (volBins[i] > volBins[pocI]) pocI = i
        val poc = r3(lo + (pocI + 0.5) * width)
        val zones = volBins.indices.filter { volBins[it] / total >= ZONE_MIN_SHARE }.map { i ->
            Zone(
                low = r3(lo + i * width),
                high = r3(lo + (i + 1) * width),
                poc = r3(lo + (i + 0.5) * width),
                volShare = CanonicalIndicators.pyRound(volBins[i] / total, 4),
            )
        }.sortedByDescending { it.volShare }
        return poc to zones
    }

    /**
     * 支撑/阻力：现价下方最近的 3 个低点簇 / 上方最近的 3 个高点簇，
     * 现价处于窗口极值时退回窗口极值；POC 在现价另一侧且与已有簇差 >0.5% 时补强。
     */
    fun levels(bars: List<KBar>, lookback: Int = 250): Result? {
        if (bars.size < 30) return null
        val win = if (bars.size > lookback) bars.takeLast(lookback) else bars
        val cur = win.last().close
        if (cur <= 0.0) return null
        val (fh, fl) = fractals(win)
        val hiClusters = cluster(fh)
        val loClusters = cluster(fl)
        val (poc, zones) = volumeProfile(win)

        val supports = loClusters.filter { (it.price ?: 0.0) < cur }
            .sortedByDescending { it.price }.take(3).toMutableList()
        val resistances = hiClusters.filter { (it.price ?: 0.0) > cur }
            .sortedBy { it.price }.take(3).toMutableList()
        if (supports.isEmpty()) {
            supports += Cluster(r3(win.minOf { it.low }), 1, "window_low_fallback")
        }
        if (resistances.isEmpty()) {
            resistances += Cluster(r3(win.maxOf { it.high }), 1, "window_high_fallback")
        }
        if (poc != null) {
            val far = { list: List<Cluster> -> list.all { kotlin.math.abs(poc - it.price) / it.price > 0.005 } }
            if (poc < cur && far(supports)) supports += Cluster(poc, null, "volume_profile")
            else if (poc > cur && far(resistances)) resistances += Cluster(poc, null, "volume_profile")
        }

        val b = win.last()
        val p = (b.high + b.low + b.close) / 3
        return Result(
            current = cur,
            poc = poc,
            zones = zones,
            supports = supports,
            resistances = resistances,
            pivot = Pivot(
                p = r3(p),
                r1 = r3(2 * p - b.low),
                s1 = r3(2 * p - b.high),
                r2 = r3(p + (b.high - b.low)),
                s2 = r3(p - (b.high - b.low)),
            ),
        )
    }
}
