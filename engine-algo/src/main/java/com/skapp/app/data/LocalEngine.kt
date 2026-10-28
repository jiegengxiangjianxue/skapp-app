package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.max

/**
 * 中枢引擎 —— 在手机上直接算指标与多引擎评分。
 *
 * 这就是「中枢软件运行引擎和算法」：不依赖站点 CI，也不等服务端。
 * 输入是设备自己拉到的日 K（腾讯/东财），输出与站点 engine_v5 /
 * multidim_engine 同构的评分，但**算法在设备上执行**。
 *
 * 红线：只用真实 K 线算，算不出就返回 null / insufficient，不编造。
 */

/** 单根 K 线派生出的技术因子 */
data class TechnicalFactors(
    val close: Double?,
    val ma5: Double?,
    val ma10: Double?,
    val ma20: Double?,
    val ma60: Double?,
    val macdDif: Double?,
    val macdDea: Double?,
    val macdHist: Double?,
    val rsi14: Double?,
    val k: Double?,
    val d: Double?,
    val j: Double?,
    val volRatio: Double?,
    val bollLower: Double?,
    val bollMid: Double?,
    val bollUpper: Double?,
    val atr14: Double?,
    val volMa5: Double?,
)

object TechnicalCalc {

    /**
     * @param now 判断「最后一根是不是今天的未完成 bar」用；盘中会按已开市分钟折算量比
     */
    fun compute(bars: List<KBar>, now: java.util.Date = java.util.Date()): TechnicalFactors? {
        if (bars.size < 30) return null
        val close = bars.last().close
        val ma5 = MarketDataClient.ma(bars, 5).lastOrNull()
        val ma10 = MarketDataClient.ma(bars, 10).lastOrNull()
        val ma20 = MarketDataClient.ma(bars, 20).lastOrNull()
        val ma60 = MarketDataClient.ma(bars, 60).lastOrNull()
        val (dif, dea, hist) = MarketDataClient.macd(bars)
        val (k, d, j) = MarketDataClient.kdj(bars)
        val (bl, bm, bu) = MarketDataClient.boll(bars)
        return TechnicalFactors(
            close = close,
            ma5 = ma5, ma10 = ma10, ma20 = ma20, ma60 = ma60,
            macdDif = dif.lastOrNull(), macdDea = dea.lastOrNull(), macdHist = hist.lastOrNull(),
            rsi14 = MarketDataClient.rsi(bars, 14),
            k = k, d = d, j = j,
            volRatio = run {
                // 盘中最后一根未走完：直接比「现量/均量」会把放量读成缩量，按已走分钟折算
                val mins = MarketHours.intradayMinutesFor(bars.last().date, now)
                if (mins != null) MarketDataClient.volumeRatioPaced(bars, 5, mins)
                else MarketDataClient.volumeRatio(bars, 5)
            },
            bollLower = bl, bollMid = bm, bollUpper = bu,
            atr14 = atr(bars, 14),
            volMa5 = bars.takeLast(5).map { it.vol }.average(),
        )
    }

    /** ATR(n) 平均真实波幅 */
    private fun atr(bars: List<KBar>, n: Int): Double? {
        if (bars.size <= n) return null
        val trs = ArrayList<Double>()
        for (i in 1 until bars.size) {
            val c = bars[i - 1].close
            val tr = maxOf(
                bars[i].high - bars[i].low,
                abs(bars[i].high - c),
                abs(bars[i].low - c),
            )
            trs += tr
        }
        return trs.takeLast(n).average()
    }
}

/** 单个引擎的评分结果 */
data class EngineScore(
    val engine: String,
    val score: Double?,        // 0-100
    val signal: String?,       // bullish / bearish / neutral / insufficient
    val reasons: List<String> = emptyList(),
)

/**
 * 12 个引擎按「测什么」分族。
 *
 * 不分族的后果是具体的：boll 与 keltner 都在答「价格站在通道哪一侧」；
 * supertrend/vortex 的分值只有两个取值（恒 bull 或 恒 bear，`signal()` 阈值 65/35 永远踩不到中性）；
 * trend 与 macd 又高度共线。逐引擎计票时「8 个看多」实际可能只代表 2~3 族的判断——
 * 共振率与下游 confidence 都虚高，而 confidence 直接显示在决策卡上。
 */
enum class EngineFamily(val label: String) {
    TREND("趋势"), MOMENTUM("动量"), CHANNEL("通道"),
    VOLUME("量能"), STRUCTURE("波动结构"), POSITION("位置"),
}

/** 族的净表态：族内按「分数偏离中性的量」加权平均，-1..1；|net| > 0.1（约 5 分）才算表态 */
data class FamilyScore(val family: EngineFamily, val net: Double, val members: Int) {
    val dir: Int get() = if (net > 0.1) 1 else if (net < -0.1) -1 else 0
}

/** 多引擎中枢结果；旧 composite 字段保留为兼容别名。 */
data class LocalEngineResult(
    val factors: TechnicalFactors?,
    val engines: List<EngineScore>,
    val composite: Double?,
    val nValid: Int,
    val barsUsed: Int,
    val weightedComposite: Double? = composite,
    val centralScore: Double? = weightedComposite,
    val resonanceRatio: Double? = null,
    val summary: String? = null,
    val engineName: String = "中枢引擎",
    val bullCount: Int = 0,
    val bearCount: Int = 0,
    val neutralCount: Int = 0,
    val status: String = "insufficient",
    /** 族级表态：12 个引擎按「测什么」折叠成 6 族，族内抵消后再统计方向 */
    val families: List<FamilyScore> = emptyList(),
    val bullFamilies: Int = 0,
    val bearFamilies: Int = 0,
    val neutralFamilies: Int = 0,
)

/**
 * 中枢多引擎：把站点 engine_v5（指标共振）+ multidim（多维评分）+
 * factor_lib（29 因子）的核心逻辑在设备上复现。
 *
 * 引擎清单（12 个，与站点算法同构）：
 *   trend 趋势 / momentum 动量 / volume 量能 / boll 布林 / macd MACD /
 *   supertrend SuperTrend / keltner Keltner / rsi_div RSI背离 /
 *   choppiness 震荡 / mfi MFI / vortex 涡旋 / obv 能量潮
 */
object LocalEngine {

    private val familyOf: Map<String, EngineFamily> = mapOf(
        "trend" to EngineFamily.TREND, "supertrend" to EngineFamily.TREND, "vortex" to EngineFamily.TREND,
        "momentum" to EngineFamily.MOMENTUM, "macd" to EngineFamily.MOMENTUM, "mfi" to EngineFamily.MOMENTUM,
        "boll" to EngineFamily.CHANNEL, "keltner" to EngineFamily.CHANNEL,
        "volume" to EngineFamily.VOLUME, "obv" to EngineFamily.VOLUME,
        "choppiness" to EngineFamily.STRUCTURE,
        "position" to EngineFamily.POSITION,
    )

    internal fun familiesOf(engines: List<EngineScore>): List<FamilyScore> =
        engines.filter { it.score != null }
            .groupBy { familyOf[it.engine] }
            .mapNotNull { (fam, list) ->
                if (fam == null) return@mapNotNull null
                val net = list.sumOf { ((it.score ?: 50.0) - 50.0) / 50.0 } / list.size
                FamilyScore(fam, net.coerceIn(-1.0, 1.0), list.size)
            }

    fun analyze(bars: List<KBar>, now: java.util.Date = java.util.Date()): LocalEngineResult {
        val f = TechnicalCalc.compute(bars, now)
        val engines = listOfNotNull(
            f?.let { trendEngine(it) },
            f?.let { momentumEngine(it) },
            f?.let { volumeEngine(bars, it) },
            f?.let { bollEngine(it) },
            f?.let { macdEngine(it) },
            f?.let { superTrendEngine(bars) },
            f?.let { keltnerEngine(bars) },
            f?.let { choppinessEngine(bars) },
            f?.let { mfiEngine(bars) },
            f?.let { vortexEngine(bars) },
            f?.let { obvEngine(bars) },
            f?.let { positionEngine(bars) },
        )
        val valid = engines.filter { it.score != null }
        val weights = mapOf(
            "trend" to 1.2, "momentum" to 1.1, "macd" to 1.1, "supertrend" to 1.1,
            "volume" to 1.0, "boll" to 1.0, "keltner" to 1.0, "choppiness" to 0.9,
            "mfi" to 1.0, "vortex" to 1.0, "obv" to 1.0, "position" to 0.9,
        )
        val weighted = valid.mapNotNull { score ->
            score.score?.let { value -> value to (weights[score.engine] ?: 1.0) }
        }
        val weightedComposite = weighted.takeIf { it.isNotEmpty() }?.let { values ->
            val weightTotal = values.sumOf { it.second }
            values.sumOf { it.first * it.second } / weightTotal
        }
        val bullCount = valid.count { it.signal == "bullish" }
        val bearCount = valid.count { it.signal == "bearish" }
        val neutralCount = valid.count { it.signal == "neutral" }
        // 共振改用**族级**口径：族内先抵消（同一件事不重复计票），族间再算方向一致度。
        // 旧口径逐引擎计票，而 boll/keltner 只覆盖「通道位置」、supertrend/vortex 恒给方向票，
        // 等于把 2~3 族的判断当成 8~10 份独立证据，共振与 confidence 系统性偏高。
        // 与 FactorWeightStrategy.aggregate() 是同一思路（那边用分族去相关解决同类问题）。
        val families = familiesOf(engines)
        val bullFamilies = families.count { it.dir > 0 }
        val bearFamilies = families.count { it.dir < 0 }
        val neutralFamilies = families.size - bullFamilies - bearFamilies
        val resonanceRatio = resonanceOf(bullFamilies, bearFamilies, families.size)
        val direction = when {
            families.isEmpty() -> "insufficient"
            bullFamilies > bearFamilies -> "偏多"
            bearFamilies > bullFamilies -> "偏空"
            else -> "中性"
        }
        val summary = weightedComposite?.let { score ->
            "中枢引擎：综合 ${"%.1f".format(score)}，族级共振 ${"%.0f%%".format((resonanceRatio ?: 0.0) * 100)}" +
                "（偏多 $bullFamilies / 偏空 $bearFamilies / 中性 $neutralFamilies 族，共 ${families.size} 族；" +
                "引擎票 多 $bullCount / 空 $bearCount / 中 $neutralCount，有效 ${valid.size}/${engines.size}）；$direction"
        }
        return LocalEngineResult(
            factors = f,
            engines = engines,
            composite = weightedComposite,
            nValid = valid.size,
            barsUsed = bars.size,
            weightedComposite = weightedComposite,
            centralScore = weightedComposite,
            resonanceRatio = resonanceRatio,
            summary = summary,
            bullCount = bullCount,
            bearCount = bearCount,
            neutralCount = neutralCount,
            status = if (weightedComposite == null) "insufficient" else "ok",
            families = families,
            bullFamilies = bullFamilies,
            bearFamilies = bearFamilies,
            neutralFamilies = neutralFamilies,
        )
    }

    /**
     * 共振（方向一致度）= max(偏多票, 偏空票) / 有效引擎数。
     *
     * 抽出来是为了能钉住口径：中性票不构成方向，绝不能计入「一致度」；
     * 旧实现取 max(bull, bear, neutral)，于是「12 个引擎全中性」被算成 100% 共振。
     * 现在喂进来的是**族级**计数（familiesOf），口径不变但不再是逐引擎计票。
     */
    internal fun resonanceOf(bull: Int, bear: Int, valid: Int): Double? =
        if (valid <= 0) null else maxOf(bull, bear).toDouble() / valid

    // ── 引擎 1: 趋势（均线排列 + 价格位置）────────────────
    private fun trendEngine(f: TechnicalFactors): EngineScore {
        val c = f.close ?: return insufficient("trend")
        val reasons = mutableListOf<String>()
        var score = 50.0
        listOf("MA5" to f.ma5, "MA10" to f.ma10, "MA20" to f.ma20, "MA60" to f.ma60)
            .forEach { (name, v) ->
                if (v == null) return@forEach
                if (c > v) { score += 6; reasons += "价在$name 上方" } else { score -= 6; reasons += "价在$name 下方" }
            }
        // 多头排列：MA5>MA10>MA20>MA60
        if (f.ma5 != null && f.ma10 != null && f.ma20 != null && f.ma60 != null &&
            f.ma5 > f.ma10 && f.ma10 > f.ma20 && f.ma20 > f.ma60
        ) { score += 12; reasons += "均线多头排列" }
        return EngineScore("trend", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    // ── 引擎 2: 动量（RSI + KDJ）──────────────────────────
    private fun momentumEngine(f: TechnicalFactors): EngineScore {
        val reasons = mutableListOf<String>()
        var score = 50.0
        f.rsi14?.let { rsi ->
            when {
                rsi > 80 -> { score -= 15; reasons += "RSI $rsi 超买" }
                rsi > 65 -> { score += 8; reasons += "RSI $rsi 偏强" }
                rsi < 20 -> { score += 15; reasons += "RSI $rsi 超卖反弹" }
                rsi < 35 -> { score -= 8; reasons += "RSI $rsi 偏弱" }
                else -> reasons += "RSI $rsi 中性"
            }
        }
        if (f.k != null && f.d != null) {
            when {
                f.k > 80 && f.d > 80 -> { score -= 10; reasons += "KDJ 高位" }
                f.k < 20 && f.d < 20 -> { score += 10; reasons += "KDJ 低位金叉区" }
                f.k > f.d -> { score += 6; reasons += "KDJ 金叉" }
                else -> { score -= 6; reasons += "KDJ 死叉" }
            }
        }
        return EngineScore("momentum", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    // ── 引擎 3: 量能（量比 + 量价配合）────────────────────
    private fun volumeEngine(bars: List<KBar>, f: TechnicalFactors): EngineScore {
        val reasons = mutableListOf<String>()
        var score = 50.0
        f.volRatio?.let { vr ->
            when {
                vr > 2.5 -> { score += 15; reasons += "量比 $vr 显著放量" }
                vr > 1.3 -> { score += 8; reasons += "量比 $vr 温和放量" }
                vr < 0.6 -> { score -= 8; reasons += "量比 $vr 明显缩量" }
                else -> reasons += "量比 $vr 正常"
            }
        }
        // 量价：近 5 日 涨跌 × 放量/缩量
        if (bars.size >= 6) {
            val last5 = bars.takeLast(5)
            val upDays = last5.count { it.close > it.open }
            val volUp = last5.map { it.vol }.average()
            val prevVol = bars.dropLast(5).takeLast(5).map { it.vol }.average()
            if (upDays >= 4 && volUp > prevVol) { score += 8; reasons += "放量上行" }
            if (upDays <= 1 && volUp > prevVol) { score -= 8; reasons += "放量下行" }
        }
        return EngineScore("volume", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    // ── 引擎 4: 布林位置 ──────────────────────────────────
    private fun bollEngine(f: TechnicalFactors): EngineScore {
        val c = f.close
        val lo = f.bollLower; val mid = f.bollMid; val hi = f.bollUpper
        if (c == null || lo == null || mid == null || hi == null || hi == lo) {
            return insufficient("boll")
        }
        val pos = (c - lo) / (hi - lo)          // 0 下轨 → 1 上轨
        val reasons = mutableListOf<String>()
        val score = when {
            pos > 1.0 -> { reasons += "突破布林上轨"; 45.0 }
            pos > 0.8 -> { reasons += "贴近布林上轨"; 55.0 }
            pos < 0.0 -> { reasons += "跌破布林下轨"; 55.0 }
            pos < 0.2 -> { reasons += "贴近布林下轨，超卖区"; 65.0 }
            else -> { reasons += "布林中轨附近"; 50.0 }
        }
        return EngineScore("boll", score, signal(score), reasons)
    }

    // ── 引擎 5: MACD ──────────────────────────────────────
    private fun macdEngine(f: TechnicalFactors): EngineScore {
        val hist = f.macdHist ?: return insufficient("macd")
        val reasons = mutableListOf<String>()
        var score = 50.0
        when {
            hist > 0 -> { score += 10; reasons += "MACD 红柱（多头）" }
            hist < 0 -> { score -= 10; reasons += "MACD 绿柱（空头）" }
        }
        f.macdDif?.let { dif ->
            if (f.macdDea != null) {
                if (dif > f.macdDea) { score += 8; reasons += "DIF 在 DEA 上方" }
                else { score -= 8; reasons += "DIF 在 DEA 下方" }
            }
        }
        return EngineScore("macd", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    private fun signal(score: Double): String = when {
        score >= 65 -> "bullish"
        score <= 35 -> "bearish"
        else -> "neutral"
    }

    // ── 引擎 6: SuperTrend ────────────────────────────────
    private fun superTrendEngine(bars: List<KBar>): EngineScore {
        val (dir, _) = ExtendedIndicators.superTrend(bars)
        if (dir == null) return insufficient("supertrend")
        val reasons = mutableListOf<String>()
        val score = if (dir > 0) {
            reasons += "SuperTrend 多头（线上方）"; 68.0
        } else {
            reasons += "SuperTrend 空头（线下方）"; 32.0
        }
        return EngineScore("supertrend", score, signal(score), reasons)
    }

    // ── 引擎 7: Keltner 通道 ──────────────────────────────
    private fun keltnerEngine(bars: List<KBar>): EngineScore {
        val (lo, mid, hi) = ExtendedIndicators.keltner(bars)
        val c = bars.lastOrNull()?.close
        if (lo == null || mid == null || hi == null || c == null || hi == lo) {
            return insufficient("keltner")
        }
        val pos = (c - lo) / (hi - lo)
        val reasons = mutableListOf<String>()
        val score = when {
            pos > 1.0 -> { reasons += "突破 Keltner 上轨"; 55.0 }
            pos > 0.7 -> { reasons += "Keltner 上轨附近"; 58.0 }
            pos < 0.0 -> { reasons += "跌破 Keltner 下轨"; 45.0 }
            pos < 0.3 -> { reasons += "Keltner 下轨附近，超卖"; 65.0 }
            else -> { reasons += "Keltner 中轨附近"; 50.0 }
        }
        return EngineScore("keltner", score, signal(score), reasons)
    }

    // ── 引擎 8: Choppiness 震荡/趋势判别 ──────────────────
    private fun choppinessEngine(bars: List<KBar>): EngineScore {
        val ch = ExtendedIndicators.choppiness(bars) ?: return insufficient("choppiness")
        val reasons = mutableListOf<String>()
        val score = when {
            ch < 38.5 -> { reasons += "CHOP $ch 趋势市场（可顺势）"; 62.0 }
            ch > 61.8 -> { reasons += "CHOP $ch 震荡市场（勿追）"; 42.0 }
            else -> { reasons += "CHOP $ch 过渡区"; 50.0 }
        }
        return EngineScore("choppiness", score, signal(score), reasons)
    }

    // ── 引擎 9: MFI 资金流量 ──────────────────────────────
    private fun mfiEngine(bars: List<KBar>): EngineScore {
        val mfi = ExtendedIndicators.mfi(bars) ?: return insufficient("mfi")
        val reasons = mutableListOf<String>()
        var score = 50.0
        when {
            mfi > 80 -> { score -= 12; reasons += "MFI $mfi 超买，资金流入过热" }
            mfi > 60 -> { score += 8; reasons += "MFI $mfi 资金流入偏强" }
            mfi < 20 -> { score += 12; reasons += "MFI $mfi 超卖，资金流出极端" }
            mfi < 40 -> { score -= 8; reasons += "MFI $mfi 资金流出偏强" }
            else -> reasons += "MFI $mfi 中性"
        }
        return EngineScore("mfi", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    // ── 引擎 10: Vortex 涡旋 ──────────────────────────────
    private fun vortexEngine(bars: List<KBar>): EngineScore {
        val (vip, vin) = ExtendedIndicators.vortex(bars)
        if (vip == null || vin == null || vin == 0.0) return insufficient("vortex")
        val reasons = mutableListOf<String>()
        val score = if (vip > vin) {
            reasons += "VI+ ${String.format("%.1f", vip)} > VI- ${String.format("%.1f", vin)}（多头）"; 62.0
        } else {
            reasons += "VI+ ${String.format("%.1f", vip)} < VI- ${String.format("%.1f", vin)}（空头）"; 38.0
        }
        return EngineScore("vortex", score, signal(score), reasons)
    }

    // ── 引擎 11: OBV 能量潮 ───────────────────────────────
    private fun obvEngine(bars: List<KBar>): EngineScore {
        if (bars.size < 30) return insufficient("obv")
        val obvSeries = ArrayList<Double>()
        var obv = 0.0
        bars.forEachIndexed { i, b ->
            if (i > 0) {
                val d = b.close - bars[i - 1].close
                obv += if (d > 0) b.vol else if (d < 0) -b.vol else 0.0
            }
            obvSeries += obv
        }
        val obvMa20 = obvSeries.takeLast(20).average()
        val priceUp = bars.last().close > bars[bars.size - 21].close
        val obvUp = obv > obvMa20
        val reasons = mutableListOf<String>()
        val score = when {
            priceUp && obvUp -> { reasons += "量价齐升（OBV 在均线上）"; 70.0 }
            priceUp && !obvUp -> { reasons += "价升量减，背离警示"; 40.0 }
            !priceUp && obvUp -> { reasons += "价跌量增，或有承接"; 55.0 }
            else -> { reasons += "价跌量减，同步下行"; 30.0 }
        }
        return EngineScore("obv", score, signal(score), reasons)
    }

    // ── 引擎 12: 位置（60日/240日分位）────────────────────
    private fun positionEngine(bars: List<KBar>): EngineScore {
        val p60 = ExtendedIndicators.distHigh60d(bars)
        val p240 = ExtendedIndicators.position240d(bars)
        if (p60 == null && p240 == null) return insufficient("position")
        val reasons = mutableListOf<String>()
        var score = 50.0
        p60?.let { r ->
            when {
                r > -0.02 -> { score += 6; reasons += "接近 60 日高点" }
                r < -0.30 -> { score += 8; reasons += "距 60 日高点 ${String.format("%.0f%%", r * 100)}，位置低" }
                else -> reasons += "距 60 日高点 ${String.format("%.0f%%", r * 100)}"
            }
        }
        if (p240 == null) {
            // 选股链路默认只取 120 根日 K，240 日分位算不出来——要说出来，
            // 不能让「位置引擎」看起来用到了 240 日口径
            reasons += "240 日分位需 ≥240 根日 K（本轮 ${bars.size} 根，不参与）"
        }
        p240?.let { p ->
            when {
                p > 0.9 -> { score -= 6; reasons += "240 日分位 ${String.format("%.0f%%", p * 100)}，高位" }
                p < 0.1 -> { score += 6; reasons += "240 日分位 ${String.format("%.0f%%", p * 100)}，低位" }
                else -> reasons += "240 日分位 ${String.format("%.0f%%", p * 100)}"
            }
        }
        return EngineScore("position", score.coerceIn(0.0, 100.0), signal(score), reasons)
    }

    private fun insufficient(name: String) =
        EngineScore(name, null, "insufficient", listOf("输入数据不足"))
}
