package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/**
 * 因子加权评分：**云端标定权重，端上算指标**。
 *
 * 为什么要有这一层：App 原来的择股是本地拍阈值（Composite 分数、五维加权），权重是写死的。
 * 云端 weight_calibrator_v3 拿 5~6 万样本、16 个技术指标、4 个持有期做了分层校准，给出每个指标
 * 的 `rel_weight`（相对权重）和 `edge`（看多胜率相对基准的偏离）。这份标定是「数据算出来的」，
 * 比手写权重有依据，但它躺在 weight_calibration.json 里，App 之前从没用过。
 *
 * 关键读法（照抄云端结论，不自行发挥）：
 *   - 16 个指标的 edge 全部为负（-5 ~ -7pp），bull_win_rate 全在 43% 上下 → **单靠这些指标的
 *     「看多」信号跑不赢基准**。云端 market_advice 因此是 `avoid_first`（回避优先）。
 *   - 所以这里不用它来「追多」，而是双通道打分：buyScore（占比小）与 avoidScore（占比大时否决）。
 *     只有当 avoid 侧干净、且 netScore 明显为正时才给 BUY。
 *   - rel_weight 差距很大（Donchian20 0.5 vs RSI 0.1），直接用权重加权，不做等权平均。
 *   - bear 样本仅占 3.4%（云端 regime_balance），所以「空头侧结论」置信区间宽，这里不给它
 *     过高的决策权重——avoid 判定要求同时满足多个空信号，而不是单指标翻脸。
 *
 * 用到的指标全部是 ExtendedIndicators 里已有的实现（或本地补的 MACD/RSI/KDJ/SuperTrend/AD 斜率），
 * 口径与云端因子库一致：都是日线、标准参数、无未来函数。
 */
object FactorWeightStrategy {

    /** 云端标定出的一个指标 */
    data class Spec(
        val name: String,
        val relWeight: Double,
        val edge: Double?,
        val bullWin: Double?,
        val bearWin: Double?,
        val n: Int?,
    )

    /** 云端标定整体（含市况与基准胜率） */
    data class Calibration(
        val engine: String,
        val horizon: Int,
        val specs: List<Spec>,
        /** 该持有期下「看多」的基准胜率（%） */
        val bullBaseRate: Double?,
        /** 该持有期下「看空」的基准胜率（%） */
        val bearBaseRate: Double?,
        val marketAdvice: String,
        val bearSharePct: Double?,
        val findings: List<String>,
    )

    /** 单个指标的投票 */
    data class Vote(
        val name: String,
        val relWeight: Double,
        /** +1 偏多 / -1 偏空 / 0 不表态 */
        val signal: Int,
        /** 0..1，信号强度 */
        val strength: Double,
        /** 指标读数，便于在界面上核对 */
        val value: String,
        val why: String,
    )

    /**
     * 三档按「否决强度」定义，不是按「推荐强度」。
     *
     * 云端实测 16 个指标的看多胜率只有 43~45%（低于 50% 基准），所以「指标全偏多」并不等于
     * 「该买」——那恰恰是云端给出 avoid_first 的原因。这套打分的正确用法是**筛掉不该碰的**：
     * PASS 只代表「本模块没有否决它」，不代表看涨；AVOID 才是有信息量的结论。
     */
    enum class Verdict(val label: String) {
        PASS("无否决"),
        WATCH("存疑"),
        AVOID("回避"),
    }

    data class Score(
        val code: String,
        val buyScore: Double,
        val avoidScore: Double,
        val netScore: Double,
        val verdict: Verdict,
        val votes: List<Vote>,
        val bullCount: Int,
        val bearCount: Int,
        val neutralCount: Int,
        val reasons: List<String>,
        /** 云端该持有期「看多」基准胜率（%）——用来提醒：偏多不等于胜率高 */
        val bullBaseRate: Double? = null,
        val bearBaseRate: Double? = null,
        /** 给界面的一句话提醒，说明本模块的口径边界 */
        val caveat: String = "",
    )

    // ── 云端标定读入 ──────────────────────────────────────────

    /**
     * 从 weight_calibration.json 读出标定。优先 5 日持有期（App 的默认持有周期）。
     */
    fun parseCalibration(json: JSONObject?, preferredHorizon: Int = 5): Calibration? {
        if (json == null) return null
        val horizons = json.optJSONObject("horizons") ?: return null
        val horizon = preferredHorizon.toString().let { h ->
            horizons.optJSONObject(h) ?: horizons.optJSONObject("1") ?: return null
        }
        val indicators = json.optJSONArray("indicators") ?: return null
        val specs = ArrayList<Spec>(indicators.length())
        for (i in 0 until indicators.length()) {
            val o = indicators.optJSONObject(i) ?: continue
            val name = o.optString("indicator").takeIf { it.isNotBlank() } ?: continue
            specs.add(
                Spec(
                    name = name,
                    relWeight = o.optDouble("rel_weight", 0.0),
                    edge = num(o, "edge"),
                    bullWin = num(o, "bull_win_rate"),
                    bearWin = num(o, "bear_win_rate"),
                    n = o.optInt("n", 0).takeIf { it > 0 },
                )
            )
        }
        if (specs.isEmpty()) return null
        val regime = json.optJSONObject("regime_balance")
        val findings = json.optJSONArray("findings")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        return Calibration(
            engine = json.optString("engine").ifBlank { "weight_calibration" },
            horizon = horizon.optInt("horizon_days", preferredHorizon),
            specs = specs,
            bullBaseRate = num(horizon, "bull_win_rate"),
            bearBaseRate = num(horizon, "bear_win_rate"),
            marketAdvice = json.optString("market_advice").ifBlank { "" },
            bearSharePct = regime?.let { num(it, "bear_share_pct") },
            findings = findings,
        )
    }

    // ── 打分 ────────────────────────────────────────────────

    /**
     * 对一只票的全部持仓周期打分。bars 需按时间升序（与 KlineApi 一致），至少 60 根。
     *
     * 判定分三步，不做加权求和的黑箱：
     *   1. 每个指标出 vote（方向 + 强度 + 可读读数）；
     *   2. buy/avoid 两路分别加权汇总（分母是全部正权重之和，不做归一造好看）；
     *   3. 用「回避优先」的规则下结论——这是云端 market_advice 的直接落地。
     */
    fun score(code: String, bars: List<KBar>, calib: Calibration): Score? {
        if (bars.size < 60) return null
        val closes = bars.map { it.close }
        val totalWeight = calib.specs.sumOf { it.relWeight }.takeIf { it > 0 } ?: return null

        val votes = calib.specs.mapNotNull { spec -> evaluate(spec, bars, closes) }
        if (votes.isEmpty()) return null

        val buyScore = votes.filter { it.signal > 0 }.sumOf { it.relWeight * it.strength } / totalWeight
        val avoidScore = votes.filter { it.signal < 0 }.sumOf { it.relWeight * it.strength } / totalWeight
        val net = buyScore - avoidScore

        val bull = votes.count { it.signal > 0 }
        val bear = votes.count { it.signal < 0 }

        // 否决制：只看空侧有多强，多侧不构成推荐。
        // 阈值取「1/3 权重」与「半数指标」两个独立口径，任一满足即回避——
        // 单指标翻脸不算数（云端空头样本仅 3.4%，单指标不可靠）。
        val verdict = when {
            avoidScore >= 0.33 || bear >= 8 -> Verdict.AVOID
            avoidScore >= 0.15 || bear >= 4 -> Verdict.WATCH
            else -> Verdict.PASS
        }

        // 只挑权重最高的几条讲清楚理由，避免界面变成指标dump
        val reasons = votes.sortedByDescending { it.relWeight * it.strength }
            .filter { it.signal != 0 }
            .take(3)
            .map { "${if (it.signal > 0) "偏多" else "偏空"}·${it.name}（${it.value}）${it.why}" }

        return Score(
            code = code,
            buyScore = round3(buyScore),
            avoidScore = round3(avoidScore),
            netScore = round3(net),
            verdict = verdict,
            votes = votes,
            bullCount = bull,
            bearCount = bear,
            neutralCount = votes.size - bull - bear,
            reasons = reasons,
            bullBaseRate = calib.bullBaseRate,
            bearBaseRate = calib.bearBaseRate,
            caveat = buildCaveat(calib),
        )
    }

    /**
     * 口径提醒：把云端「看多基准胜率不足 50%」这件事直接写在结果里，
     * 免得界面上把 PASS 读成「看涨」。
     */
    private fun buildCaveat(calib: Calibration): String {
        val bull = calib.bullBaseRate
        val bear = calib.bearBaseRate
        return if (bull != null && bull < 50.0) {
            "云端实测 ${calib.horizon} 日看多胜率 ${"%.1f".format(bull)}%、看空 ${bear?.let { "%.1f".format(it) } ?: "—"}%，" +
                "偏多本身不是正期望；本模块只做否决，PASS 不等于看涨"
        } else {
            "本模块只做否决：AVOID 有信息量，PASS 仅表示未被否掉"
        }
    }

    /** 单个指标 → 投票。每个分支都写清阈值依据，方便以后复核。 */
    private fun evaluate(spec: Spec, bars: List<KBar>, closes: List<Double>): Vote? {
        val close = closes.last()
        val w = spec.relWeight
        fun v(sig: Int, strength: Double, value: String, why: String) =
            Vote(spec.name, w, sig, straddle(strength), value, why)

        return when (spec.name) {
            // 唐奇安通道位置：贴近 20 日高/低点，是趋势延续的最直接读数（云端 rel_weight 最高 0.5）
            "Donchian20" -> {
                val (up, low) = ExtendedIndicators.donchian(bars, 20)
                if (up == null || low == null || up - low <= 0) null
                else {
                    val pos = (close - low) / (up - low)
                    v(
                        if (pos >= 0.75) 1 else if (pos <= 0.25) -1 else 0,
                        abs(pos - 0.5) * 2,
                        "位置 %.0f%%".format(pos * 100),
                        if (pos >= 0.75) "贴 20 日高点" else if (pos <= 0.25) "贴 20 日低点" else "通道中部"
                    )
                }
            }

            "UltimateOsc" -> {
                val uo = ExtendedIndicators.ultimateOsc(bars) ?: return null
                v(
                    if (uo > 55) 1 else if (uo < 45) -1 else 0,
                    abs(uo - 50) / 50,
                    "%.1f".format(uo),
                    if (uo > 70) "超买区动量" else if (uo < 30) "超卖区动量" else "中性动量"
                )
            }

            // 震荡度本身没有方向，要和均线方向合成：先看是不是趋势，再看往哪边
            "Choppiness" -> {
                val chop = ExtendedIndicators.choppiness(bars) ?: return null
                val ma20 = ExtendedIndicators.sma(closes, 20).lastOrNull() ?: return null
                when {
                    chop < 38 -> v(
                        if (close > ma20) 1 else -1, (38 - chop) / 38,
                        "%.1f".format(chop), "趋势清晰（${"%.0f".format(chop)}<38）"
                    )
                    chop > 61 -> v(0, 0.0, "%.1f".format(chop), "震荡无方向（>61）")
                    else -> v(0, 0.0, "%.1f".format(chop), "混合区")
                }
            }

            "CMO" -> {
                val c = ExtendedIndicators.cmo(bars) ?: return null
                v(
                    if (c > 50) 1 else if (c < -50) -1 else 0,
                    abs(c) / 100,
                    "%.1f".format(c),
                    if (c > 50) "动量强" else if (c < -50) "动量弱" else "动量中性"
                )
            }

            // Keltner 用 ATR 通道，比 BOLL 更抗跳空；位置读数与 Donchian 同构但阈值更松
            "BOLL/Keltner" -> {
                val (low, mid, up) = ExtendedIndicators.keltner(bars, 20, 2.0)
                if (low == null || up == null || mid == null || up - low <= 0) null
                else {
                    val pos = (close - low) / (up - low)
                    v(
                        if (pos >= 0.8) 1 else if (pos <= 0.2) -1 else 0,
                        abs(pos - 0.5) * 2,
                        "位置 %.0f%%".format(pos * 100),
                        if (pos >= 0.8) "上轨外" else if (pos <= 0.2) "下轨外" else "通道内"
                    )
                }
            }

            "KDJ(RSV)" -> {
                val r = rsv(bars, 9) ?: return null
                v(
                    if (r > 80) 1 else if (r < 20) -1 else 0,
                    abs(r - 50) / 50,
                    "RSV %.0f".format(r),
                    if (r > 80) "高位钝化" else if (r < 20) "低位钝化" else "中位"
                )
            }

            "VWMA" -> {
                val vw = ExtendedIndicators.vwma(bars, 20) ?: return null
                if (vw <= 0) return null
                val dev = (close - vw) / vw
                v(
                    if (dev > 0.01) 1 else if (dev < -0.01) -1 else 0,
                    min(abs(dev) / 0.05, 1.0),
                    "偏离 %+.1f%%".format(dev * 100),
                    if (dev > 0.01) "量价在均价线上" else if (dev < -0.01) "量价在均价线下" else "贴均价线"
                )
            }

            // A/D 线看的是「量的方向」和「价的方向」是否一致，背离比同向更有信息量
            "A/D Line" -> {
                val ad = adSeries(bars)
                if (ad.size < 21) null
                else {
                    val adSlope = ad.last() - ad[ad.size - 21]
                    val pxChg = (close - closes[closes.size - 21]) / closes[closes.size - 21]
                    val scale = ad.takeLast(21).sumOf { abs(it) } / 21 + 1e-9
                    val mag = min(abs(adSlope) / (scale * 5), 1.0)
                    when {
                        adSlope > 0 && pxChg > 0 -> v(1, mag, "AD↑价↑", "量价同向")
                        adSlope > 0 && pxChg <= 0 -> v(1, 0.7, "AD↑价↓", "底背离")
                        adSlope <= 0 && pxChg > 0 -> v(-1, 0.8, "AD↓价↑", "顶背离")
                        else -> v(-1, mag, "AD↓价↓", "量价同向走弱")
                    }
                }
            }

            "HullMA" -> {
                val hma = ExtendedIndicators.hullMa(closes, 16) ?: return null
                if (hma <= 0) return null
                val dev = (close - hma) / hma
                v(
                    if (dev > 0.01) 1 else if (dev < -0.01) -1 else 0,
                    min(abs(dev) / 0.05, 1.0),
                    "偏离 %+.1f%%".format(dev * 100),
                    if (dev > 0) "在 HMA 之上" else "在 HMA 之下"
                )
            }

            "Pivot" -> {
                val prev = bars.getOrNull(bars.size - 2) ?: return null
                val (p, _, _) = ExtendedIndicators.pivotPoints(prev)
                if (p <= 0) return null
                val dev = (close - p) / p
                v(
                    if (dev > 0.01) 1 else if (dev < -0.01) -1 else 0,
                    min(abs(dev) / 0.03, 1.0),
                    "偏离 %+.1f%%".format(dev * 100),
                    if (dev > 0) "站上枢轴" else "跌破枢轴"
                )
            }

            "MA5/MA20" -> {
                val m5 = ExtendedIndicators.sma(closes, 5).lastOrNull() ?: return null
                val m20 = ExtendedIndicators.sma(closes, 20).lastOrNull() ?: return null
                if (m20 <= 0) return null
                val gap = (m5 - m20) / m20
                v(
                    if (gap > 0) 1 else if (gap < 0) -1 else 0,
                    min(abs(gap) / 0.03, 1.0),
                    "差 %+.1f%%".format(gap * 100),
                    if (gap > 0) "短均在上" else "短均在下"
                )
            }

            // ExtendedIndicators.superTrend 的上/下轨写法与标准相反，这里用本地修正版
            "SuperTrend" -> {
                val trend = superTrendFixed(bars, 10, 3.0) ?: return null
                val atr = ExtendedIndicators.atr(bars, 10) ?: return null
                v(
                    trend, 0.6,
                    if (trend > 0) "多头" else "空头",
                    "ATR %.2f".format(atr)
                )
            }

            "ElderForce" -> {
                val ef = ExtendedIndicators.elderForce(bars, 13) ?: return null
                val scale = bars.takeLast(13).sumOf { abs(it.close) * it.vol } / 13 + 1e-9
                v(
                    if (ef > 0) 1 else if (ef < 0) -1 else 0,
                    min(abs(ef) / scale, 1.0),
                    "%.2e".format(ef),
                    if (ef > 0) "多头力量占优" else "空头力量占优"
                )
            }

            "Vortex" -> {
                val (vip, vim) = ExtendedIndicators.vortex(bars, 14)
                if (vip == null || vim == null) null
                else v(
                    if (vip > vim) 1 else -1,
                    min(abs(vip - vim) / 0.5, 1.0),
                    "VI+ %.2f / VI- %.2f".format(vip, vim),
                    if (vip > vim) "正涡旋在上" else "负涡旋在上"
                )
            }

            "MACD" -> {
                val (dif, dea) = macd(bars) ?: return null
                if (dif == null || dea == null) return null
                val gap = (dif - dea) / close
                v(
                    if (dif > dea) 1 else -1,
                    min(abs(gap) / 0.005, 1.0),
                    "DIF %+.3f".format(dif),
                    if (dif > dea) "DIF 在 DEA 上" else "DIF 在 DEA 下"
                )
            }

            // 云端实测 RSI 权重最低（0.1，看多胜率 42.9% 最差），这里保留但权重自然很低
            "RSI" -> {
                val r = rsi(closes, 14) ?: return null
                v(
                    if (r > 55) 1 else if (r < 45) -1 else 0,
                    abs(r - 50) / 50,
                    "%.0f".format(r),
                    if (r > 70) "超买" else if (r < 30) "超卖" else "中性"
                )
            }

            else -> null
        }
    }

    // ── 本地补的指标（ExtendedIndicators 里没有可靠的实现） ──────

    /** RSV：KD 的原始值，(C - LLV(L,9)) / (HHV(H,9) - LLV(L,9)) × 100 */
    private fun rsv(bars: List<KBar>, n: Int = 9): Double? {
        if (bars.size < n) return null
        val win = bars.takeLast(n)
        val hh = win.maxOf { it.high }
        val ll = win.minOf { it.low }
        if (hh - ll <= 0) return null
        return (bars.last().close - ll) / (hh - ll) * 100
    }

    /** Wilder RSI */
    private fun rsi(values: List<Double>, n: Int = 14): Double? {
        if (values.size <= n) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var ag = gain / n
        var al = loss / n
        for (i in n + 1 until values.size) {
            val d = values[i] - values[i - 1]
            ag = (ag * (n - 1) + max(d, 0.0)) / n
            al = (al * (n - 1) + max(-d, 0.0)) / n
        }
        if (ag + al <= 0) return 50.0
        return 100 - 100 / (1 + ag / al.coerceAtLeast(1e-9))
    }

    /** MACD(12,26,9) → (DIF, DEA) */
    private fun macd(bars: List<KBar>): Pair<Double?, Double?>? {
        val closes = bars.map { it.close }
        val e12 = ExtendedIndicators.ema(closes, 12)
        val e26 = ExtendedIndicators.ema(closes, 26)
        if (e12.size != closes.size || e26.size != closes.size) return null
        val dif = ArrayList<Double>(closes.size)
        for (i in closes.indices) {
            val a = e12[i]
            val b = e26[i]
            dif.add(if (a != null && b != null) a - b else Double.NaN)
        }
        val valid = dif.dropWhile { it.isNaN() }
        if (valid.size < 9) return null
        val dea = ExtendedIndicators.ema(valid, 9).lastOrNull()
        val lastDif = valid.lastOrNull()
        return if (lastDif != null && dea != null) lastDif to dea else null
    }

    /** A/D 累计线序列（ExtendedIndicators.adLine 只给终值，算不了斜率/背离） */
    private fun adSeries(bars: List<KBar>): List<Double> {
        var acc = 0.0
        return bars.map { b ->
            val range = b.high - b.low
            if (range > 0) acc += ((b.close - b.low) - (b.high - b.close)) / range * b.vol
            acc
        }
    }

    /**
     * 标准 SuperTrend：上轨 = HL2 + mult×ATR，下轨 = HL2 - mult×ATR，收盘穿越翻向。
     * 返回 1（多头）/ -1（空头）。
     */
    private fun superTrendFixed(bars: List<KBar>, period: Int, mult: Double): Int? {
        if (bars.size < period + 2) return null
        val atr = ExtendedIndicators.atr(bars, period) ?: return null
        // 用整段近似固定带宽（只判方向，不做逐日重算，够用且不会引入未来函数）
        val last = bars.last()
        val hl2 = (last.high + last.low) / 2
        val upper = hl2 + mult * atr
        val lower = hl2 - mult * atr
        return when {
            last.close > upper -> 1
            last.close < lower -> -1
            else -> {
                // 在带内：看最近 period 根的净变化方向
                val prev = bars[bars.size - 1 - period]
                if (last.close >= prev.close) 1 else -1
            }
        }
    }

    // ── 小工具 ──────────────────────────────────────────────

    private fun straddle(v: Double) = v.coerceIn(0.0, 1.0)

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    private fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }
}
