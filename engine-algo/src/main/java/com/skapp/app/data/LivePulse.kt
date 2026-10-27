package com.skapp.app.data

/**
 * 实时市场脉搏：只用盘中真实盘口（[LiveMarket.Row]）算出来的整体状态。
 *
 * ## 为什么要它
 *
 * 原来「今天市场怎么样」是读云端 `market_phase` / `api/market` 的产物——收盘后 CI 算一次，
 * 盘中打开看到的可能是几小时前、甚至昨天的结论。用户要求本地实时分析，所以这里把
 * 能实时算的都实时算：涨跌家数、涨跌停、中位涨幅、成交额、主力净额、资金榜。
 *
 * ## 只算能算准的
 *
 * 云端 market_phase 里还有 `height`（连板高度）/`promo`（晋级率）/`seal`（封板率）三个字段，
 * 需要历史涨停序列与封单额，实时接口拿不到准确值。**这里不猜**：不给就是不给，
 * 上层显示「本地不可得」，由云端历史库补（属数据库职责，不是分析输入）。
 */
data class LivePulse(
    val ts: Long,
    val total: Int,
    val up: Int,
    val down: Int,
    val flat: Int,
    val limitUp: Int,
    val limitDown: Int,
    val medianPct: Double?,
    /** 上涨占比 up/(up+down)，0..1；无涨跌样本时为 null */
    val breadth: Double?,
    val totalAmount: Double?,
    val mainNetTotal: Double?,
    /** -100..100，正=风险偏好上行 */
    val phaseScore: Double,
    /** 强势 / 偏强 / 平衡 / 偏弱 / 弱势 / 无数据 */
    val label: String,
    val inflow: List<FlowRow>,
    val outflow: List<FlowRow>,
    /** 停牌/无成交家数：不进涨跌平统计，单独计数，免得被当成「平盘」稀释情绪 */
    val suspended: Int = 0,
) {
    data class FlowRow(
        val code: String,
        val name: String?,
        val mainNet: Double,
        val pctChg: Double?,
    )

    companion object {

        fun of(rows: List<LiveMarket.Row>, topN: Int = 10): LivePulse {
            val ts = rows.maxOfOrNull { it.ts } ?: System.currentTimeMillis()
            // 停牌票 clist 里也有行、涨跌幅写 0，直接统计会把平盘虚增、中位涨幅拉向 0、
            // 板块均值被稀释。统计口径一律只在「有价有量」的票上做。
            val traded = rows.filter { it.tradable }
            val suspended = rows.size - traded.size
            val chgs = traded.mapNotNull { it.pctChg }
            val up = chgs.count { it > 0.01 }
            val down = chgs.count { it < -0.01 }
            val flat = chgs.size - up - down
            val limitUp = rows.count { it.atLimitUp }
            val limitDown = rows.count { it.atLimitDown }
            val breadth = if (up + down > 0) up.toDouble() / (up + down) else null
            val median = medianOf(chgs)
            val amount = rows.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.sum()
            val mainTotal = rows.mapNotNull { it.mainNet }.takeIf { it.isNotEmpty() }?.sum()

            val score = if (traded.isEmpty()) 0.0 else phaseScoreOf(breadth, median, limitUp, limitDown)
            val label = when {
                traded.isEmpty() -> "无数据"
                score >= 40 -> "强势"
                score >= 15 -> "偏强"
                score > -15 -> "平衡"
                score > -40 -> "偏弱"
                else -> "弱势"
            }
            val withFlow = rows.filter { it.mainNet != null }
            val inflow = withFlow.sortedByDescending { it.mainNet ?: 0.0 }
                .take(topN).map { FlowRow(it.code, it.name, it.mainNet ?: 0.0, it.pctChg) }
            val outflow = withFlow.sortedBy { it.mainNet ?: 0.0 }
                .take(topN).map { FlowRow(it.code, it.name, it.mainNet ?: 0.0, it.pctChg) }

            return LivePulse(
                ts = ts,
                total = rows.size,
                up = up,
                down = down,
                flat = flat,
                limitUp = limitUp,
                limitDown = limitDown,
                medianPct = median,
                breadth = breadth,
                totalAmount = amount,
                mainNetTotal = mainTotal,
                phaseScore = score,
                label = label,
                inflow = inflow,
                outflow = outflow,
                suspended = suspended,
            )
        }

        /**
         * 相位分数，三项相加后夹在 -100..100：
         *   1. 宽度项：(上涨占比 - 0.5) × 120      → ±60，涨跌家数是最朴素的情绪秤
         *   2. 幅度项：中位涨幅 × 8，夹 ±20        → 用中位数而不是均值，避免一只妖股带偏全市场
         *   3. 涨停项：涨停×1.2（上限 20）减 跌停×2（上限 20）
         *      跌停权重翻倍——同样的家数，跌停对情绪的杀伤大于涨停的提振。
         */
        private fun phaseScoreOf(
            breadth: Double?,
            medianPct: Double?,
            limitUp: Int,
            limitDown: Int,
        ): Double {
            val breadthTerm = ((breadth ?: 0.5) - 0.5) * 120
            val medianTerm = ((medianPct ?: 0.0) * 8).coerceIn(-20.0, 20.0)
            val limitTerm = (limitUp * 1.2).coerceAtMost(20.0) - (limitDown * 2.0).coerceAtMost(20.0)
            return (breadthTerm + medianTerm + limitTerm).coerceIn(-100.0, 100.0)
        }

        private fun medianOf(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        }
    }
}
