package com.skapp.app.data

import kotlin.math.sqrt

/**
 * 组合风控引擎：把「一组票」当成一个整体来量化风险。
 *
 * ## 为什么需要（与 [PositionCap] 不是同一件事）
 *
 * [PositionCap] 回答的是「今天大盘允许上多少仓」——那是**总量**闸门，看宽度、跌停数、
 * 连板高度。它不知道你手上这 5 只票是不是同一个板块、是不是同涨同跌。真正的组合风险
 * 在内部：5 只半导体等于把一份赌注下 5 倍，而有 5 个仓位并不等于分散。
 *
 * 端上此前没有任何组合层计算：选股出来是一张列表，仓位建议是一个总数，中间这段空白
 * 只能靠人看着行业猜。
 *
 * ## 边界
 *
 * 只吃 K 线（收盘价序列），不读网络、不读云端。相关性与回撤都是**历史统计**，
 * 不预测未来；样本不足时返回 `null` 而不是拿小样本硬算出一个看似精确的数——
 * 组合风控最怕的就是假精确。
 */
object PortfolioRiskEngine {

    /** 一个持仓/候选。`weight` 用相对权重即可（内部会归一化），`sector` 缺省时不参与集中度计算。 */
    data class Holding(
        val code: String,
        val bars: List<KBar>,
        val weight: Double = 1.0,
        val sector: String? = null,
    )

    data class PairCorr(val a: String, val b: String, val corr: Double)

    data class Result(
        /** 组合年化波动率（%），日收益标准差 × √250 */
        val volPct: Double?,
        /** 按权重合成的历史净值最大回撤（%，正数） */
        val maxDrawdownPct: Double?,
        /** 两两相关系数均值 */
        val avgCorr: Double?,
        /** 相关性最高的一对（越接近 1 越说明「看着分散、实际同一注」） */
        val maxPair: PairCorr?,
        /** 行业集中度 HHI（0~1，越接近 1 越集中）。需要 [Holding.sector] */
        val sectorHhi: Double?,
        val topSector: String?,
        val topSectorWeight: Double?,
        /** 低 / 中 / 高 */
        val level: String,
        /** 面向用户的具体建议，按严重程度排序 */
        val advices: List<String>,
        /** 未能参与计算的项目及原因 */
        val missing: List<String>,
    )

    /** 相关系数低于此值视为「基本独立」，高于 [CORR_HIGH] 视为「同一注」。 */
    private const val CORR_MID = 0.5
    private const val CORR_HIGH = 0.75

    /** 少于这个根数不参与相关性/回撤（样本太少，相关系数没有意义）。 */
    private const val MIN_BARS = 30

    private const val TRADING_DAYS = 250.0

    fun assess(holdings: List<Holding>, lookback: Int = 60): Result {
        val missing = ArrayList<String>()
        if (holdings.isEmpty()) {
            return Result(null, null, null, null, null, null, null, "低", emptyList(), listOf("无持仓"))
        }

        val usable = holdings.filter { it.bars.size >= MIN_BARS }
        holdings.filter { it.bars.size < MIN_BARS }.forEach {
            missing += "${it.code} 只有 ${it.bars.size} 根K线（需 ≥$MIN_BARS），未参与组合风险"
        }
        if (usable.isEmpty()) {
            return Result(null, null, null, null, null, null, null, "未知", emptyList(), missing + "全部样本不足")
        }

        val total = usable.sumOf { it.weight.coerceAtLeast(0.0) }
        val weights = if (total <= 0.0) {
            // 权重没给或全 0 时按等权，比直接丢掉更有用
            usable.associate { it.code to 1.0 / usable.size }
        } else {
            usable.associate { it.code to it.weight.coerceAtLeast(0.0) / total }
        }

        // ── 收益率序列（按各自最后 lookback 根对齐；两端长度可能不等，取短的一致部分） ──
        val rets = usable.associate { h -> h.code to dailyReturns(h.bars, lookback) }
        val pairs = ArrayList<PairCorr>()
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                val a = usable[i].code
                val b = usable[j].code
                val c = corr(rets[a], rets[b])
                if (c != null) pairs += PairCorr(a, b, c)
            }
        }
        val avgCorr = pairs.takeIf { it.isNotEmpty() }?.map { it.corr }?.average()
        val maxPair = pairs.maxByOrNull { it.corr }

        // ── 组合波动率：w'Σw ──
        val volPct = portfolioVol(usable.map { it.code }, weights, rets, pairs)

        // ── 组合回撤：按权重合成日收益 → 净值 → 最大回撤 ──
        val maxDrawdown = maxDrawdown(usable.map { it.code }, weights, rets)

        // ── 行业集中度 ──
        val sectorWeights = usable
            .mapNotNull { h -> h.sector?.takeIf { it.isNotBlank() }?.let { it to weights[h.code]!! } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, w) -> w.sum() }
        val hhi = sectorWeights.takeIf { it.isNotEmpty() }?.values?.sumOf { it * it }
        val topSector = sectorWeights.maxByOrNull { it.value }?.key
        val topSectorWeight = sectorWeights.maxOfOrNull { it.value }
        if (usable.any { it.sector.isNullOrBlank() } && hhi != null) {
            missing += "部分持仓缺板块标签，集中度只按有标签的算"
        }

        // ── 结论 ──
        val advices = ArrayList<String>()
        if (maxPair != null && maxPair.corr >= CORR_HIGH) {
            advices += "${maxPair.a}/${maxPair.b} 相关性 ${fmt(maxPair.corr)}，几乎同一注，同时持有等于加倍下注"
        } else if (avgCorr != null && avgCorr >= CORR_MID) {
            advices += "持仓平均相关性 ${fmt(avgCorr)}，整体偏同向，大盘一跌会一起跌"
        }
        if (topSectorWeight != null && topSectorWeight >= 0.5 && topSector != null) {
            advices += "$topSector 占 ${(topSectorWeight * 100).toInt()}%，行业过度集中"
        }
        if (maxDrawdown != null && maxDrawdown >= 15.0) {
            advices += "按现权重复盘，历史上曾回撤 ${(maxDrawdown * 100).toInt()}%，当前仓位需按此设止损"
        }
        if (volPct != null && volPct >= 35.0) {
            advices += "组合年化波动 ${volPct.toInt()}%，高于单票常见区间，建议降总仓或分散"
        }

        val level = when {
            (maxPair?.corr ?: 0.0) >= CORR_HIGH || (topSectorWeight ?: 0.0) >= 0.6 -> "高"
            (avgCorr ?: 0.0) >= CORR_MID || (topSectorWeight ?: 0.0) >= 0.4 -> "中"
            else -> "低"
        }
        if (advices.isEmpty()) advices += "组合内相关性与行业分布未触发风险阈值"

        return Result(
            volPct = volPct,
            maxDrawdownPct = maxDrawdown?.let { it * 100 },
            avgCorr = avgCorr,
            maxPair = maxPair,
            sectorHhi = hhi,
            topSector = topSector,
            topSectorWeight = topSectorWeight,
            level = level,
            advices = advices,
            missing = missing,
        )
    }

    // ── 内部计算 ────────────────────────────────────────────

    /** 取最后 [lookback]+1 根收盘价算日收益率；不足返回 null。 */
    private fun dailyReturns(bars: List<KBar>, lookback: Int): DoubleArray? {
        val closes = bars.map { it.close }.takeLast(lookback + 1)
        if (closes.size < MIN_BARS) return null
        val out = DoubleArray(closes.size - 1)
        for (i in 1 until closes.size) {
            val prev = closes[i - 1]
            if (prev <= 0.0) return null
            out[i - 1] = (closes[i] - prev) / prev
        }
        return out
    }

    /** Pearson 相关系数。两条序列长度不同时按末尾对齐取短的那段。 */
    internal fun corr(a: DoubleArray?, b: DoubleArray?): Double? {
        if (a == null || b == null) return null
        val n = minOf(a.size, b.size)
        if (n < MIN_BARS - 1) return null
        val x = a.takeLast(n)
        val y = b.takeLast(n)
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }

    /**
     * 组合年化波动。
     *
     * 协方差用「相关系数 × 各自标准差」还原——相关系数在 [pairs] 里，但缺对（样本不足）
     * 的按 0 相关处理并计入 missing 语义（此时是低估，不是高估，方向上可以接受）。
     */
    private fun portfolioVol(
        codes: List<String>,
        weights: Map<String, Double>,
        rets: Map<String, DoubleArray?>,
        pairs: List<PairCorr>,
    ): Double? {
        val stds = codes.associateWith { c -> rets[c]?.let { stdOf(it) } }
        if (stds.values.any { it == null }) return null
        val corrMap = pairs.associate { (it.a to it.b) to it.corr }
        var variance = 0.0
        for (i in codes.indices) {
            for (j in codes.indices) {
                val wi = weights[codes[i]] ?: 0.0
                val wj = weights[codes[j]] ?: 0.0
                val si = stds[codes[i]] ?: return null
                val sj = stds[codes[j]] ?: return null
                val rho = if (i == j) 1.0 else corrMap[codes[i] to codes[j]] ?: corrMap[codes[j] to codes[i]] ?: 0.0
                variance += wi * wj * rho * si * sj
            }
        }
        if (variance <= 0.0) return null
        return sqrt(variance) * sqrt(TRADING_DAYS) * 100
    }

    private fun stdOf(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** 按权重合成日收益 → 净值 → 最大回撤（正数百分比）。 */
    private fun maxDrawdown(
        codes: List<String>,
        weights: Map<String, Double>,
        rets: Map<String, DoubleArray?>,
    ): Double? {
        val series = codes.map { rets[it] }
        if (series.any { it == null }) return null
        val n = series.minOf { it!!.size }
        if (n < MIN_BARS - 1) return null

        var nav = 1.0
        var peak = 1.0
        var mdd = 0.0
        for (i in 0 until n) {
            var r = 0.0
            for (c in codes) {
                val w = weights[c] ?: 0.0
                r += w * rets[c]!![rets[c]!!.size - n + i]
            }
            nav *= (1.0 + r)
            if (nav > peak) peak = nav
            val dd = (peak - nav) / peak
            if (dd > mdd) mdd = dd
        }
        return mdd
    }

    private fun fmt(v: Double) = "%.2f".format(v)
}
