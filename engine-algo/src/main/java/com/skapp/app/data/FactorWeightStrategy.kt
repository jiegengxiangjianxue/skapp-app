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
        /** 族级分解：看清「多少票是同一族重复投的」 */
        val families: List<FamilyScore> = emptyList(),
        /** 有方向的族数（最多 5）——比指标个数更能代表独立证据量 */
        val activeFamilies: Int = 0,
        /** 旧口径（逐指标加权，未去相关），保留用于对比与排查 */
        val rawBuyScore: Double = 0.0,
        val rawAvoidScore: Double = 0.0,
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

    // ── 指标族（去相关） ──────────────────────────────────────

    /**
     * 16 个指标按「测的是什么」分族。
     *
     * 为什么必须分：这 16 个指标高度共线——Donchian 位置、Keltner 位置、Pivot 位置本质上都在
     * 回答「价格站在区间哪一侧」。实测里经常出现「14 个偏多 / 0 个偏空」，看着像 14 份独立证据，
     * 其实只是 5 个族里的 2~3 个族在重复表态。按指标逐个加权会把同一件事数很多遍，
     * 于是分数虚高、区分度消失。
     *
     * 分族后族内先取净额（相互抵消的分歧不再各记一半），族间再按云端给的权重合成，
     * 相当于「每个族只有一票」。族的划分按指标定义，不按相关性数据拟合——
     * 后者需要全市场因子矩阵，端上不做。
     */
    enum class Family(val label: String, val members: List<String>) {
        CHANNEL("通道位置", listOf("Donchian20", "BOLL/Keltner", "Pivot")),
        MOMENTUM("动量", listOf("UltimateOsc", "CMO", "RSI", "MACD")),
        TREND("趋势", listOf("SuperTrend", "MA5/MA20", "HullMA", "VWMA", "Vortex")),
        VOLUME("量能", listOf("A/D Line", "ElderForce")),
        STRUCTURE("波动结构", listOf("Choppiness", "KDJ(RSV)")),
    }

    /** 一个族的净表态 */
    data class FamilyScore(
        val family: Family,
        /** 族内加权净方向，-1..1 */
        val net: Double,
        /** 该族内所有指标的云端 rel_weight 之和（仅用于展示与排序，**不参与合成**） */
        val weightShare: Double,
        /** 族内有方向的指标数 / 总指标数 */
        val participate: Int,
        val total: Int,
        /** 族内分歧度：0 = 全部同向，1 = 多空对半 */
        val dispersion: Double,
    ) {
        val dir: Int get() = if (net > 0.05) 1 else if (net < -0.05) -1 else 0
    }

    private val familyOf: Map<String, Family> =
        Family.entries.flatMap { f -> f.members.map { it to f } }.toMap()

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

        // 旧口径：逐指标独立加权（同一族内多个指标会重复计数）
        val rawBuy = votes.filter { it.signal > 0 }.sumOf { it.relWeight * it.strength } / totalWeight
        val rawAvoid = votes.filter { it.signal < 0 }.sumOf { it.relWeight * it.strength } / totalWeight

        // 新口径：族内先抵消，族间每族一票（见 Family 的注释与 aggregate() 的说明）
        val families = buildFamilies(votes)
        val agg = aggregate(families)
        val buyScore = agg.buy
        val avoidScore = agg.avoid
        val net = buyScore - avoidScore
        val activeFamilies = families.count { it.dir != 0 }

        val bull = votes.count { it.signal > 0 }
        val bear = votes.count { it.signal < 0 }
        val bullFamilies = families.count { it.dir > 0 }
        val bearFamilies = families.count { it.dir < 0 }

        // 否决制：只看空侧有多强，多侧不构成推荐。
        // 阈值取「1/3 权重」与「半数指标」两个独立口径，任一满足即回避——
        // 单指标翻脸不算数（云端空头样本仅 3.4%，单指标不可靠）。
        val verdict = when {
            // 否决要求「多个族同向看空」：单族（比如全是趋势指标）翻脸不算数，
            // 云端空头样本仅 3.4%，单族误判的代价太高。
            avoidScore >= 0.30 && bearFamilies >= 2 -> Verdict.AVOID
            avoidScore >= 0.20 && bearFamilies >= 3 -> Verdict.AVOID
            avoidScore >= 0.12 || bearFamilies >= 2 -> Verdict.WATCH
            else -> Verdict.PASS
        }

        // 理由按族给：先说「哪几族在表态」，再点族内最有代表性的指标，
        // 避免 5 个同族指标并列成 5 条看似独立的证据
        val reasons = families.filter { it.dir != 0 }
            .sortedByDescending { abs(it.net) * it.weightShare }
            .take(3)
            .map { f ->
                val top = votes.filter { familyOf[it.name] == f.family && it.signal != 0 }
                    .maxByOrNull { it.relWeight * it.strength }
                val dirTxt = if (f.dir > 0) "偏多" else "偏空"
                val rep = top?.let { "（代表：${it.name} ${it.value}）" } ?: ""
                val dispTxt = if (f.dispersion > 0.3) "，族内分歧 ${(f.dispersion * 100).toInt()}%" else ""
                "$dirTxt·${f.family.label}族$rep$dispTxt"
            }

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
            families = families,
            activeFamilies = activeFamilies,
            rawBuyScore = round3(rawBuy),
            rawAvoidScore = round3(rawAvoid),
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

    /** 把逐指标投票折叠成族级表态（族内加权、分歧可见） */
    internal fun buildFamilies(votes: List<Vote>): List<FamilyScore> =
        Family.entries.mapNotNull { fam ->
            val vs = votes.filter { familyOf[it.name] == fam }
            if (vs.isEmpty()) return@mapNotNull null
            val famW = vs.sumOf { it.relWeight }
            val signed = vs.sumOf { it.relWeight * it.signal * it.strength }
            val bulls = vs.count { it.signal > 0 }
            val bears = vs.count { it.signal < 0 }
            val voted = bulls + bears
            FamilyScore(
                family = fam,
                net = if (famW > 0) (signed / famW).coerceIn(-1.0, 1.0) else 0.0,
                weightShare = famW,
                participate = voted,
                total = vs.size,
                dispersion = if (voted > 0) min(bulls, bears).toDouble() / voted else 0.0,
            )
        }

    data class Aggregate(val buy: Double, val avoid: Double)

    /**
     * 族级合成。**每族一票，族间等权**，并且按族内分歧打折。
     *
     * 这里踩过一个坑值得记下来：最初写成「族内加权平均 × 族权重（= 族内权重和 / 总和）」，
     * 数学上正好等于逐指标加权求和——两级平均是恒等变换，等于什么都没改，
     * 实测四只票的分数与改之前一模一样，就是这个原因。
     *
     * 之所以族间取等权：云端的 rel_weight 是**指标层**标定，没有族层标定。
     * 若拿「族内权重之和」当族权重，趋势族（5 个指标）天然比量能族（2 个）话语权大，
     * 而这恰恰是要消掉的偏差——趋势族那 5 个指标里，有 4 个都在说「价格在均线上方」。
     *
     * dispersion 惩罚：族内 3 多 2 空时，这一族不该既算 3 份多、又算 2 份空（原口径两边都计），
     * 也不该按净额全票通过。按 (1 - dispersion) 打折，等于「族内越吵架，这票越轻」。
     */
    internal fun aggregate(families: List<FamilyScore>): Aggregate {
        val n = families.size
        if (n == 0) return Aggregate(0.0, 0.0)
        val per = 1.0 / n
        var buy = 0.0
        var avoid = 0.0
        for (f in families) {
            val eff = per * (1.0 - f.dispersion)
            if (f.net > 0) buy += f.net * eff
            else if (f.net < 0) avoid += -f.net * eff
        }
        return Aggregate(round3(buy), round3(avoid))
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
            // 用指标库的标准逐日实现。早期这里另写了一个「本地近似版」：拿最后一根的
            // hl2 ± 3·ATR 判方向，价格几乎不可能越出 ±3 ATR，于是绝大多数票落到 else 分支
            // 去看「period 日净变化」——SuperTrend 被退化成了又一个动量因子（与 CMO/VWMA 同轴）。
            "SuperTrend" -> {
                val (dir, _) = ExtendedIndicators.superTrend(bars, 10, 3.0)
                if (dir == null) return null
                val atr = ExtendedIndicators.atr(bars, 10)
                v(
                    if (dir > 0) 1 else -1, 0.6,
                    if (dir > 0) "多头" else "空头",
                    atr?.let { "ATR %.2f".format(it) } ?: "ATR 不可得",
                )
            }

            "ElderForce" -> {
                val ef = ExtendedIndicators.elderForce(bars, 13) ?: return null
                // 强度分母必须与 EF 同量纲：EF 是 (收盘变动 × 成交量) 的 EMA，
                // 旧写法拿「收盘价 × 成交量」当尺度，差一整个价格量级，
                // 于是 |EF|/scale 恒在 0.001 量级——这个因子在族内几乎不贡献强度。
                val scale = bars.takeLast(14)
                    .zipWithNext { a, b -> abs(b.close - a.close) * b.vol }
                    .average() + 1e-9
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

    // ── 小工具 ──────────────────────────────────────────────

    private fun straddle(v: Double) = v.coerceIn(0.0, 1.0)

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    private fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }
}
