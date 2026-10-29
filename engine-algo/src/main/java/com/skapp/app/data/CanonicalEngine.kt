package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 单一引擎实现（云端 `engine_v5.build_votes` / `score_stock` 的逐票移植）。
 *
 * ## 定位
 *
 * 这是「两端同一套算法」的端上那一半：21 票、加权、共振系数、0~100 映射全部按
 * [EngineSpec] 给出的参数计算，输出与云端 `engine_v5.json` 的 `votes / score / resonance`
 * 可以逐字段对比。任何公式差异都会让 `CanonicalEngineParityTest` 变红——这是唯一判据。
 *
 * ## 与旧 [LocalEngine] 的关系
 *
 * [LocalEngine] 的 12 引擎是另一套口径（每引擎直接给 0~100 分再平均），保留它是因为
 * 界面与筛股链路仍在用；但「引擎评分」的权威口径从这里出。旧实现的族级共振思路
 * （族内先抵消再计方向）在这里由 spec 的 `family` 字段承载，不再另算一套。
 *
 * ## 不编造
 *
 * 输入不足时对应的票**不产生**（`missing_vote_policy=drop`），而不是按 0 分参与平均——
 * 后者会把「算不出来」伪装成「中性」。
 */
object CanonicalEngine {

    /** 单票结果。`strength` 已按规格四舍五入（与云端输出同精度）。 */
    data class Vote(val indicator: String, val vote: Int, val strength: Double, val why: String)

    data class Result(
        val code: String,
        val score: Double,
        val raw: Double,
        val resonance: Double,
        val bull: Int,
        val bear: Int,
        val neutral: Int,
        val votes: List<Vote>,
        val signal: String,
        val specVersion: String,
        val missing: List<String> = emptyList(),
    ) {
        val directional: Int get() = bull + bear
    }

    /** 技术面 17 票 + 位置票；外部票（板块/资金/涨停池）由 [externalVotes] 注入。 */
    fun score(
        code: String,
        bars: List<KBar>,
        spec: EngineSpec = EngineSpecLoader.loadBundled().spec,
        weights: Map<String, Double>? = null,
        externalVotes: List<Vote> = emptyList(),
    ): Result? {
        if (bars.size < spec.runtime.minBars) return null
        val close = bars.map { it.close }
        val high = bars.map { it.high }
        val low = bars.map { it.low }
        val vol = bars.map { it.vol }
        val votes = buildVotes(code, close, high, low, vol, spec).toMutableList()
        votes += externalVotes
        if (votes.isEmpty()) return null
        val agg = spec.aggregation
        val w = weights ?: spec.weights
        var num = 0.0
        var den = 0.0
        var bull = 0
        var bear = 0
        for (v in votes) {
            val weight = w[v.indicator] ?: 1.0
            num += weight * v.vote * v.strength
            den += weight
            if (v.vote > 0) bull++ else if (v.vote < 0) bear++
        }
        val directional = bull + bear
        val resonance = if (directional > 0) max(bull, bear).toDouble() / directional else 0.0
        val base = if (den != 0.0) num / den else 0.0
        val score = CanonicalIndicators.pyRound(agg.score(base, resonance), agg.roundScore)
        return Result(
            code = code,
            score = score,
            raw = CanonicalIndicators.pyRound(base, 4),
            resonance = CanonicalIndicators.pyRound(resonance, agg.roundResonance),
            bull = bull,
            bear = bear,
            neutral = votes.size - directional,
            votes = votes,
            signal = agg.signalOf(score),
            specVersion = spec.specVersion,
        )
    }

    /**
     * 21 票构建。只依赖 OHLCV，纯函数——与云端 `build_votes` 一一对应，
     * 名字与顺序都刻意保持相同，方便逐票 diff。
     */
    fun buildVotes(
        codeBare: String,
        close: List<Double>,
        high: List<Double>,
        low: List<Double>,
        vol: List<Double>,
        spec: EngineSpec,
    ): List<Vote> {
        val votes = mutableListOf<Vote>()
        val agg = spec.aggregation

        fun add(indicator: String, vote: Int, strength: Double, why: String) {
            votes += Vote(
                indicator = indicator,
                vote = vote,
                strength = CanonicalIndicators.pyRound(strength.coerceIn(0.0, 1.0), agg.roundStrength),
                why = why,
            )
        }

        // 1. SuperTrend
        run {
            val (st, tr) = CanonicalIndicators.superTrend(
                high, low, close,
                spec.intParam("supertrend", "n", 10),
                spec.param("supertrend", "multiplier", 3.0),
            )
            val t = tr.lastOrNull()
            if (t != null) {
                val strength = if (t != 0) spec.param("supertrend", "strength", 0.9) else 0.0
                val stv = st.lastOrNull()
                add("SuperTrend", t, strength,
                    if (stv != null) "趋势${if (t > 0) "多头" else "空头"}, 线值 ${"%.2f".format(stv)}" else "未形成")
            }
        }

        // 2. MA 金叉（死区 deadband_pct）
        run {
            val fast = spec.intParam("ma_golden", "fast", 5)
            val slow = spec.intParam("ma_golden", "slow", 20)
            val maFast = CanonicalIndicators.sma(close, fast).lastOrNull()
            val maSlow = CanonicalIndicators.sma(close, slow).lastOrNull()
            if (maFast != null && maSlow != null && maFast != 0.0 && maSlow != 0.0) {
                val dev = (maFast - maSlow) / maSlow * 100
                val deadband = spec.param("ma_golden", "deadband_pct", 0.3)
                val v = when {
                    dev > deadband -> 1
                    dev < -deadband -> -1
                    else -> 0
                }
                val scale = spec.param("ma_golden", "strength_scale_pct", 3.0)
                add("MA5/MA20", v, min(abs(dev) / scale, 1.0),
                    "MA$fast=${"%.2f".format(maFast)} MA$slow=${"%.2f".format(maSlow)} 偏离${"%+.2f".format(dev)}%")
            }
        }

        // 3. RSI
        run {
            val period = spec.intParam("rsi", "period", 14)
            val r = CanonicalIndicators.rsi(close, period).lastOrNull()
            if (r != null && r != 0.0) {
                val oversold = spec.param("rsi", "oversold", 30.0)
                val overbought = spec.param("rsi", "overbought", 70.0)
                val strongLo = spec.param("rsi", "strong_lo", 45.0)
                val strongHi = spec.param("rsi", "strong_hi", 60.0)
                when {
                    r < oversold -> add("RSI", 1, (oversold - r) / oversold, "RSI=$r 超卖, 反弹概率增")
                    r > overbought -> add("RSI", -1, (r - overbought) / 30, "RSI=$r 超买, 回踩风险")
                    r in strongLo..strongHi ->
                        add("RSI", 1, spec.param("rsi", "strong_strength", 0.4), "RSI=$r 强势区间")
                    else -> add("RSI", 0, spec.param("rsi", "neutral_strength", 0.2), "RSI=$r 中性")
                }
            }
        }

        // 4. MACD（比较柱体变化，不是柱体绝对值）
        run {
            val fast = spec.intParam("macd", "fast", 12)
            val slow = spec.intParam("macd", "slow", 26)
            val eFast = CanonicalIndicators.ema(close, fast)
            val eSlow = CanonicalIndicators.ema(close, slow)
            val le = eFast.lastOrNull()
            val leSlow = eSlow.lastOrNull()
            val pe = eFast.getOrNull(eFast.size - 2)
            val peSlow = eSlow.getOrNull(eSlow.size - 2)
            if (le != null && leSlow != null && pe != null && peSlow != null) {
                val hist = le - leSlow
                val prevHist = pe - peSlow
                val v = if (hist > 0) 1 else -1
                val s = min(abs(hist - prevHist) / max(abs(prevHist), 0.01), 1.0)
                add("MACD", v, max(s, spec.param("macd", "strength_floor", 0.3)),
                    "DIF=${"%+.3f".format(hist)} 前值${"%+.3f".format(prevHist)} " +
                        if (hist > prevHist) "走强" else "走弱")
            }
        }

        // 5. KDJ（9 日 RSV 近似，与云端同口径：不用 K/D 平滑）
        run {
            val period = spec.intParam("kdj", "period", 9)
            if (close.size >= period) {
                val hn = high.takeLast(period).max()
                val ln = low.takeLast(period).min()
                val rsv = if (hn > ln) (close.last() - ln) / (hn - ln) * 100 else 50.0
                val mid = spec.param("kdj", "mid", 50.0)
                val v = if (rsv > mid) 1 else -1
                add("KDJ(RSV)", v, abs(rsv - mid) / mid, "RSV=${"%.1f".format(rsv)} (${period}日)")
            }
        }

        // 6. BOLL/Keltner（云端实际调用 keltner，以代码为准）
        run {
            val mid = spec.intParam("boll_keltner", "ema_n", 20)
            val atrN = spec.intParam("boll_keltner", "atr_n", 10)
            val k = spec.param("boll_keltner", "k", 2.0)
            val (_, up, lo) = CanonicalIndicators.keltner(high, low, close, mid, atrN, k)
            val lu = up.lastOrNull()
            val ll = lo.lastOrNull()
            val lm = CanonicalIndicators.ema(close, mid).lastOrNull()
            if (lu != null && ll != null && lm != null && lu > ll) {
                val pos = (close.last() - ll) / (lu - ll)
                val upper = spec.param("boll_keltner", "upper", 0.7)
                val lower = spec.param("boll_keltner", "lower", 0.3)
                val v = when {
                    pos > upper -> 1
                    pos < lower -> -1
                    else -> 0
                }
                add("BOLL/Keltner", v, abs(pos - 0.5) * 2,
                    "带内位置 ${"%.0f".format(pos * 100)}% (中轨${"%.2f".format(lm)})")
            }
        }

        // 7. Donchian（追突破为负期望，只给负票）
        run {
            val n = spec.intParam("donchian", "n", 20)
            val (up, _, _) = CanonicalIndicators.donchian(high, low, n)
            val lu = up.lastOrNull()
            if (lu != null && lu != 0.0) {
                val dev = (close.last() - lu) / lu * 100
                val v = if (dev > spec.param("donchian", "near_high_pct", -1.0)) -1 else 0
                val strength = min(abs(dev) / spec.param("donchian", "strength_scale_pct", 8.0), 1.0) *
                    spec.param("donchian", "strength_cap", 0.5)
                add("Donchian20", v, strength,
                    "距${n}日高 ${"%+.2f".format(dev)}% (追突破无正edge, 不给正票)")
            }
        }

        // 8. CMO
        run {
            val c = CanonicalIndicators.cmo(close, spec.intParam("cmo", "n", 14)).lastOrNull()
            if (c != null) {
                val th = spec.param("cmo", "threshold", 20.0)
                val v = when {
                    c > th -> 1
                    c < -th -> -1
                    else -> 0
                }
                add("CMO", v, min(abs(c) / spec.param("cmo", "strength_scale", 50.0), 1.0), "CMO=$c")
            }
        }

        // 9. Vortex
        run {
            val n = spec.intParam("vortex", "n", 14)
            val (vip, vim) = CanonicalIndicators.vortex(high, low, close, n)
            val lp = vip.lastOrNull()
            val lm = vim.lastOrNull()
            if (lp != null && lm != null && lp != 0.0 && lm != 0.0) {
                val v = if (lp > lm) 1 else -1
                add("Vortex", v, min(abs(lp - lm) * spec.param("vortex", "strength_scale", 5.0), 1.0),
                    "VI+=$lp VI-=$lm")
            }
        }

        // 10. Ultimate Oscillator
        run {
            val n1 = spec.intParam("ultimate_osc", "n1", 7)
            val n2 = spec.intParam("ultimate_osc", "n2", 14)
            val n3 = spec.intParam("ultimate_osc", "n3", 28)
            val uo = CanonicalIndicators.ultimateOsc(high, low, close, n1, n2, n3).lastOrNull()
            if (uo != null && uo != 0.0) {
                val lo = spec.param("ultimate_osc", "lo", 30.0)
                val hi = spec.param("ultimate_osc", "hi", 70.0)
                val mid = spec.param("ultimate_osc", "mid", 50.0)
                val v = when {
                    (uo > lo && uo < hi) && uo > mid -> 1
                    uo >= hi -> -1
                    else -> 0
                }
                val strength = if (v != 0) spec.param("ultimate_osc", "strength_active", 0.4)
                else spec.param("ultimate_osc", "strength_neutral", 0.2)
                add("UltimateOsc", v, strength, "UO=$uo")
            }
        }

        // 11. Choppiness（震荡市闸门：无趋势不投方向票）
        run {
            val n = spec.intParam("choppiness", "n", 14)
            val ch = CanonicalIndicators.choppiness(high, low, close, n).lastOrNull()
            if (ch != null && ch != 0.0) {
                val trendBelow = spec.param("choppiness", "trend_below", 40.0)
                if (ch < trendBelow) {
                    val maN = spec.intParam("choppiness", "ma_n", 20)
                    val ma = CanonicalIndicators.sma(close, maN).lastOrNull()
                    val v = if (ma != null && close.last() > ma) 1 else -1
                    add("Choppiness", v, (50 - ch) / 50, "CHOP=$ch 趋势市, 顺势")
                } else {
                    add("Choppiness", 0, 0.1, "CHOP=$ch 震荡市, 不追趋势信号")
                }
            }
        }

        // 12. HullMA
        run {
            val n = spec.intParam("hull_ma", "n", 9)
            val hm = CanonicalIndicators.hullMa(close, n).lastOrNull()
            if (hm != null && hm != 0.0) {
                val v = if (close.last() > hm) 1 else -1
                val scale = spec.param("hull_ma", "strength_scale_pct", 2.0)
                add("HullMA", v, min(abs(close.last() - hm) / hm * 100 / scale, 1.0),
                    "HMA=${"%.2f".format(hm)} 价格${if (close.last() > hm) "上方" else "下方"}")
            }
        }

        // 13. Elder Force
        run {
            val n = spec.intParam("elder_force", "n", 13)
            val ef = CanonicalIndicators.elderForce(close, vol, n).lastOrNull()
            if (ef != null) {
                val v = if (ef > 0) 1 else -1
                val scale = spec.param("elder_force", "strength_scale", 100_000.0)
                add("ElderForce", v, min(abs(ef) / (abs(ef) + scale), 1.0), "EF=${"%.0f".format(ef)}")
            }
        }

        // 14. Connors RSI
        run {
            val crsi = CanonicalIndicators.connorsRsi(
                close,
                spec.intParam("connors_rsi", "rsi_n", 3),
                spec.intParam("connors_rsi", "streak_n", 2),
                spec.intParam("connors_rsi", "rank_n", 100),
            ).lastOrNull()
            if (crsi != null) {
                val lo = spec.param("connors_rsi", "lo", 20.0)
                val hi = spec.param("connors_rsi", "hi", 80.0)
                when {
                    crsi < lo -> add("ConnorsRSI", 1, (lo - crsi) / lo, "CRSI=$crsi 极度超卖")
                    crsi > hi -> add("ConnorsRSI", -1, (crsi - hi) / 20, "CRSI=$crsi 极度超买")
                    else -> add("ConnorsRSI", 0, spec.param("connors_rsi", "neutral_strength", 0.2),
                        "CRSI=$crsi 中性")
                }
            }
        }

        // 15. A/D Line
        run {
            val lookback = spec.intParam("ad_line", "lookback", 10)
            val ad = CanonicalIndicators.adLine(high, low, close, vol)
            val cur = ad.lastOrNull()
            val prev = ad.getOrNull(ad.size - 1 - lookback)
            if (ad.size >= lookback + 1 && cur != null && prev != null) {
                val d = cur - prev
                val v = if (d > 0) 1 else -1
                add("A/D Line", v, min(abs(d) / (abs(cur) + 1), 1.0),
                    "${lookback}日${if (d > 0) "累积" else "派发"} ${"%+.0f".format(d)}")
            }
        }

        // 16. Pivot
        run {
            if (close.isNotEmpty()) {
                val p = CanonicalIndicators.pivotPoints(high, low, close).last()
                val c = close.last()
                val v = if (c > p.pp) 1 else -1
                val why = if (c > p.pp) "价格在枢轴 ${p.pp} 上方, 支撑 ${p.s1}"
                else "价格在枢轴 ${p.pp} 下方, 压力 ${p.r1}"
                add("Pivot", v, spec.param("pivot", "strength", 0.4), why)
            }
        }

        // 17. VWMA
        run {
            val n = spec.intParam("vwma", "n", 20)
            val vw = CanonicalIndicators.vwma(close, vol, n).lastOrNull()
            if (vw != null && vw != 0.0) {
                val v = if (close.last() > vw) 1 else -1
                val scale = spec.param("vwma", "strength_scale_pct", 2.0)
                add("VWMA", v, min(abs(close.last() - vw) / vw * 100 / scale, 1.0),
                    "VWMA=${"%.2f".format(vw)} (量均价)")
            }
        }

        // 21. below_ma60 + momentum_overheat（唯一有实测正 edge 的技术特征）
        run {
            val minBars = spec.intParam("below_ma60", "min_bars", 80)
            if (close.size >= minBars) {
                val maN = spec.intParam("below_ma60", "ma_n", 60)
                val ma60 = close.takeLast(maN).sum() / maN
                if (ma60 > 0) {
                    val dev60 = (close.last() / ma60 - 1) * 100
                    val mx20 = high.takeLast(20).max()
                    val ret20 = if (close.size >= 21 && close[close.size - 21] > 0)
                        (close[close.size - 21] / close.last() - 1) * -100 else 0.0
                    val nearHigh = mx20 > 0 && close.last() >= mx20 * spec.param("below_ma60", "near_high_pct", 0.98)
                    val coreLo = spec.param("below_ma60", "core_dev_lo", -10.0)
                    val coreHi = spec.param("below_ma60", "core_dev_hi", -5.0)
                    val retCap = spec.param("below_ma60", "ret20_cap", 15.0)
                    val deepDev = spec.param("below_ma60", "deep_dev_pct", -25.0)
                    val overheat = spec.param("below_ma60", "overheat_dev_pct", 20.0)
                    when {
                        dev60 >= coreLo && dev60 < coreHi && !nearHigh && ret20 <= retCap ->
                            add("below_ma60", 1, spec.param("below_ma60", "strength_core", 0.9),
                                "距${maN}日线 ${"%+.1f".format(dev60)}% (回测正edge区), 20日${"%+.1f".format(ret20)}%")
                        close.last() < ma60 * 0.95 && dev60 < deepDev ->
                            add("below_ma60", -1, spec.param("below_ma60", "strength_deep", 0.5),
                                "距${maN}日线 ${"%+.1f".format(dev60)}% 跌深, 反弹不确定")
                        dev60 > overheat ->
                            add("momentum_overheat", -1, min(dev60 / 100, 0.8),
                                "距${maN}日线 ${"%+.1f".format(dev60)}% 过热, 回测追突破为负edge")
                    }
                }
            }
        }
        return votes
    }

    /**
     * 外部票（板块共振 / 主力资金 / 涨停池）——端上只有近似口径。
     * 与云端同名同权重，但 `why` 里必须写明是代理口径，界面不得当成真资金流展示。
     */
    fun externalVote(indicator: String, vote: Int, strength: Double, why: String, spec: EngineSpec): Vote =
        Vote(indicator, vote, CanonicalIndicators.pyRound(strength.coerceIn(0.0, 1.0), spec.aggregation.roundStrength), why)
}
