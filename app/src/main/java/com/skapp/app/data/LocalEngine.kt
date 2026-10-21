package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.max

/**
 * 本地引擎 —— 在手机上直接算指标与多引擎评分。
 *
 * 这就是「本地软件运行引擎和算法」：不依赖站点 CI，也不等服务端。
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

    fun compute(bars: List<KBar>): TechnicalFactors? {
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
            volRatio = MarketDataClient.volumeRatio(bars, 5),
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

/** 多引擎综合结果 */
data class LocalEngineResult(
    val factors: TechnicalFactors?,
    val engines: List<EngineScore>,
    val composite: Double?,
    val nValid: Int,
    val barsUsed: Int,
)

/**
 * 本地多引擎：把站点 engine_v5（指标共振）+ multidim（多维评分）的核心
 * 逻辑在设备上复现。每个引擎独立打分，composite 为有效引擎均值。
 */
object LocalEngine {

    fun analyze(bars: List<KBar>): LocalEngineResult {
        val f = TechnicalCalc.compute(bars)
        val engines = listOfNotNull(
            f?.let { trendEngine(it) },
            f?.let { momentumEngine(it) },
            f?.let { volumeEngine(bars, it) },
            f?.let { bollEngine(it) },
            f?.let { macdEngine(it) },
        )
        val valid = engines.filter { it.score != null }
        return LocalEngineResult(
            factors = f,
            engines = engines,
            composite = if (valid.isEmpty()) null
            else valid.mapNotNull { it.score }.average(),
            nValid = valid.size,
            barsUsed = bars.size,
        )
    }

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

    private fun insufficient(name: String) =
        EngineScore(name, null, "insufficient", listOf("输入数据不足"))
}
