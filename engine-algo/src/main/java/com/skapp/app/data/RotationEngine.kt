package com.skapp.app.data

/**
 * rotation 板块轮动引擎（V1）。
 *
 * ## 它和市场状态引擎的分工
 * [MarketStateEngine] 已经在算市场层的 `E`（phase/cycle/global/winloop/kelly 加权）并给出
 * 「进攻/防守/中性观望」。本引擎**不重造第二套市场状态**，只做市场状态引擎不做的那件事：
 * **横向把板块排开**，给出最强/最弱方向与分散度，再用 E 把轮动结论落到三档上。
 * 两套标签并存会出现「E 说防守、rotation 说进攻」，那是口径事故，不是发现。
 *
 * ## 复用而不是重算
 * 当日板块强度直接用 [LocalSectors.Row.strength]（涨幅 0.35 + 广度 0.30 + 结构 0.20 + 涨停 0.15，
 * 缺项按剩余权重归一）。本引擎只补三项它没有的：5 日动量、成交额占比变化、横截面分位。
 *
 * ## 口径红线
 * - 板块成交额占比变化必须用**同口径成分集**算（快照要存成分代码集），否则份额变化是分组变动不是资金迁移。
 * - 5 日动量来自「覆盖成分股的日 K」，覆盖不到就退出该项并按剩余权重归一，不许拿 0 顶。
 * - 行业映射覆盖率 < 0.75 时整体判 unknown：热度加总已经失真，排序再漂亮也没意义。
 */
object RotationEngine {

    const val VERSION = 1

    data class Params(
        val wStrength: Double = 0.35,
        val wMom5: Double = 0.25,
        val wBreadth: Double = 0.20,
        val wAmt: Double = 0.20,
        val minSectors: Int = 8,
        val minMembers: Int = 5,
        val dispGate: Double = 1.2,
        val attackE: Double = 30.0,
        val defenseE: Double = -30.0,
        val bullRot: Double = 0.35,
        val bearRot: Double = -0.35,
        val breadthPanic: Double = 0.15,
        val limitHotShare: Double = 0.02,
        val minCoverage: Double = 0.75,
        val minUsableWeight: Double = 0.40,
        val topN: Int = 3,
        val mom5Days: Int = 5,
    )

    data class SectorRot(
        val name: String,
        val rot: Double,
        val strength: Double?,
        val mom5: Double?,
        val breadth: Double?,
        val amtDeltaPp: Double?,
        val n: Int,
        val lowN: Boolean,
        val usedWeight: Double,
    )

    data class Result(
        val marketState: String,
        val e: Double?,
        val marketRot: Double?,
        val dispersion: Double?,
        val strongest: List<SectorRot>,
        val weakest: List<SectorRot>,
        val ranked: List<SectorRot>,
        val coverage: Double,
        val momCoverage: Double,
        val confidence: Double,
        val status: String,
        val reasons: List<String>,
    )

    fun compute(
        sectors: LocalSectors.Result?,
        mom5Pct: Map<String, Double>? = null,
        amtShareDeltaPp: Map<String, Double>? = null,
        marketE: Double? = null,
        breadth: Double? = null,
        p: Params = Params(),
    ): Result {
        val rows = sectors?.rows
        if (rows.isNullOrEmpty()) return empty("insufficient", listOf("板块聚合不可得"), 0.0, 0.0)
        if (rows.size < p.minSectors) {
            return empty("insufficient", listOf("板块数 ${rows.size} < ${p.minSectors}，无法做横截面分位"), sectors.coverage, 0.0)
        }
        val totalHeat = rows.sumOf { it.heat }
        val reasons = ArrayList<String>(6)
        if (sectors.coverage < p.minCoverage) {
            reasons.add("行业映射覆盖 ${fmt(sectors.coverage * 100)}% < ${fmt(p.minCoverage * 100)}%，热度加总失真，状态判 unknown")
        }
        val mapped = rows.sumOf { it.n }
        val limitHot = mapped > 0 && rows.sumOf { it.limitUp }.toDouble() / mapped > p.limitHotShare
        if (limitHot) reasons.add("涨停占比超 ${fmt(p.limitHotShare * 100)}%，广度被极值主导，广度权重降到 0.10")

        val strengthSorted = rows.mapNotNull { strengthOf(it) }.sorted().toDoubleArray()
        val momSorted = rows.mapNotNull { mom5Pct?.get(it.name) }.sorted().toDoubleArray()
        val amtSorted = rows.mapNotNull { amtShareDeltaPp?.get(it.name) }.sorted().toDoubleArray()
        val momCoverage = if (rows.isEmpty()) 0.0 else
            rows.count { mom5Pct?.get(it.name)?.isFinite() == true }.toDouble() / rows.size

        val ranked = rows.map { r ->
            var wS = p.wStrength
            val wB = if (limitHot) 0.10 else p.wBreadth
            var wM = p.wMom5
            var wA = p.wAmt
            if (limitHot) wS += p.wBreadth - 0.10
            if (momCoverage < 0.30) wM = 0.0
            if (totalHeat <= 0.0 || amtShareDeltaPp == null) wA = 0.0
            val sVal = strengthOf(r)
            val mVal = mom5Pct?.get(r.name)
            val aVal = amtShareDeltaPp?.get(r.name)
            val br = breadthOf(r)
            var sum = 0.0
            var acc = 0.0
            if (sVal != null && strengthSorted.isNotEmpty()) {
                acc += wS * pct2(FlowEngine.percentileOf(strengthSorted, sVal)); sum += wS
            }
            if (mVal != null && mVal.isFinite() && momSorted.isNotEmpty() && wM > 0.0) {
                acc += wM * pct2(FlowEngine.percentileOf(momSorted, mVal)); sum += wM
            }
            if (br != null) {
                acc += wB * br; sum += wB
            }
            if (aVal != null && aVal.isFinite() && amtSorted.isNotEmpty() && wA > 0.0) {
                acc += wA * pct2(FlowEngine.percentileOf(amtSorted, aVal)); sum += wA
            }
            SectorRot(
                name = r.name,
                rot = if (sum > 0.0) acc / sum else Double.NaN,
                strength = sVal,
                mom5 = mVal,
                breadth = br,
                amtDeltaPp = aVal,
                n = r.n,
                lowN = r.n < p.minMembers,
                usedWeight = sum,
            )
        }.sortedByDescending { if (it.rot.isFinite()) it.rot else -9.0 }

        val pool = ranked.filter { it.rot.isFinite() && it.usedWeight >= p.minUsableWeight }
        if (pool.isEmpty()) return empty("insufficient", reasons + "无板块权重达 ${fmt(p.minUsableWeight)}", sectors.coverage, momCoverage)
        val rotSorted = pool.map { it.rot }.sorted()
        val marketRot = median(rotSorted)
        val disp = pctile(rotSorted, 0.90) - pctile(rotSorted, 0.10)
        val tradable = pool.filter { !it.lowN }
        val strongest = tradable.take(p.topN)
        val weakest = tradable.reversed().take(p.topN)
        var state = when {
            marketE == null -> "unknown"
            marketE > p.attackE && disp < p.dispGate -> "attack"
            marketE < p.defenseE -> "defense"
            else -> "balanced"
        }
        if (marketE != null && marketE > p.attackE && disp >= p.dispGate) {
            reasons.add("进攻但结构分化（离散度 ${fmt(disp)} ≥ ${fmt(p.dispGate)}），降级均衡")
        }
        if (breadth != null && breadth < p.breadthPanic) {
            state = "defense"
            reasons.add("涨跌家数广度 ${fmt(breadth)} 极弱，强制防守（只看相对强弱）")
        }
        if (sectors.coverage < p.minCoverage) state = "unknown"
        val conf = when {
            state == "unknown" -> 0.30
            pool.size < p.minSectors -> 0.50
            momCoverage < 0.30 -> 0.65
            else -> 0.85
        }
        return Result(
            marketState = state,
            e = marketE,
            marketRot = marketRot,
            dispersion = disp,
            strongest = strongest,
            weakest = weakest,
            ranked = ranked,
            coverage = sectors.coverage,
            momCoverage = momCoverage,
            confidence = conf,
            status = if (state == "unknown") "degraded" else "ok",
            reasons = reasons,
        )
    }

    fun labelOf(sector: SectorRot, p: Params = Params()): String = when {
        !sector.rot.isFinite() -> "无数据"
        sector.rot >= p.bullRot -> "强"
        sector.rot <= p.bearRot -> "弱"
        else -> "中"
    }

    private fun strengthOf(r: LocalSectors.Row): Double? = r.strength ?: r.chg1d

    private fun breadthOf(r: LocalSectors.Row): Double? {
        val d = r.up + r.down
        return if (d <= 0) null else (r.up - r.down).toDouble() / d
    }

    private fun pct2(p: Double): Double = (p / 50.0 - 1.0).coerceIn(-1.0, 1.0)

    private fun median(sorted: List<Double>): Double = pctile(sorted, 0.5)

    private fun pctile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun empty(status: String, reasons: List<String>, coverage: Double, momCoverage: Double) = Result(
        marketState = "unknown",
        e = null,
        marketRot = null,
        dispersion = null,
        strongest = emptyList(),
        weakest = emptyList(),
        ranked = emptyList(),
        coverage = coverage,
        momCoverage = momCoverage,
        confidence = 0.0,
        status = status,
        reasons = reasons,
    )

    private fun fmt(v: Double?): String = if (v == null) "null" else String.format("%.2f", v)
}
