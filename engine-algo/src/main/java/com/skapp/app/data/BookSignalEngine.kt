package com.skapp.app.data

/**
 * 三层书籍信号引擎：反转（MTR）/ 涨停结构 / 订单流近似。
 *
 * 与云端 `stocks-ai-view/scripts/{reversal_engine,limit_structure,orderflow_proxy}.py`
 * 同源同判据，端上重算而不是读云端产物：端上本来就有 120 根日 K（`runtime.screen_bars`），
 * 三条算法都只需要 OHLCV，读产物反而会引入「云端没更新就没有信号」的空窗。
 *
 * ## 校准表是实测快照，不是书本先验
 *
 * 下面的 [CALIB] 来自云端实测：200 只 × 400 根日 K，5 日持有，cut_step=2。
 * 每条含毛收益 [Calib.fwd5]、t 值 [Calib.t]、样本数 [Calib.n]，以及扣 15bp 往返
 * 成本后的净收益 [Calib.netFwd5] 与 [Calib.netT]。
 *
 * **打分用净收益，不是毛收益**：A 股 5 日尺度的边缘优势普遍只有 0.2~1.8%，
 * 15bp 成本占比不小，毛正不等能赚。门槛（|netT| >= MIN_T）、权重（min(|netT|,4)/4）、
 * 层分（netFwd5）三处全走净口径，与云端 `signal_hub.layer_expectancy` 一致。
 * 表里保留毛收益与毛 t 只供对比诊断；毛过检、扣费后不显著的标签（如
 * `mtr:top_confirmed` 1.86→1.20）会留在表里备查，但**不参与打分**。
 *
 * ## 三条纪律（写进代码，别只写注释）
 *
 * 1. **只出多头证据，不出看空票**。三层判据（抄底反转 / 涨停动能 / 卖压反转）在本轮
 *    实测里没有一条显著为负，`limit:board2` −1.16%(t=−0.62)、`flow:delta_up`
 *    −0.04%(t=−0.38) 都过不了 t 检验。硬把它们塞成 −1 票就是拿「没证据」当「看空」，
 *    与 [EngineChain] 里「缺项不补 0」是同一个道理。
 * 2. **没有过检标签就不给分**（返回 null），不返回 50 假装评估过。
 * 3. **不可用项如实声明**（[knownLimits]）：封单量、首次封板时间、龙虎榜、筹码、
 *    真 Delta 全都拿不到，日线只能代理。别让调用方以为这些算过了。
 */
object BookSignalEngine {

    /** 三层都要足够历史，80 根与云端 MIN_BARS 一致。 */
    const val MIN_BARS = 80

    /** 三条信号各自的层 id，用于 reason 拼接与降级提示。 */
    const val LAYER_REVERSAL = "reversal"
    const val LAYER_LIMIT = "limit"
    const val LAYER_FLOW = "orderflow"

    /**
     * 校准快照。`label` 必须与云端校准表键名逐字一致，否则两端理由对不上。
     *
     * 维护方式：云端跑 `python3 scripts/reversal_engine.py --calibrate`（涨停层、订单流层同理）重跑后，把新表里的
     * 过检标签同步到这里。**同步门槛按净 t**（n ≥ 100 且 |netT| ≥ 1.5，与 [scoreOf] 一致），
     * 毛收益/毛 t 也一并抄过来留作对比；云端表里字段名为 `net_avg_fwd_15bp` / `net_t_15bp`
     * （反转/涨停/订单流三张），结构层那张是 `net_avg_fwd_5d_15bp` / `net_t_5d_15bp`。
     *
     * 注意：注释里不要写「斜杠+星号」开头的通配路径（Kotlin 支持嵌套块注释），
     * 否则注释会提前吃掉，报出莫名其妙的「Unclosed comment」与「Missing }」。
     */
    data class Calib(
        val label: String,
        /** 毛收益（未扣成本），保留供对比与诊断。 */
        val fwd5: Double,
        val t: Double,
        val n: Int,
        /** 净收益：扣 15bp 往返成本后。**打分用这个**，不是 fwd5。 */
        val netFwd5: Double = fwd5,
        /** 净收益的 t 值。**门槛与权重都用这个**：扣费后不显著的标签不得计分。 */
        val netT: Double = t,
    )

    private val CALIB: Map<String, Calib> = listOf(
        // ── 反转层：底部链顺向有效，顶部链反向 ──
        Calib("mtr:second_higher_low", 0.68, 3.85, 1229, 0.53, 3.00),
        Calib("mtr:bottom_confirmed", 0.67, 2.20, 438, 0.52, 1.71),
        Calib("mtr:extreme_bottom", 0.55, 6.10, 6075, 0.40, 4.42),
        Calib("mtr:pullback_down", 0.51, 6.60, 5320, 0.36, 4.65),
        Calib("mtr:break_up", 0.56, 6.61, 5555, 0.41, 4.85),
        // 毛 t=1.86 过检，但扣费后净 t 只剩 1.20 → 不达 MIN_T，留着备查、不计分
        Calib("mtr:top_confirmed", 0.42, 1.86, 725, 0.27, 1.20),
        Calib("mtr:break_down", 0.41, 4.60, 6795, 0.26, 2.90),
        Calib("mtr:pullback_up", 0.40, 4.41, 6591, 0.24, 2.74),
        Calib("mtr:extreme_top", 0.35, 4.07, 7137, 0.20, 2.33),
        // 同上：毛 t=1.86 → 净 t=0.86，扣费后不显著
        Calib("mtr:second_lower_high", 0.28, 1.86, 1944, 0.13, 0.86),
        // ── 涨停层：首板顺向，连板转负（后者过不了检验，不入表） ──
        Calib("limit:first_board & limit:active_5d", 1.77, 2.33, 333, 1.62, 2.13),
        Calib("limit:strong_seal & limit:active_5d", 1.55, 2.08, 387, 1.40, 1.88),
        Calib("limit:first_board", 1.40, 1.96, 366, 1.25, 1.75),
        Calib("limit:first_board & limit:strong_seal", 1.42, 1.96, 352, 1.27, 1.75),
        // ── 订单流近似：卖压与低位收盘是买入信号，买压无延续性 ──
        Calib("flow:imbalance_down", 1.97, 4.76, 374, 1.82, 4.40),
        Calib("flow:delta_down & flow:imbalance_down", 1.70, 3.75, 272, 1.55, 3.42),
        Calib("flow:delta_down", 0.68, 7.21, 4263, 0.53, 5.62),
    ).associateBy { it.label }

    /** 合成分映射常数：`exp5 = ±3%` 时到达 0/100，对应 spec 的 65/35 信号阈值。 */
    private const val CLAMP_PCT = 3.0
    private const val MIN_N = 100
    // 2026-10-30 复盘：1.5 与多重检验门槛确实有口径差（116 条表、Bonferroni 3.52，
    // 7/17 条不过线），但**不作就地收紧**：
    //   ① limit:* 4 条 t=1.96~2.33、n=333~387、edge 1.40~1.77%，是边缘但有经济意义的信号；
    //      Bonferroni 的独立性假设在这批高度互相关的 `A & B` 组合标签上不成立，硬卡 3.52
    //      会把 n=350 的首板组合一并误杀；
    //   ② 任何 >2.33 的取值都会让首板样本无分（其全部相关组合 t<=2.33），等于关掉首板展示。
    // 本次只把 book 权重置 0（不计票）；MIN_T 的分级校准留作独立议题，按 FDR 重做后再动。
    private const val MIN_T = 1.5

    data class Result(
        /** 0~100；null = 没有过检标签，不给分（不是 50）。 */
        val score: Double?,
        /** 加权 5 日期望收益（%）。 */
        val expected5d: Double?,
        /** 全部标签（三层合并，去重保序）。 */
        val tags: List<String>,
        /** 每层命中的标签，便于界面解释「这条信号来自哪本书」。 */
        val layers: Map<String, List<String>>,
        /** 命中的最佳标签键（|t| 最大）。 */
        val basis: String?,
        val basisT: Double?,
        val basisN: Int?,
        /** 缺席/降级说明。 */
        val degraded: List<String>,
        /** 一句话结论，可直接作为推股理由。 */
        val reason: String,
    ) {
        val hasEvidence: Boolean get() = score != null
    }

    // ─────────────────────────── 入口 ───────────────────────────

    fun compute(bars: List<KBar>, code: String = ""): Result {
        val degraded = ArrayList<String>()
        if (bars.size < MIN_BARS) {
            degraded += "K 线 ${bars.size} < $MIN_BARS"
            return Result(null, null, emptyList(), emptyMap(), null, null, null, degraded, "数据不足")
        }
        val reversal = runCatching { reversalTags(bars) }
            .getOrElse { degraded += "反转层异常：${it.message}"; emptyList() }
        val limit = runCatching { limitTags(bars, code) }
            .getOrElse { degraded += "涨停层异常：${it.message}"; emptyList() }
        val flow = runCatching { flowTags(bars) }
            .getOrElse { degraded += "订单流层异常：${it.message}"; emptyList() }

        val layers = linkedMapOf(
            LAYER_REVERSAL to reversal,
            LAYER_LIMIT to limit,
            LAYER_FLOW to flow,
        )
        val tags = (reversal + limit + flow).distinct()
        val scored = scoreOf(tags)

        val reason = when {
            scored.first == null -> "三层皆无过检信号（不给分）"
            else -> buildString {
                append("三层信号 ")
                append("%.2f%%/5日".format(scored.second))
                scored.third?.let { append("（${it.label} t_net=${"%.2f".format(it.netT)} n=${it.n}）") }
            }
        }

        return Result(
            score = scored.first,
            expected5d = scored.second,
            tags = tags,
            layers = layers,
            basis = scored.third?.label,
            basisT = scored.third?.netT,
            basisN = scored.third?.n,
            degraded = degraded,
            reason = reason,
        )
    }

    /**
     * 标签 → 合成分。组合键优先（单独一个标签的信息量明显低于组合）。
     *
     * 返回 (score, exp5, 最佳标签)；无过检标签时 score 为 null。
     */
    private fun scoreOf(tags: List<String>): Triple<Double?, Double?, Calib?> {
        val hits = ArrayList<Calib>()
        // 组合键：标签两两拼接，与云端 signal_hub.layer_expectancy 的取键方式一致
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                CALIB["${tags[i]} & ${tags[j]}"]?.let { hits += it }
                CALIB["${tags[j]} & ${tags[i]}"]?.let { hits += it }
            }
        }
        for (t in tags) CALIB[t]?.let { hits += it }
        val valid = hits.filter { it.n >= MIN_N && kotlin.math.abs(it.netT) >= MIN_T }
        if (valid.isEmpty()) return Triple(null, null, null)

        var num = 0.0
        var den = 0.0
        for (c in valid) {
            val w = kotlin.math.min(kotlin.math.abs(c.netT), 4.0) / 4.0
            num += c.netFwd5 * w
            den += w
        }
        val exp5 = if (den > 0) num / den else 0.0
        val score = 50.0 + 50.0 * (exp5 / CLAMP_PCT).coerceIn(-1.0, 1.0)
        val best = valid.maxByOrNull { kotlin.math.abs(it.netT) }
        return Triple(score, exp5, best)
    }

    // ─────────────────────── 涨停结构层（#15/#29/#31） ───────────────────────

    /** 涨停幅度阈值（留 0.2% 容差）。主板 10 / 创业板与科创 20 / 北交所 30。 */
    fun limitPct(code: String): Double {
        val c = code.lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")
        return when {
            c.startsWith("300") || c.startsWith("301") || c.startsWith("688") -> 19.8
            c.startsWith("8") || c.startsWith("4") || c.startsWith("920") -> 29.8
            else -> 9.8
        }
    }

    fun limitTags(bars: List<KBar>, code: String): List<String> {
        val n = bars.size
        if (n < MIN_BARS) return emptyList()
        val thr = limitPct(code)
        val cum = DoubleArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + bars[i].vol

        fun vma(i: Int): Double? = if (i + 1 < 20) null else (cum[i + 1] - cum[i + 1 - 20]) / 20.0

        var streak = 0
        var lastLimitIdx = -1
        var lastBoards = 0
        var lastStrong = false
        var lastVolRatio: Double? = null
        var brokenIdx = -1
        var brokenPrevStreak = 0

        for (i in 1 until n) {
            val prev = bars[i - 1].close
            if (prev <= 0.0) continue
            val chg = (bars[i].close / prev - 1.0) * 100.0
            if (chg >= thr) {
                streak++
                lastLimitIdx = i
                lastBoards = streak
                lastStrong = kotlin.math.abs(bars[i].close - bars[i].high) < 1e-6
                lastVolRatio = vma(i)?.takeIf { it > 0 }?.let { bars[i].vol / it }
            } else {
                if (streak > 0) {
                    brokenIdx = i
                    brokenPrevStreak = streak
                }
                streak = 0
            }
        }

        val tags = ArrayList<String>(4)
        if (lastLimitIdx == n - 1) {
            when {
                lastBoards == 1 -> tags += "limit:first_board"
                lastBoards == 2 -> tags += "limit:board2"
                lastBoards == 3 -> tags += "limit:board3"
                else -> tags += "limit:board4p"
            }
            if (lastStrong) tags += "limit:strong_seal"
            if ((lastVolRatio ?: 0.0) >= 2.0) tags += "limit:vol_surge"
        }
        if (brokenIdx >= n - 3 && brokenPrevStreak >= 1) {
            tags += "limit:broken"
            if (brokenPrevStreak >= 3) tags += "limit:high_board_broken"
        }
        if (lastLimitIdx >= n - 5) tags += "limit:active_5d"
        return tags
    }

    // ─────────────────────── 订单流近似层（#9） ───────────────────────

    /** 收盘位置值：收在最高 +1，收在最低 −1，无振幅 0。 */
    fun clv(b: KBar): Double {
        val rng = b.high - b.low
        if (rng <= 0.0) return 0.0
        return (b.close - b.low) / rng * 2.0 - 1.0
    }

    fun flowTags(bars: List<KBar>): List<String> {
        val n = bars.size
        if (n < MIN_BARS) return emptyList()
        val cum = DoubleArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + bars[i].vol
        val clvs = DoubleArray(n) { clv(bars[it]) }

        // 5 日 delta 归一：Σ(clv×vol) / (20日均量×5)
        var acc = 0.0
        for (j in n - 5 until n) acc += clvs[j] * bars[j].vol
        val vma = if (n >= 20) (cum[n] - cum[n - 20]) / 20.0 else 0.0
        val dnorm = if (vma > 0) acc / (vma * 5.0) else null

        val last = bars[n - 1]
        val prevClose = bars[n - 2].close
        val chg = if (prevClose > 0) (last.close / prevClose - 1.0) * 100.0 else 0.0
        val vr = if (vma > 0) last.vol / vma else null

        val tags = ArrayList<String>(4)
        if (dnorm != null) {
            if (dnorm >= 0.3) tags += "flow:delta_up"
            else if (dnorm <= -0.3) tags += "flow:delta_down"
        }
        // 吸收：放量却推不动（effort without result）
        if (vr != null && vr >= 2.0 && kotlin.math.abs(chg) <= 1.0 && kotlin.math.abs(clvs[n - 1]) <= 0.3) {
            tags += if (chg <= 0) "flow:absorb_down" else "flow:absorb_up"
        }
        // 连续失衡：近 3 根收盘位置同向且强势
        if (n >= 3) {
            if ((n - 3 until n).all { clvs[it] > 0.6 }) tags += "flow:imbalance_up"
            if ((n - 3 until n).all { clvs[it] < -0.6 }) tags += "flow:imbalance_down"
        }
        if (vr != null && vr >= 1.5 && kotlin.math.abs(chg) < 0.5) tags += "flow:volume_no_progress"
        return tags
    }

    // ─────────────────────── 反转结构层（#4 MTR） ───────────────────────

    private const val EXTREME_WIN = 20
    private const val SWING_K = 3
    private const val BREAK_MAX_GAP = 15
    private const val PULLBACK_MAX_GAP = 15
    private const val CONFIRM_MAX_GAP = 12
    private const val BREAK_MIN_ATR = 0.3
    private const val PULLBACK_LO = 0.20
    private const val PULLBACK_HI = 0.70
    private const val BREAK_LOOKBACK = 60

    private fun atr(bars: List<KBar>, n: Int = 14): Double {
        if (bars.size < n + 1) return 1e-9
        var sum = 0.0
        for (i in bars.size - n until bars.size) {
            val b = bars[i]
            val p = bars[i - 1]
            sum += maxOf(b.high - b.low, kotlin.math.abs(b.high - p.close), kotlin.math.abs(b.low - p.close))
        }
        return (sum / n).takeIf { it > 0 } ?: 1e-9
    }

    private fun swingHighs(bars: List<KBar>, k: Int = SWING_K): List<Int> {
        val out = ArrayList<Int>()
        for (i in k until bars.size - k) {
            val h = bars[i].high
            var ok = true
            for (j in i - k..i + k) if (h < bars[j].high) { ok = false; break }
            if (ok) out += i
        }
        return out
    }

    private fun swingLows(bars: List<KBar>, k: Int = SWING_K): List<Int> {
        val out = ArrayList<Int>()
        for (i in k until bars.size - k) {
            val l = bars[i].low
            var ok = true
            for (j in i - k..i + k) if (l > bars[j].low) { ok = false; break }
            if (ok) out += i
        }
        return out
    }

    /** 滑动窗口 MA（O(n)，别在循环里重算，端上 120 根 × 每票都要跑）。 */
    private fun maSeries(bars: List<KBar>, n: Int = 10): DoubleArray {
        val out = DoubleArray(bars.size) { Double.NaN }
        var acc = 0.0
        for (i in bars.indices) {
            acc += bars[i].close
            if (i >= n) acc -= bars[i - n].close
            if (i >= n - 1) out[i] = acc / n
        }
        return out
    }

    fun reversalTags(bars: List<KBar>): List<String> {
        val n = bars.size
        if (n < MIN_BARS) return emptyList()
        val atrv = atr(bars)
        val m10 = maSeries(bars, 10)
        val sh = swingHighs(bars)
        val sl = swingLows(bars)

        val top = topReversal(bars, atrv, m10, sh, sl)
        val bottom = bottomReversal(bars, atrv, m10, sh, sl)
        // 方向互斥：同一窗口内可先后出现顶与底，当下只认更近完成的那一套。
        return when {
            top.first.isEmpty() -> bottom.first
            bottom.first.isEmpty() -> top.first
            top.second >= bottom.second -> top.first
            else -> bottom.first
        }
    }

    /** 返回 (标签, 最后一环索引)。 */
    private fun topReversal(
        bars: List<KBar>, atrv: Double, m10: DoubleArray, sh: List<Int>, sl: List<Int>,
    ): Pair<List<String>, Int> {
        val n = bars.size
        val tags = ArrayList<String>(5)
        val winStart = n - EXTREME_WIN
        if (winStart < 0) return tags to -1
        val hiIdx = (winStart until n).maxByOrNull { bars[it].high } ?: return tags to -1
        val hi = bars[hiIdx].high
        // 趋势末端：必须接近 60 日新高（1% 容差），否则只是区间里的小高点
        val lookFrom = maxOf(0, hiIdx - 60)
        if (hi < (lookFrom..hiIdx).maxOf { bars[it].high } * 0.99) return tags to -1
        // 前置上升趋势：极值前 10 根累计 ≥ 2%
        if (hiIdx >= 10) {
            val pre = bars[hiIdx - 10].close
            if (pre > 0 && (bars[hiIdx].close / pre - 1.0) * 100.0 < 2.0) return tags to -1
        }
        if (hiIdx !in sh) return tags to -1
        tags += "mtr:extreme_top"

        val prevLows = sl.filter { it in (hiIdx - BREAK_LOOKBACK) until hiIdx }
        val refIdx = prevLows.lastOrNull() ?: return tags to -1
        val refLow = bars[refIdx].low
        val slope = (refLow - hi) / maxOf(1, hiIdx - refIdx)

        var brkIdx = -1
        for (j in hiIdx + 1..minOf(n - 1, hiIdx + BREAK_MAX_GAP)) {
            val line = hi + slope * (j - hiIdx)
            val m = m10[j]
            val belowLine = bars[j].close < line - BREAK_MIN_ATR * atrv
            val belowMa = !m.isNaN() && bars[j].close < m - BREAK_MIN_ATR * atrv * 0.5
            if (belowLine || belowMa) { brkIdx = j; break }
        }
        if (brkIdx < 0) return tags to -1
        tags += "mtr:break_down"

        val segLow = (brkIdx until n).minOf { bars[it].low }
        val span = hi - segLow
        if (span <= 0) return tags to brkIdx
        var pbIdx = -1
        for (j in brkIdx + 1..minOf(n - 1, brkIdx + PULLBACK_MAX_GAP)) {
            if (bars[j].high > hi) return tags to brkIdx // 越过极值 = 趋势延续
            val ratio = (bars[j].high - segLow) / span
            if (pbIdx < 0 && ratio in PULLBACK_LO..PULLBACK_HI) pbIdx = j
        }
        if (pbIdx < 0) return tags to brkIdx
        tags += "mtr:pullback_up"
        if (n - 1 - pbIdx > CONFIRM_MAX_GAP) return tags to pbIdx // 结构过期

        val pbLow = (pbIdx until n).minOf { bars[it].low }
        val after = pbIdx + 1 until n
        val lowerHigh = after.count() >= 2 && after.maxOf { bars[it].high } < bars[pbIdx].high
        val followThrough = bars[n - 1].close < pbLow ||
            (n >= 2 && bars[n - 1].close < bars[n - 2].close && bars[n - 1].close < pbLow * 1.02)
        if (lowerHigh) tags += "mtr:second_lower_high"
        if (lowerHigh && followThrough) tags += "mtr:top_confirmed"
        return tags to (n - 1)
    }

    private fun bottomReversal(
        bars: List<KBar>, atrv: Double, m10: DoubleArray, sh: List<Int>, sl: List<Int>,
    ): Pair<List<String>, Int> {
        val n = bars.size
        val tags = ArrayList<String>(5)
        val winStart = n - EXTREME_WIN
        if (winStart < 0) return tags to -1
        val loIdx = (winStart until n).minByOrNull { bars[it].low } ?: return tags to -1
        val lo = bars[loIdx].low
        val lookFrom = maxOf(0, loIdx - 60)
        if (lo > (lookFrom..loIdx).minOf { bars[it].low } * 1.01) return tags to -1
        if (loIdx >= 10) {
            val pre = bars[loIdx - 10].close
            if (pre > 0 && (bars[loIdx].close / pre - 1.0) * 100.0 > -2.0) return tags to -1
        }
        if (loIdx !in sl) return tags to -1
        tags += "mtr:extreme_bottom"

        val prevHighs = sh.filter { it in (loIdx - BREAK_LOOKBACK) until loIdx }
        val refIdx = prevHighs.lastOrNull() ?: return tags to -1
        val refHigh = bars[refIdx].high
        val slope = (refHigh - lo) / maxOf(1, loIdx - refIdx)

        var brkIdx = -1
        for (j in loIdx + 1..minOf(n - 1, loIdx + BREAK_MAX_GAP)) {
            val line = lo + slope * (j - loIdx)
            val m = m10[j]
            val aboveLine = bars[j].close > line + BREAK_MIN_ATR * atrv
            val aboveMa = !m.isNaN() && bars[j].close > m + BREAK_MIN_ATR * atrv * 0.5
            if (aboveLine || aboveMa) { brkIdx = j; break }
        }
        if (brkIdx < 0) return tags to -1
        tags += "mtr:break_up"

        val segHigh = (brkIdx until n).maxOf { bars[it].high }
        val span = segHigh - lo
        if (span <= 0) return tags to brkIdx
        var pbIdx = -1
        for (j in brkIdx + 1..minOf(n - 1, brkIdx + PULLBACK_MAX_GAP)) {
            if (bars[j].low < lo) return tags to brkIdx
            val ratio = (segHigh - bars[j].low) / span
            if (pbIdx < 0 && ratio in PULLBACK_LO..PULLBACK_HI) pbIdx = j
        }
        if (pbIdx < 0) return tags to brkIdx
        tags += "mtr:pullback_down"
        if (n - 1 - pbIdx > CONFIRM_MAX_GAP) return tags to pbIdx

        val pbHigh = (pbIdx until n).maxOf { bars[it].high }
        val after = pbIdx + 1 until n
        val higherLow = after.count() >= 2 && after.minOf { bars[it].low } > bars[pbIdx].low
        val followThrough = bars[n - 1].close > pbHigh ||
            (n >= 2 && bars[n - 1].close > bars[n - 2].close && bars[n - 1].close > pbHigh * 0.98)
        if (higherLow) tags += "mtr:second_higher_low"
        if (higherLow && followThrough) tags += "mtr:bottom_confirmed"
        return tags to (n - 1)
    }

    // ─────────────────────────── 不可用项 ───────────────────────────

    /** 拿不到的数据必须如实列出来，避免上层以为「已经算过封单/龙虎榜」。 */
    fun knownLimits(): Map<String, String> = linkedMapOf(
        "seal_amount" to "封单金额需 L2 逐笔，端上无此数据",
        "first_seal_time" to "首次封板时间需分钟数据，端上只有日 K",
        "open_count" to "打开次数只能用「收盘是否等于最高价」近似",
        "lhb_seats" to "龙虎榜席位无数据源",
        "chip_distribution" to "筹码分布需股东/逐笔数据",
        "true_delta" to "订单流为收盘位置代理，不是逐笔主动买卖",
        "st_stock" to "ST 涨跌幅 5% 无法从代码判断，可能误判涨停",
    )

    // ─────────────────────────── 自检 ───────────────────────────

    data class SelfCheck(val checks: Int, val ok: Boolean, val violations: List<String>)

    /**
     * 不变量自检：守的是「看着能跑、实际会骗人」的几处。
     * 全部用合成 K 线，不依赖网络与真实行情，可在 CI 跑。
     */
    fun selfCheck(): SelfCheck {
        val bad = ArrayList<String>()
        var count = 0
        fun expect(name: String, cond: Boolean, detail: String) {
            count++
            if (!cond) bad += "$name：$detail"
        }

        fun barCon(o: Double, c: Double, h: Double, l: Double, v: Double = 1000.0) =
            KBar("2026-01-01", o, c, h, l, v)

        expect("收在最高 clv=+1", kotlin.math.abs(clv(barCon(10.0, 11.0, 11.0, 10.0)) - 1.0) < 1e-9, "clv 应为 +1")
        expect("收在最低 clv=-1", kotlin.math.abs(clv(barCon(11.0, 10.0, 11.0, 10.0)) + 1.0) < 1e-9, "clv 应为 -1")
        expect("无振幅 clv=0", kotlin.math.abs(clv(barCon(10.0, 10.0, 10.0, 10.0))) < 1e-9, "无振幅不得除零")

        expect("主板涨停阈值", limitPct("600000") == 9.8, "主板应为 9.8")
        expect("创业板涨停阈值", limitPct("300750") == 19.8, "创业板应为 19.8")
        expect("科创板涨停阈值", limitPct("688981") == 19.8, "科创板应为 19.8")
        expect("北交所涨停阈值", limitPct("830799") == 29.8, "北交所应为 29.8")

        // 无标签 → 不给分（不是 50）
        val empty = compute(List(MIN_BARS) { barCon(10.0, 10.0, 10.05, 9.95) })
        expect("无证据不给分", empty.score == null, "横盘不得产出分数")
        expect("缺 K 线降级可见", compute(List(10) { barCon(10.0, 10.0, 10.1, 9.9) }).degraded.isNotEmpty(), "数据不足必须写进 degraded")

        expect("不可用项已声明", knownLimits().containsKey("seal_amount"), "封单必须声明不可用")
        expect("只出多头证据", CALIB.values.none { it.netFwd5 < 0 }, "校准表扣费后不应含负向标签（三层无看空判据）")

        return SelfCheck(count, bad.isEmpty(), bad)
    }
}
