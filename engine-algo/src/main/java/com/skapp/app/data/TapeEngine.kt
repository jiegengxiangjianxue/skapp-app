package com.skapp.app.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * tape 盘口与量价结构引擎（V1）。
 *
 * ## 为什么端上做得出
 * 真内外盘要逐笔成交、真委比要五档挂单，端上一个都拿不到。能拿到的是
 * 开/高/低/收/昨收/振幅/量比/成交额（[LiveMarket.Row]）与当日之前的日 K（[KBar]）。
 * 于是所有分项都改写成「用日内位置 + 影线 + 缺口 + 收盘位置」表达的结构量：
 * 收盘位置在区间上沿且振幅不小，就是**主动买占优的形态代理**，不是真内外盘，别当真的用。
 *
 * ## 与板块层的口径关系
 * 板块层已有 `closePos` / `longShadowN`（[LocalSectors.Row]），口径是「成员收盘在最低—最高区间的
 * 平均位置」与「下影 ≥ 振幅 50%」。本引擎**只算个股层**，板块层不得再实现一遍，
 * 否则同一个 0.5 门槛会出现两套，改一处漏一处。
 *
 * ## 口径红线
 * - 一字板 `high == low` 时没有区间可言，`buyProxy=null`：0.5 是「横盘」不是「无法判断」。
 * - 开盘前不出分：`open`/`prevClose` 都没定，缺口项会拿昨日收盘当开盘价编造。
 * - 缺口超过涨跌停上限一律判除权，不给缺口项（端上无除权信息，只能这样兜）。
 */
object TapeEngine {

    const val VERSION = 1

    data class Params(
        val ampK: Double = 3.0,
        val divergePerVr: Double = 1.0,
        val divergeScale: Double = 2.0,
        val wBuy: Double = 12.0,
        val wDiverge: Double = 8.0,
        val wShadow: Double = 6.0,
        val wGap: Double = 6.0,
        val wPos: Double = 6.0,
        val bullThreshold: Double = 62.0,
        val bearThreshold: Double = 38.0,
        val gapMinPct: Double = 1.0,
        val shadowLong: Double = 0.5,
        val abnormalGapPct: Double = 11.0,
        val posBars: Int = 5,
        val minBars: Int = 2,
        val minAmountYuan: Double = 1.0e7,
        val openMinute: Int = 570,
        val closeAuctionMinute: Int = 897,
        val closeConfidence: Double = 0.8,
        val minUsedWeight: Int = 10,
    )

    data class Result(
        val score: Double?,
        val signal: String,
        val buyProxy: Double?,
        val divergence: Double?,
        val upperShadow: Double?,
        val lowerShadow: Double?,
        val gapPct: Double?,
        val gapFilled: Boolean?,
        val closePos5: Double?,
        val confidence: Double,
        val usedWeight: Int,
        val status: String,
        val reasons: List<String>,
    )

    private const val FULL_WEIGHT = 38.0

    fun compute(
        bars: List<KBar>,
        row: LiveMarket.Row? = null,
        nowMs: Long = System.currentTimeMillis(),
        p: Params = Params(),
    ): Result {
        val minute = cstMinute(nowMs)
        if (minute < p.openMinute) {
            return empty("pre_open", listOf("开盘前 open/prevClose 未定，缺口项不可算"), 0)
        }
        val last = bars.lastOrNull()
        val open = row?.open ?: last?.open
        val high = row?.high ?: last?.high
        val low = row?.low ?: last?.low
        val close = row?.price ?: last?.close
        if (open == null || high == null || low == null || close == null) {
            return empty("insufficient", listOf("缺 open/high/low/close，结构分项全不可算"), 0)
        }
        if (row == null && bars.size < p.minBars) {
            return empty("insufficient", listOf("日 K 少于 ${p.minBars} 根且无实时行"), 0)
        }
        val reasons = ArrayList<String>(6)
        val pct = row?.pctChg ?: run {
            val prevClose = bars.getOrNull(bars.size - 2)?.close
            if (prevClose != null && prevClose > 0) (close / prevClose - 1.0) * 100.0 else null
        }
        val amplitude = row?.amplitude ?: run {
            val prevClose = bars.getOrNull(bars.size - 2)?.close
            if (prevClose != null && prevClose > 0) (high - low) / prevClose * 100.0 else null
        }
        val volRatio = row?.volRatio

        var weightSum = 0
        var weighted = 0.0
        fun add(w: Double, s: Double) {
            val wi = w.toInt()
            weightSum += wi
            weighted += w * s.coerceIn(-1.0, 1.0)
        }

        val buyProxy: Double?
        val range = high - low
        val cpr = if (range > 1e-9) ((close - low) / range).coerceIn(0.0, 1.0) else null
        if (cpr != null && amplitude != null) {
            val shrink = (amplitude / p.ampK).coerceIn(0.0, 1.0)
            val bp = (0.5 + (cpr - 0.5) * shrink).coerceIn(0.0, 1.0)
            buyProxy = bp
            add(p.wBuy, 2.0 * bp - 1.0)
            if (bp >= 0.65) reasons.add("收在区间上沿且振幅够（${fmt(amplitude)}%）：主动买占优")
            if (bp <= 0.35) reasons.add("收在区间下沿：主动卖占优")
        } else {
            buyProxy = null
            reasons.add("一字板/无振幅：内外盘代理不可算")
        }

        val divergence: Double?
        if (pct != null && volRatio != null) {
            val e = ((pct - p.divergePerVr * (volRatio - 1.0)) / p.divergeScale).coerceIn(-2.0, 2.0)
            divergence = e
            add(p.wDiverge, e / 2.0)
            if (volRatio >= 2.0 && abs(pct) <= 1.0) reasons.add("放量滞涨/滞跌（量比 ${fmt(volRatio)}，涨幅 ${fmt(pct)}%）")
            if (volRatio >= 1.5 && pct < 0) reasons.add("放量下跌")
        } else {
            divergence = null
        }

        val upper: Double?
        val lower: Double?
        if (range > 1e-9) {
            upper = ((high - maxOf(open, close)) / range).coerceIn(0.0, 1.0)
            lower = ((minOf(open, close) - low) / range).coerceIn(0.0, 1.0)
            add(p.wShadow, lower - upper)
            if (upper >= p.shadowLong) reasons.add("长上影 ${fmt(upper * 100)}%：上方抛压")
            if (lower >= p.shadowLong) reasons.add("长下影 ${fmt(lower * 100)}%：下方承接")
        } else {
            upper = null
            lower = null
        }

        val prevBar = bars.getOrNull(bars.size - 2)
        val prevClose = row?.prevClose ?: prevBar?.close
        val gapPct: Double?
        val gapFilled: Boolean?
        var gapItem = 0.0
        var gapUsable = false
        if (prevClose != null && prevClose > 0 && row != null) {
            val g = (open - prevClose) / prevClose * 100.0
            gapPct = g
            val cap = row.limitCap
            if (abs(g) > cap + 0.5) {
                gapFilled = null
                reasons.add("缺口 ${fmt(g)}% 超涨跌停上限，判除权/复牌，缺口项不计")
            } else {
                val prevHigh = prevBar?.high
                val prevLow = prevBar?.low
                val upGap = g >= p.gapMinPct && prevHigh != null && open > prevHigh
                val downGap = g <= -p.gapMinPct && prevLow != null && open < prevLow
                gapFilled = when {
                    upGap -> {
                        gapItem = if (close < prevHigh) -0.5 else 1.0
                        close < prevHigh
                    }
                    downGap -> {
                        gapItem = -1.0
                        false
                    }
                    else -> null
                }
                gapUsable = gapFilled != null || upGap || downGap
                if (upGap) reasons.add(if (gapFilled == true) "向上跳空已回补" else "向上跳空未回补")
                if (downGap) reasons.add("向下跳空")
            }
        } else {
            gapPct = null
            gapFilled = null
        }
        if (gapUsable) add(p.wGap, gapItem)

        val closePos5: Double?
        if (bars.size >= p.posBars) {
            val win = bars.subList(bars.size - p.posBars, bars.size)
            val hi5 = win.maxOf { it.high }
            val lo5 = win.minOf { it.low }
            val cp = if (hi5 - lo5 > 1e-9) ((close - lo5) / (hi5 - lo5)).coerceIn(0.0, 1.0) else null
            closePos5 = cp
            if (cp != null) add(p.wPos, 2.0 * cp - 1.0)
        } else {
            closePos5 = null
            reasons.add("日 K ${bars.size} 根 < ${p.posBars}，${p.posBars} 日收盘位置不计")
        }

        if (weightSum < p.minUsedWeight) {
            return empty("insufficient", reasons + "可用权重 $weightSum/${FULL_WEIGHT.toInt()} 过低", weightSum)
        }
        var score = 50.0 + FULL_WEIGHT * (weighted / weightSum)
        if (row != null && row.amount != null && row.amount!! < p.minAmountYuan) {
            reasons.add("成交额 < ${p.minAmountYuan} 元，结构项噪声主导，不给分")
            return empty("illiquid", reasons, weightSum)
        }
        val conf = if (minute >= p.closeAuctionMinute) {
            reasons.add("收盘集合竞价时段，收盘价可能再变，置信打折")
            p.closeConfidence
        } else {
            1.0
        }
        score = score.coerceIn(0.0, 100.0)
        val signal = when {
            score >= p.bullThreshold -> "bullish"
            score <= p.bearThreshold -> "bearish"
            else -> "neutral"
        }
        return Result(
            score = score,
            signal = signal,
            buyProxy = buyProxy,
            divergence = divergence,
            upperShadow = upper,
            lowerShadow = lower,
            gapPct = gapPct,
            gapFilled = gapFilled,
            closePos5 = closePos5,
            confidence = conf,
            usedWeight = weightSum,
            status = "ok",
            reasons = reasons,
        )
    }

    private fun empty(status: String, reasons: List<String>, usedWeight: Int) = Result(
        score = null,
        signal = "insufficient",
        buyProxy = null,
        divergence = null,
        upperShadow = null,
        lowerShadow = null,
        gapPct = null,
        gapFilled = null,
        closePos5 = null,
        confidence = 0.0,
        usedWeight = usedWeight,
        status = status,
        reasons = reasons,
    )

    /** 公开包装：调用方要按「已交易分钟」还原换手率时需要它，不再各写一份 Calendar。 */
    fun minuteOfDay(nowMs: Long): Int = cstMinute(nowMs)

    internal fun cstMinute(nowMs: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun fmt(v: Double?): String = if (v == null) "null" else String.format("%.2f", v)
}
