package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.tanh

/**
 * flow 资金流强度引擎（V1）。
 *
 * ## 为什么端上做得出
 * 云端有逐日主力净额序列，端上只有**当前时刻**的 `f62`（[LiveMarket.Row.mainNet]）。
 * 但「今天这只票资金进还是出」不需要历史序列：成交额 × 有符号标准化涨跌 × 流动性折算，
 * 就是净流入的代理量。缺的只是多日累计——那类量端上不给就是不给（[Result.m5Ratio] 为 null，不写 0）。
 *
 * ## 三档必须可区分，不许混用
 * - `local`：只有成交额/换手/量比，纯代理，置信 0.70
 * - `local_main`：端上另有当日 mainNet（clist 同批取回，不增加请求），当日占比是真值，置信 0.85
 * - `cloud`：云端 ≥5 日 mainNet 序列可得，才谈得上 5 日累计，置信 0.95
 *
 * 三档的分值不可跨档排序：`tier` 必须与 `score` 一起返回，同档内才可比。
 * 拿 `local` 的 68 分去压 `cloud` 的 61 分，等于拿代理量当真值。
 *
 * ## 口径红线
 * - 停牌（`tradable=false`）不进横截面：它的 pctChg 是 0，会把分位分布拉平。
 * - 一字板成交额极小但涨跌打满，代理量会虚高 → 按 5 日均额闸门压权重，不剔除（剔除等于假装没这只票）。
 * - 除权/复牌首日 |pctChg| 超过涨跌停上限 → `score=null`，不给分（[LiveMarket.Row.limitCap] 按板块给上限）。
 */
object FlowEngine {

    const val VERSION = 1

    /** 参数写死默认值，全部是经验假设，须用云端逐日 mainNet 回测校准后才可动。 */
    data class Params(
        val pctSd: Double = 5.0,
        val tanhK: Double = 1.5,
        val turnoverFloor: Double = 0.5,
        val turnoverHigh: Double = 20.0,
        val lowTurnWeight: Double = 0.3,
        val highTurnWeight: Double = 0.5,
        val volRatioK: Double = 0.5,
        val volRatioCap: Double = 2.0,
        val wCross: Double = 0.6,
        val wAbs: Double = 0.4,
        val gatedWCross: Double = 0.3,
        val minPeerN: Int = 300,
        val limitAmountRatio: Double = 0.5,
        val limitWeight: Double = 0.2,
        val mainUnit: Double = 0.05,
        val bullThreshold: Double = 65.0,
        val bearThreshold: Double = 35.0,
        val bullBreadthGate: Double = 0.85,
        val bearBreadthGate: Double = 0.15,
        val minCloudN: Int = 30,
        val minAmountYuan: Double = 1.0e7,
    )

    /** 云端增强输入：当日主力净额占成交额、5 日累计占成交额（均来自云端序列）。 */
    data class CloudFlow(val pctMain: Double, val m5Ratio: Double?)

    data class Result(
        val code: String,
        val score: Double?,
        val signal: String,
        val tier: String,
        val netProxyYuan: Double?,
        val fsiPct: Double?,
        val fsiAbs: Double?,
        val pctMain: Double?,
        val m5Ratio: Double?,
        val scoreDelta: Double?,
        val liquidityWeight: Double,
        val volRatioFactor: Double,
        val confidence: Double,
        val reasons: List<String>,
    )

    fun signalOf(score: Double?, p: Params = Params()): String = when {
        score == null -> "insufficient"
        score >= p.bullThreshold -> "bullish"
        score <= p.bearThreshold -> "bearish"
        else -> "neutral"
    }

    /** 单票（无横截面）：只能出绝对强度档，`tier` 恒为 local_abs。 */
    fun compute(
        row: LiveMarket.Row,
        amount5d: Double? = null,
        cloud: CloudFlow? = null,
        turnScale: Double = 1.0,
        p: Params = Params(),
    ): Result = screen(listOf(row), mapOf(row.code to amount5d), mapOfNotNull(row.code, cloud), null, turnScale, p)
        .firstOrNull() ?: emptyResult(row.code, "insufficient", listOf("单票无横截面样本"))

    /**
     * 全市场横截面。[turnScale] 用于盘中早期：09:45 前换手率是「已交易分钟」口径，
     * 传 240/已交易分钟 把换手还原成全天预估，否则一律被判成低换手。
     */
    fun screen(
        rows: List<LiveMarket.Row>,
        amount5dOf: Map<String, Double?> = emptyMap(),
        cloud: Map<String, CloudFlow> = emptyMap(),
        breadth: Double? = null,
        turnScale: Double = 1.0,
        p: Params = Params(),
    ): List<Result> {
        val suspended = rows.filter { !it.tradable }.map { it.code }.toSet()
        val cells = ArrayList<Cell>(rows.size)
        val anomalies = HashMap<String, String>()
        for (r in rows) {
            if (!r.tradable) continue
            val c = cellOf(r, amount5dOf[r.code], turnScale, p)
            if (c == null) {
                anomalies[r.code] = "输入缺字段或除权异常"
                continue
            }
            cells.add(c)
        }
        val byCode = HashMap<String, Cell>(cells.size)
        for (c in cells) byCode[c.code] = c
        val netSorted = cells.map { it.netProxy }.sorted().toDoubleArray()
        val n = netSorted.size
        val useCross = n >= p.minPeerN
        val gated = breadth != null && (breadth > p.bullBreadthGate || breadth < p.bearBreadthGate)
        val w1 = when {
            !useCross -> 0.0
            gated -> p.gatedWCross
            else -> p.wCross
        }
        val w2 = 1.0 - w1
        val cloudN = cloud.size
        val cloudOK = cloudN >= p.minCloudN
        return rows.map { r ->
            if (suspended.contains(r.code)) {
                return@map emptyResult(r.code, "insufficient", listOf("停牌/无成交，不进横截面"))
            }
            val anomaly = anomalies[r.code]
            if (anomaly != null) {
                return@map emptyResult(r.code, "insufficient", listOf(anomaly))
            }
            val c = byCode[r.code] ?: return@map emptyResult(r.code, "insufficient", listOf("代理量缺失"))
            val fsiPct = if (useCross) percentileOf(netSorted, c.netProxy) else null
            val local = w1 * (fsiPct ?: c.fsiAbs) + w2 * c.fsiAbs
            val main = r.mainNet?.let { if (r.amount != null && r.amount!! > 0) it / r.amount!! else null }
            val cf = cloud[r.code]
            val pctMain = cf?.pctMain ?: main
            val m5 = cf?.m5Ratio
            val reasons = ArrayList<String>(4)
            if (gated) reasons.add("普涨/普跌日，横截面分位失效，权重切 0.3/0.7（breadth=${fmt(breadth)}）")
            if (!useCross) reasons.add("横截面样本 $n < ${p.minPeerN}，只出绝对强度")
            if (c.limitLocked) reasons.add("封板且成交额 < ${p.limitAmountRatio}×5 日均额，代理量已压权")
            if (cf != null && !cloudOK) reasons.add("云端序列样本 $cloudN < ${p.minCloudN}，不参与云端档")
            val m1 = pctMain?.let { 50.0 + 50.0 * tanh(it / p.mainUnit) }
            val m5s = m5?.let { 50.0 + 50.0 * tanh(it / p.mainUnit) }
            val score: Double
            val tier: String
            val confidence: Double
            when {
                m1 != null && m5s != null && cloudOK -> {
                    score = 0.40 * local + 0.30 * m1 + 0.30 * m5s
                    tier = "cloud"
                    confidence = 0.95
                    reasons.add("云端档：当日占比 + 5 日累计占比参与合成")
                }
                m1 != null -> {
                    score = 0.65 * local + 0.35 * m1
                    tier = "local_main"
                    confidence = 0.85
                    reasons.add("端上档：当日 mainNet/成交额 是真值，5 日累计不可得")
                }
                else -> {
                    score = local
                    tier = if (useCross) "local" else "local_abs"
                    confidence = if (useCross) 0.70 else 0.42
                    reasons.add("纯代理档：无 mainNet，成交额×标准化涨跌为净额代理")
                }
            }
            val s = score.coerceIn(0.0, 100.0)
            Result(
                code = r.code,
                score = s,
                signal = signalOf(s, p),
                tier = tier,
                netProxyYuan = c.netProxy,
                fsiPct = fsiPct,
                fsiAbs = c.fsiAbs,
                pctMain = pctMain,
                m5Ratio = m5,
                scoreDelta = if (tier == "cloud") s - local else null,
                liquidityWeight = c.liq,
                volRatioFactor = c.vrF,
                confidence = confidence,
                reasons = reasons,
            )
        }
    }

    private data class Cell(
        val code: String,
        val netProxy: Double,
        val fsiAbs: Double,
        val liq: Double,
        val vrF: Double,
        val limitLocked: Boolean,
    )

    private fun cellOf(r: LiveMarket.Row, amount5d: Double?, turnScale: Double, p: Params): Cell? {
        val amount = r.amount ?: return null
        val pct = r.pctChg ?: return null
        val turn = r.turnoverRate ?: return null
        if (amount <= 0.0) return null
        if (abs(pct) > r.limitCap + 0.5) return null
        val t = tanh((pct / p.pctSd).coerceIn(-3.0, 3.0) / p.tanhK)
        val turnAdj = turn * turnScale
        var liq = when {
            turnAdj < p.turnoverFloor -> p.lowTurnWeight
            turnAdj > p.turnoverHigh -> p.highTurnWeight
            else -> 1.0
        }
        if (amount < p.minAmountYuan) liq *= p.lowTurnWeight
        val vr = r.volRatio
        val bounded = vr?.let { ((it - 1.0) / 2.0).coerceIn(-1.0, 1.0) } ?: 0.0
        val vrF = 1.0 + p.volRatioK * bounded
        val limitLocked = (r.atLimitUp || r.atLimitDown) &&
            amount5d != null && amount5d > 0 && amount < p.limitAmountRatio * amount5d
        if (limitLocked) liq *= p.limitWeight
        return Cell(
            code = r.code,
            netProxy = amount * t / 3.0 * liq * vrF,
            fsiAbs = (50.0 + 50.0 * tanh(3.0 * t)).coerceIn(0.0, 100.0),
            liq = liq,
            vrF = vrF,
            limitLocked = limitLocked,
        )
    }

    private fun emptyResult(code: String, signal: String, reasons: List<String>) = Result(
        code = code,
        score = null,
        signal = signal,
        tier = "none",
        netProxyYuan = null,
        fsiPct = null,
        fsiAbs = null,
        pctMain = null,
        m5Ratio = null,
        scoreDelta = null,
        liquidityWeight = 0.0,
        volRatioFactor = 1.0,
        confidence = 0.0,
        reasons = reasons,
    )

    private fun mapOfNotNull(code: String, value: CloudFlow?): Map<String, CloudFlow> =
        if (value == null) emptyMap() else mapOf(code to value)

    /** 分位（0-100），用二分算「小于 + 半个等于」，浮点按 1e-9 容差判等。 */
    internal fun percentileOf(sorted: DoubleArray, v: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val eps = 1e-9
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < v - eps) lo = mid + 1 else hi = mid
        }
        val first = lo
        var lo2 = first
        var hi2 = sorted.size
        while (lo2 < hi2) {
            val mid = (lo2 + hi2) / 2
            if (sorted[mid] <= v + eps) lo2 = mid + 1 else hi2 = mid
        }
        val equal = lo2 - first
        return 100.0 * (first + 0.5 * equal) / sorted.size
    }

    private fun fmt(v: Double?): String = if (v == null) "null" else String.format("%.2f", v)
}
