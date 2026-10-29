package com.skapp.app.data

/**
 * 早盘 / 明日候选引擎 —— 端上复刻云端两套 6 维投票。
 *
 * 口径唯一真值（云端）：
 *  - 明日候选 `stocks-ai-view/scripts/engines/tomorrow_candidate_engine.py:186-306`
 *  - 早盘推股 `stocks-ai-view/scripts/engines/morning_pick_engine.py:155-288`
 *
 * 两套聚合公式**不同且不是笔误**，不要为了「看起来统一」把系数同化：
 *  - 明日候选 `score = 50 + 50 × base × (0.6 + 0.4 × 同向率)`
 *  - 早盘     `score = 50 + 50 × base × (0.5 + 0.5 × 同向率)`
 *
 * 数值一致性由 `EarlyVoteParityTest` 钉住：期望分来自云端引擎本体对同一批 K 线的输出
 * （夹具 `testdata/parity/early_votes.json` 由 Python 现算，不是手写常量）。
 *
 * 端上缺料时的处理与云端一致：缺该维原料就**不产生这一票**（云端 `if v:` 才 append），
 * 分母随之变化，绝不用 0 票或中性票顶替。
 */
object EarlyVoteEngine {

    /** 两套引擎的族差异只有同向率系数与票集，其余实现共用。 */
    enum class Family(
        val id: String,
        val label: String,
        val sameBase: Double,
        val sameSpan: Double,
    ) {
        TOMORROW("tomorrow_candidate", "明日候选", 0.6, 0.4),
        MORNING("morning_pick", "早盘推股", 0.5, 0.5),
    }

    data class Vote(
        val indicator: String,
        val vote: Int,
        /** 已按云端口径 round(…,3)。 */
        val strength: Double,
        val why: String,
    )

    data class Result(
        val family: Family,
        /** 0~100，已 round(…,2)。 */
        val score: Double,
        /** Σw·vote·strength / Σw，[-1,1]。 */
        val base: Double,
        /** 同向率 max(多,空)/票数，[0,1]。 */
        val sameRate: Double,
        val bull: Int,
        val bear: Int,
        val neutral: Int,
        val votes: List<Vote>,
        /** 缺料的维度（票因此缺席），给界面做「为何只有 4 票」的解释。 */
        val missing: List<String>,
    )

    /**
     * 外部原料。全部可空：拿不到就对应维缺席，不做插值。
     *
     * @param industryChg5  所属行业近 5 日涨幅（%），端上来自 LocalSectors
     * @param indexCloses   指数日收盘 [(date, close)]，升序，需 ≥21 根
     * @param globalAvgChg  非 A 股指数隔夜均涨跌（%），来自 MarketDataClient.globalIndices
     * @param mainNetToday  当日主力净流入（元），来自实时快照 LiveMarket.Row.mainNet
     */
    data class Context(
        val industryChg5: Double? = null,
        val indexCloses: List<Pair<String, Double>> = emptyList(),
        val globalAvgChg: Double? = null,
        val mainNetToday: Double? = null,
    )

    private const val RSI_N = 14

    /** 明日候选 6 维：ma / rsi / macd / volume / sector / rel_strength。 */
    fun tomorrow(
        bars: List<KBar>,
        ctx: Context,
        weights: Map<String, Double> = emptyMap(),
    ): Result? {
        if (bars.size < 2) return null
        val closes = bars.map { it.close }
        val votes = mutableListOf<Vote>()
        voteMa(closes)?.let(votes::add)
        voteRsi(closes)?.let(votes::add)
        voteMacd(closes)?.let(votes::add)
        voteVolume(bars)?.let(votes::add)
        voteSector(ctx.industryChg5)?.let(votes::add)
        voteRelStrength(bars, ctx.indexCloses)?.let(votes::add)
        return aggregate(
            Family.TOMORROW, votes, weights,
            missingOf(ctx, needIndex = true),
        )
    }

    /** 早盘 6 维：gap_env / breakout / pullback / momentum / vol_energy / moneyflow。 */
    fun morning(
        bars: List<KBar>,
        ctx: Context,
        weights: Map<String, Double> = emptyMap(),
    ): Result? {
        if (bars.size < 2) return null
        val closes = bars.map { it.close }
        val votes = mutableListOf<Vote>()
        voteGapEnv(ctx.globalAvgChg)?.let(votes::add)
        voteBreakout(bars)?.let(votes::add)
        votePullback(closes, bars)?.let(votes::add)
        voteMomentum(bars)?.let(votes::add)
        voteVolEnergy(bars)?.let(votes::add)
        voteMoneyflow(ctx.mainNetToday)?.let(votes::add)
        return aggregate(
            Family.MORNING, votes, weights,
            missingOf(ctx, needIndex = false),
        )
    }

    private fun missingOf(ctx: Context, needIndex: Boolean): List<String> = buildList {
        if (ctx.industryChg5 == null && needIndex) add("sector（行业涨幅不可得）")
        if (ctx.indexCloses.size < 21 && needIndex) add("rel_strength（指数收盘不足 21 根）")
        if (ctx.globalAvgChg == null && !needIndex) add("gap_env（外围指数不可得）")
        if (ctx.mainNetToday == null && !needIndex) add("moneyflow（资金流快照不可得）")
    }

    // ── 聚合（云端 score_stock）──────────────────────────────
    private fun aggregate(
        family: Family,
        votes: List<Vote>,
        weights: Map<String, Double>,
        missing: List<String>,
    ): Result? {
        if (votes.isEmpty()) return null
        var num = 0.0
        var den = 0.0
        var bull = 0
        var bear = 0
        for (v in votes) {
            val w = weights[v.indicator] ?: 1.0
            num += w * v.vote * v.strength
            den += w
            if (v.vote > 0) bull++ else if (v.vote < 0) bear++
        }
        if (den == 0.0) return null
        val base = num / den
        val same = maxOf(bull, bear).toDouble() / votes.size
        val raw = 50.0 + 50.0 * base * (family.sameBase + family.sameSpan * same)
        return Result(
            family = family,
            score = CanonicalIndicators.pyRound(raw.coerceIn(0.0, 100.0), 2),
            base = CanonicalIndicators.pyRound(base, 4),
            sameRate = CanonicalIndicators.pyRound(same, 4),
            bull = bull,
            bear = bear,
            neutral = votes.size - bull - bear,
            votes = votes,
            missing = missing,
        )
    }

    private fun vote(indicator: String, v: Int, strength: Double, why: String) =
        Vote(indicator, v, CanonicalIndicators.pyRound(minOf(maxOf(strength, 0.0), 1.0), 3), why)

    // ── 明日候选各维 ─────────────────────────────────────────
    private fun sma(values: List<Double>, n: Int): Double? =
        if (values.size < n) null else values.takeLast(n).sum() / n

    /** cloud `_rsi` 是**简单均值**口径（不是 Wilder 平滑），与 canonical rsi 不同，别互换。 */
    private fun simpleRsi(closes: List<Double>, n: Int = RSI_N): Double? {
        if (closes.size < n + 1) return null
        var g = 0.0
        var l = 0.0
        for (i in closes.size - n until closes.size) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) g += d else l += -d
        }
        if (l == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + (g / n) / (l / n))
    }

    private fun emaSeries(closes: List<Double>, n: Int): List<Double> {
        val k = 2.0 / (n + 1)
        var e = closes.first()
        val out = ArrayList<Double>(closes.size)
        for (c in closes) {
            e = c * k + e * (1 - k)
            out.add(e)
        }
        return out
    }

    private fun voteMa(closes: List<Double>): Vote? {
        val c = closes.last()
        val m20 = sma(closes, 20) ?: return null
        val m60 = sma(closes, 60) ?: return null
        if (m20 == 0.0 || m60 == 0.0) return null
        val dev20 = (c - m20) / m20 * 100
        if (c > m20 && m20 > m60 && dev20 < 15) {
            return vote("ma", 1, minOf(maxOf(dev20, 1.0) / 10, 1.0),
                "多头排列(收>${"%.2f".format(m20)}>${"%.2f".format(m60)}), 乖离 ${"%+.1f".format(dev20)}%")
        }
        if (c < m20 && m20 < m60) {
            return vote("ma", -1, minOf(kotlin.math.abs(dev20) / 10, 1.0),
                "空头排列, 乖离 ${"%+.1f".format(dev20)}%")
        }
        return vote("ma", 0, 0.2, "均线缠绕, 乖离MA20 ${"%+.1f".format(dev20)}%")
    }

    private fun voteRsi(closes: List<Double>): Vote? {
        val r = simpleRsi(closes) ?: return null
        return when {
            r in 50.0..70.0 -> vote("rsi", 1, (r - 50) / 20 * 0.8 + 0.2, "RSI ${"%.1f".format(r)} 强而未热")
            r > 80 -> vote("rsi", -1, minOf((r - 80) / 20 + 0.4, 1.0), "RSI ${"%.1f".format(r)} 过热")
            r < 30 -> vote("rsi", -1, minOf((30 - r) / 30 + 0.3, 1.0), "RSI ${"%.1f".format(r)} 超卖弱势")
            r < 45 -> vote("rsi", -1, 0.3, "RSI ${"%.1f".format(r)} 偏弱")
            else -> vote("rsi", 0, 0.2, "RSI ${"%.1f".format(r)} 中性")
        }
    }

    private fun voteMacd(closes: List<Double>): Vote? {
        if (closes.size < 35) return null
        val e12 = emaSeries(closes, 12)
        val e26 = emaSeries(closes, 26)
        val dif = e12.indices.map { e12[it] - e26[it] }
        val dea = emaSeries(dif, 9)
        if (dif.size < 2) return null
        val d = dif.last()
        val e = dea.last()
        val pd = dif[dif.size - 2]
        val pe = dea[dea.size - 2]
        return when {
            pd <= pe && d > e -> vote("macd", 1, 0.9, "MACD 金叉")
            d > e && d > 0 -> vote("macd", 1, 0.5, "MACD 零上多头")
            pd >= pe && d < e -> vote("macd", -1, 0.9, "MACD 死叉")
            d < e && d < 0 -> vote("macd", -1, 0.5, "MACD 零下空头")
            else -> vote("macd", 0, 0.2, "MACD 收敛")
        }
    }

    /** 20 日均量（不含当日）。 */
    private fun vol20Prev(bars: List<KBar>): Double {
        if (bars.size < 21) return 0.0
        var s = 0.0
        for (i in bars.size - 21 until bars.size - 1) s += bars[i].vol
        val v = s / 20
        return if (v == 0.0) 1.0 else v
    }

    private fun chgPct(bars: List<KBar>): Double {
        if (bars.size < 2) return 0.0
        val prev = bars[bars.size - 2].close
        if (prev == 0.0) return 0.0
        return (bars.last().close - prev) / prev * 100
    }

    private fun voteVolume(bars: List<KBar>): Vote? {
        if (bars.size < 21) return null
        val v20 = vol20Prev(bars)
        val vr = bars.last().vol / v20
        val chg = chgPct(bars)
        return when {
            vr >= 1.8 && chg > 1.5 -> vote("volume", 1, minOf(vr / 4 + 0.3, 1.0),
                "放量上涨(量比 ${"%.1f".format(vr)}, 涨 ${"%+.1f".format(chg)}%)")
            vr >= 1.8 && chg < -1.5 -> vote("volume", -1, minOf(vr / 4 + 0.3, 1.0),
                "放量下跌(量比 ${"%.1f".format(vr)}, 跌 ${"%+.1f".format(chg)}%)")
            vr <= 0.6 && chg < 0 -> vote("volume", 1, 0.3,
                "缩量回调(量比 ${"%.2f".format(vr)}), 抛压衰减")
            else -> vote("volume", 0, 0.2, "量比 ${"%.2f".format(vr)} 平量")
        }
    }

    private fun voteSector(indChg5: Double?): Vote? {
        if (indChg5 == null) return null
        return when {
            indChg5 >= 3.0 -> vote("sector", 1, minOf(indChg5 / 10, 1.0),
                "行业近5日 ${"%+.1f".format(indChg5)}% 领涨共振")
            indChg5 <= -3.0 -> vote("sector", -1, minOf(kotlin.math.abs(indChg5) / 10, 1.0),
                "行业近5日 ${"%+.1f".format(indChg5)}% 领跌")
            else -> vote("sector", 0, 0.2, "行业近5日 ${"%+.1f".format(indChg5)}% 中性")
        }
    }

    /**
     * 20 日相对强度：以**指数最后交易日**为对齐锚点（云端注释明确：指数常滞后个股，
     * 用个股末日对齐会让该维静默缺席）。
     */
    private fun voteRelStrength(bars: List<KBar>, indexCloses: List<Pair<String, Double>>): Vote? {
        if (bars.size < 21 || indexCloses.size < 21) return null
        val anchor = indexCloses.last().first
        val win = bars.filter { it.date <= anchor }
        if (win.size < 21) return null
        val idx = indexCloses.filter { it.first <= anchor }.map { it.second }
        if (idx.size < 21) return null
        val baseClose = win[win.size - 21].close
        val baseIdx = idx[idx.size - 21]
        if (baseClose == 0.0 || baseIdx == 0.0) return null
        val stockChg = (win.last().close - baseClose) / baseClose * 100
        val idxChg = (idx.last() - baseIdx) / baseIdx * 100
        val rel = stockChg - idxChg
        return when {
            rel >= 5 -> vote("rel_strength", 1, minOf(rel / 15, 1.0),
                "20日相对强度 ${"%+.1f".format(rel)}% 显著强于大盘")
            rel <= -5 -> vote("rel_strength", -1, minOf(kotlin.math.abs(rel) / 15, 1.0),
                "20日相对强度 ${"%+.1f".format(rel)}% 弱于大盘")
            else -> vote("rel_strength", 0, 0.2, "20日相对强度 ${"%+.1f".format(rel)}%")
        }
    }

    // ── 早盘各维 ─────────────────────────────────────────────
    private fun voteGapEnv(avgChg: Double?): Vote? {
        if (avgChg == null) return null
        return when {
            avgChg >= 0.8 -> vote("gap_env", 1, minOf(avgChg / 2, 1.0),
                "隔夜外围均涨 ${"%+.2f".format(avgChg)}% 偏暖")
            avgChg <= -0.8 -> vote("gap_env", -1, minOf(kotlin.math.abs(avgChg) / 2, 1.0),
                "隔夜外围均跌 ${"%+.2f".format(avgChg)}% 承压")
            else -> vote("gap_env", 0, 0.2, "隔夜外围 ${"%+.2f".format(avgChg)}% 平稳")
        }
    }

    private fun voteBreakout(bars: List<KBar>): Vote? {
        if (bars.size < 61) return null
        val c = bars.last().close
        var hi60 = Double.NEGATIVE_INFINITY
        for (i in bars.size - 61 until bars.size - 1) if (bars[i].high > hi60) hi60 = bars[i].high
        val v20 = vol20Prev(bars)
        val vr = bars.last().vol / v20
        val chg = chgPct(bars)
        return when {
            c >= hi60 && vr >= 1.5 -> vote("breakout", 1, minOf(vr / 3 + 0.4, 1.0),
                "放量突破60日新高 ${"%.2f".format(hi60)} (量比 ${"%.1f".format(vr)})")
            c >= hi60 && vr < 0.8 -> vote("breakout", 0, 0.3,
                "创新高但缩量(量比 ${"%.2f".format(vr)}), 动能存疑")
            chg < -3 && vr >= 2 -> vote("breakout", -1, 0.6,
                "放量重挫 ${"%+.1f".format(chg)}%, 早盘回避")
            else -> vote("breakout", 0, 0.2, "未突破")
        }
    }

    private fun votePullback(closes: List<Double>, bars: List<KBar>): Vote? {
        if (bars.size < 21) return null
        val m20 = sma(closes, 20) ?: return null
        val m60 = sma(closes, 60) ?: return null
        if (m20 == 0.0 || m60 == 0.0) return null
        val v20 = vol20Prev(bars)
        val vr = bars.last().vol / v20
        val chg = chgPct(bars)
        val c = closes.last()
        val uptrend = c > m20 && m20 > m60
        return when {
            uptrend && vr <= 0.7 && chg >= -2.5 && chg <= 0.5 ->
                vote("pullback", 1, minOf((0.7 - vr) + 0.4, 1.0),
                    "上升趋势缩量回踩(量比 ${"%.2f".format(vr)}, ${"%+.1f".format(chg)}%), 低吸窗口")
            !uptrend && c < m60 -> vote("pullback", -1, 0.4, "收于 MA60 下方, 趋势走弱")
            else -> vote("pullback", 0, 0.2, "无回踩结构")
        }
    }

    private fun voteMomentum(bars: List<KBar>): Vote? {
        if (bars.size < 6) return null
        val base = bars[bars.size - 6].close
        if (base == 0.0) return null
        val chg5 = (bars.last().close - base) / base * 100
        return when {
            chg5 in 3.0..15.0 -> vote("momentum", 1, minOf(chg5 / 15, 1.0),
                "5日动量 ${"%+.1f".format(chg5)}% 温和走强")
            chg5 > 20 -> vote("momentum", -1, 0.6, "5日 ${"%+.1f".format(chg5)}% 过热, 早盘追高风险")
            chg5 < -8 -> vote("momentum", -1, 0.5, "5日 ${"%+.1f".format(chg5)}% 急跌, 不接飞刀")
            else -> vote("momentum", 0, 0.2, "5日动量 ${"%+.1f".format(chg5)}%")
        }
    }

    /**
     * 量能结构。注意云端此处 20 日均量的切片是 **[size-20, size)**（含当日），
     * 与 volume/breakout/pullback 的 `[-21:-1]`（不含当日）不同 —— 这是云端原样行为，
     * 统一掉会让这维的票与云端不一致。
     */
    private fun voteVolEnergy(bars: List<KBar>): Vote? {
        if (bars.size < 22) return null
        var v5 = 0.0
        for (i in bars.size - 5 until bars.size) v5 += bars[i].vol
        v5 /= 5
        var s20 = 0.0
        for (i in bars.size - 20 until bars.size) s20 += bars[i].vol
        val v20 = if (s20 / 20 == 0.0) 1.0 else s20 / 20
        val chg = chgPct(bars)
        val ratio = v5 / v20
        return when {
            ratio in 1.05..2.0 -> vote("vol_energy", 1, minOf((ratio - 1) / 1, 1.0) * 0.7 + 0.2,
                "量能温和放大(5日/20日 ${"%.2f".format(ratio)})")
            ratio > 3 && kotlin.math.abs(chg) < 1 -> vote("vol_energy", -1, 0.6,
                "巨量滞涨(5日/20日 ${"%.2f".format(ratio)}), 分歧出货嫌疑")
            ratio < 0.6 -> vote("vol_energy", 0, 0.3, "量能萎缩(${"%.2f".format(ratio)}), 观望")
            else -> vote("vol_energy", 0, 0.2, "量能 ${"%.2f".format(ratio)}")
        }
    }

    private fun voteMoneyflow(net: Double?): Vote? {
        if (net == null) return null
        return when {
            net > 0 -> vote("moneyflow", 1, 0.4, "快照主力净流入 ${"%+.0f".format(net / 1e4)} 万")
            net < 0 -> vote("moneyflow", -1, 0.4, "快照主力净流出 ${"%+.0f".format(net / 1e4)} 万")
            else -> vote("moneyflow", 0, 0.1, "快照资金流中性")
        }
    }
}
