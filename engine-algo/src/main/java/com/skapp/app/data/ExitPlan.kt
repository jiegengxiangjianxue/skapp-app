package com.skapp.app.data

/**
 * 退出纪律引擎 —— 「什么时候走」。
 *
 * ## 为什么要有这个
 *
 * 端上 12 个引擎、9 个相位、一堆评分，回答的全是**买什么**。但真实亏损几乎都发生在
 * 「该走的时候没走」：涨了不肯减，跌了不肯割，一路看着利润回吐完再去接第二刀。
 *
 * 所以这里只做三件机械的事，不做预测、不给目标价：
 *
 * 1. **破位清仓**：收盘跌破最近 N 日最低（不含今日）→ 清仓。这是「跌破最低点清仓」的
 *    可执行版本：低点必须用**不含今天**的历史算，否则今天自己砸出来的新低会把线一起拉下来，
 *    永远触发不了。
 * 2. **回吐过半减仓**：从区间高点回撤超过「高点—起涨点」波幅的一半 → 减仓。
 *    这是「利润回撤一半跑路」在没有持仓成本时的保守代理（起涨点用区间最低收盘近似，
 *    有成本价时才是真利润口径，界面必须写清楚这一点）。
 * 3. **止跌观察**：长下影（下影线占当根振幅 ≥ 50%）说明低位有承接，**只是候选**，
 *    文章的原话是「如果本周收长下引线，才有可能在这个位置止跌」——是「可能」，不是买点。
 *
 * ## 阈值写死
 *
 * 参数是常量而不是可调旋钮：这类规则一旦能调，就会在实际亏损时被调松，纪律也就不存在了。
 * 要改口径就改代码并留测试，别加开关。
 */
object ExitPlan {

    /** 破位回看天数（不含今日）。 */
    const val LOOKBACK = 20

    /** 回吐波幅多少算「回撤一半跑路」。 */
    const val GIVEBACK_HALF = 0.5

    /** 下影线占比达到多少算长下影。 */
    const val LONG_SHADOW = 0.5

    /**
     * 有效波幅门槛：高点—起涨点的波幅占高点不足 5% 时，不启用「回吐一半」。
     *
     * 横盘股波幅只有 1%，止盈线就贴在头上，任何一点回落都会被判减仓——规则一吵，
     * 用户就会把它当噪音关掉，纪律也就没了。所以「没涨过」就不谈「回吐」。
     */
    const val MIN_WAVE = 0.05

    const val ACT_CLEAR = "清仓"
    const val ACT_TRIM = "减仓"
    const val ACT_WATCH = "止跌观察"
    const val ACT_HOLD = "持有"
    const val ACT_INSUFFICIENT = "数据不足"

    data class Result(
        val n: Int,
        /** 清仓线：最近 [LOOKBACK] 日最低价（不含今日）。 */
        val stopLine: Double?,
        /** 现价距清仓线的距离（%），负数表示已跌破。 */
        val stopDistPct: Double?,
        /** 今日收盘已跌破清仓线。 */
        val broken: Boolean,
        /** 区间高点（近 [LOOKBACK] 日，含今日）。 */
        val peak: Double?,
        /** 现价距高点的回撤（%）。 */
        val peakDrawdownPct: Double?,
        /** 跟踪止盈线：高点回吐波幅一半的位置。 */
        val trailLine: Double?,
        /** 现价在近 [LOOKBACK] 日区间的位置（0~1）。 */
        val rangePos: Double?,
        /** 今日下影线占当根振幅的比例（0~1）。 */
        val lowerShadow: Double?,
        /** 今日收盘在当根区间的比例（0~1）。 */
        val closePos: Double?,
        val action: String,
        val reasons: List<String>,
        /** 时间窗提示：距最近显著转折点已过几个交易日（斐波那契 5/8/13/21/34）。只是「可能变盘」。 */
        val timeHint: String? = null,
    )

    /** 斐波那契变盘窗口（交易日）：只是节奏参考，不是买卖信号。 */
    private val FIB = setOf(5, 8, 13, 21, 34)

    /**
     * @param bars 日 K，按日期升序，最后一根是今日（可含盘中未收盘的那根）
     */
    fun compute(bars: List<KBar>, lookback: Int = LOOKBACK): Result {
        if (bars.size < lookback + 1) {
            return Result(
                n = bars.size, stopLine = null, stopDistPct = null, broken = false,
                peak = null, peakDrawdownPct = null, trailLine = null, rangePos = null,
                lowerShadow = null, closePos = null, action = ACT_INSUFFICIENT,
                reasons = listOf("历史仅 ${bars.size} 根，需 ≥${lookback + 1} 根（含今日）"),
                timeHint = null,
            )
        }
        val today = bars.last()
        // 破位线只看「今天之前」：今天自己砸出来的新低不能进分母，否则这条线永远不触发
        val hist = bars.subList(bars.size - 1 - lookback, bars.size - 1)
        val stopLine = hist.minOf { it.low }
        val histLowClose = hist.minOf { it.close }

        // 区间高点含今日：今天冲高回落本身就是我们要判断的形态
        val peak = maxOf(today.high, hist.maxOf { it.high })
        val rangeLow = minOf(today.low, hist.minOf { it.low })
        val cur = today.close

        val stopDistPct = if (stopLine > 0) (cur - stopLine) / stopLine * 100 else null
        val broken = cur < stopLine
        val drawdownPct = if (peak > 0) (peak - cur) / peak * 100 else null
        // 回吐一半：高点 → 起涨点（区间最低收盘）的波幅，回吐超过一半就减
        val span = peak - histLowClose
        val trailLine = if (span > 0 && span / peak >= MIN_WAVE) peak - GIVEBACK_HALF * span else null
        val rangePos = if (peak > rangeLow) (cur - rangeLow) / (peak - rangeLow) else null

        val amp = today.high - today.low
        val lowerShadow = if (amp > 0) (minOf(today.open, today.close) - today.low) / amp else null
        val closePos = if (amp > 0) (today.close - today.low) / amp else null

        // 判定顺序 = 处置力度顺序：先看要不要全走，再看要不要减，最后才谈止跌
        val action: String
        val reasons = mutableListOf<String>()
        when {
            broken -> {
                action = ACT_CLEAR
                reasons += "%s：收盘 %.2f 跌破 %d 日最低 %.2f（%.1f%%）".format(
                    ACT_CLEAR, cur, lookback, stopLine, stopDistPct ?: 0.0,
                )
            }
            trailLine != null && cur < trailLine -> {
                action = ACT_TRIM
                reasons += "%s：距高点 %.2f 回撤 %.1f%%，已回吐过半波幅（止盈线 %.2f）".format(
                    ACT_TRIM, peak, drawdownPct ?: 0.0, trailLine,
                )
            }
            lowerShadow != null && lowerShadow >= LONG_SHADOW && today.close < today.open -> {
                action = ACT_WATCH
                reasons += "%s：今日下影占振幅 %.0f%%，低位有承接；只是止跌**候选**，不是买点".format(
                    ACT_WATCH, lowerShadow * 100,
                )
            }
            else -> {
                action = ACT_HOLD
                reasons += "%s：站在 %d 日最低 %.2f 上方 %.1f%%".format(
                    ACT_HOLD, lookback, stopLine, stopDistPct ?: 0.0,
                )
            }
        }
        reasons += "清仓线 %.2f（%d 日最低，不含今日）".format(stopLine, lookback)
        if (trailLine != null) {
            reasons += "止盈线 %.2f（高点 %.2f 回吐波幅一半，起涨点 %.2f）".format(trailLine, peak, histLowClose)
        }
        rangePos?.let { reasons += "区间位置 %.0f%%（%.0f→%.0f）".format(it * 100, rangeLow, peak) }
        if (bars.size < 60) reasons += "历史 %d 根，未满 60 根，区间仅作近 %d 日参考".format(bars.size, lookback)

        return Result(
            n = bars.size,
            stopLine = stopLine,
            stopDistPct = stopDistPct,
            broken = broken,
            peak = peak,
            peakDrawdownPct = drawdownPct,
            trailLine = trailLine,
            rangePos = rangePos,
            lowerShadow = lowerShadow,
            closePos = closePos,
            action = action,
            reasons = reasons,
            timeHint = timeWindow(bars),
        )
    }

    /**
     * 时间窗：找最近的显著转折（±3 根内的极值），数到今天过了几个交易日。
     *
     * 为什么不是「预测某天见顶」：单边市里时间窗只是**变盘点**，不是「低点接高点」的旧套路。
     * 所以这里只输出「第 N 个交易日」，不输出方向。
     */
    internal fun timeWindow(bars: List<KBar>): String? {
        val w = 3
        if (bars.size < 2 * w + 2) return null
        val last = bars.size - 1
        var lowIdx = -1
        var highIdx = -1
        for (i in last - 1 downTo maxOf(w, bars.size - 60)) {
            if (i - w < 0 || i + w > last) continue
            if (lowIdx < 0 && bars[i].low == bars.slice(i - w..i + w).minOf { it.low }) lowIdx = i
            if (highIdx < 0 && bars[i].high == bars.slice(i - w..i + w).maxOf { it.high }) highIdx = i
            if (lowIdx >= 0 && highIdx >= 0) break
        }
        val idx: Int
        val kind: String
        when {
            lowIdx >= 0 && highIdx >= 0 -> {
                if (lowIdx > highIdx) { idx = lowIdx; kind = "显著低点" } else { idx = highIdx; kind = "显著高点" }
            }
            lowIdx >= 0 -> { idx = lowIdx; kind = "显著低点" }
            highIdx >= 0 -> { idx = highIdx; kind = "显著高点" }
            else -> return "近 60 日无显著转折，时间窗不可得"
        }
        val n = last - idx
        val next = FIB.firstOrNull { it > n } ?: 34
        return "距最近$kind（${bars[idx].date}）已 $n 个交易日" +
            if (n in FIB) "，落在 $n 日斐波那契窗口 → 可能的变盘点（不指示方向）"
            else "，下一窗口 $next 日"
    }
}
