package com.skapp.app.data

/**
 * 仓位上限 —— 「今天该不该做」。
 *
 * ## 为什么不能是一条 phaseScore 直线
 *
 * 之前仓位上限是 `0.8 ×(相位分+100)/200` 的一条直线：相位再差也还剩 2 成仓，相位再好也
 * 给满 8 成。这条线没有「停手」这个档位，也不看情绪**趋势**——半导体板块从 3 板高度掉到
 * 1 板、晋级率 60% 掉到 25%，市场其实已经换了一个世界，可直线只反映当天的横截面强度。
 *
 * 这里改成两层：
 *
 * 1. **硬闸门**（任一命中 → 上限直接砍到 2 成以下）：宽度塌了、跌停比涨停多、
 *    连板高度掉档且晋级率崩了、相位极度弱势。这一层回答「今天该不该出门」。
 * 2. **加权分**（宽度 40% + 相位 30% + 情绪趋势 30%）→ 0~80%。这一层回答「出门带多少」。
 *
 * 输出永远附带**逐条带数值的理由**：用户要知道自己被砍到什么仓位是因为哪一条，
 * 而不是看着一个 0.35 猜。
 */
object PositionCap {

    /** 硬闸门命中后的上限天花板。 */
    const val GATE_CAP = 0.2

    /** 正常上限（永不满仓：A 股单边下跌极快，留仓就是留活路）。 */
    const val MAX_CAP = 0.8

    const val LABEL_EMPTY = "空仓观望"
    const val LABEL_LIGHT = "轻仓"
    const val LABEL_HALF = "半仓"
    const val LABEL_HEAVY = "可进攻"

    data class Result(
        val cap: Double,
        val label: String,
        /** 命中「今天别做」的硬闸门（空表示没命中）。 */
        val gates: List<String>,
        /** 逐条带数值的理由，界面直接展示。 */
        val reasons: List<String>,
    )

    /**
     * @param breadth     上涨家数占涨跌总数比例（0~1）；null=不可得
     * @param phaseScore  相位分（-100~100）
     * @param limitUp     涨停家数
     * @param limitDown   跌停家数
     * @param heightToday 今日连板高度；null/-1=不可得
     * @param heightPrev  前几个交易日的连板高度（退潮判断用）；null=不可得
     * @param promo       晋级率（0~1）；null=不可得
     * @param sectorRet5d 板块 5 日动量中位数（%）；null=不可得（存档不足 5 天）
     * @param volScale    波动率目标闸门系数（0.2~1.0，[VolTargetGate]）；null=不可得，不参与
     */
    fun compute(
        breadth: Double?,
        phaseScore: Double,
        limitUp: Int,
        limitDown: Int,
        heightToday: Int? = null,
        heightPrev: Int? = null,
        promo: Double? = null,
        sectorRet5d: Double? = null,
        volScale: Double? = null,
    ): Result {
        val gates = mutableListOf<String>()

        // 闸门 1：宽度塌 + 跌停不比涨停少 —— 全市场在往下躺
        if (breadth != null && breadth < 0.35 && limitDown >= limitUp) {
            gates += "宽度 %.0f%% 且跌停 %d ≥ 涨停 %d".format(breadth * 100, limitDown, limitUp)
        }
        // 闸门 2：连板高度掉档 + 晋级率崩 —— 情绪退潮，接力盘在撤退
        val h = heightToday?.takeIf { it > 0 }
        val hp = heightPrev?.takeIf { it > 0 }
        if (h != null && hp != null && hp - h >= 2 && (promo ?: 1.0) < 0.30) {
            gates += "连板高度 %d→%d 板且晋级率 %.0f%%".format(hp, h, (promo ?: 0.0) * 100)
        }
        // 闸门 3：相位极度弱势（跌停潮级别的横截面）
        if (phaseScore <= -60) gates += "相位分 %.0f（极度弱势）".format(phaseScore)

        // 加权：宽度最实在（全市场的行为），相位是横截面合成，情绪趋势看是否在恶化
        val breadthTerm = ((breadth ?: 0.5) - 0.5) * 1.6 + 0.5   // 0.5→0.5, 0.3→0.18, 0.7→0.82
        val phaseTerm = (phaseScore + 100) / 200                 // -100→0, 0→0.5, 100→1
        val trendTerm = when {
            h == null || hp == null -> 0.5                        // 不可得就取中性，不猜
            h > hp -> 0.75
            h == hp -> 0.5
            hp - h == 1 -> 0.35
            else -> 0.15
        }
        val raw = 0.4 * breadthTerm.coerceIn(0.0, 1.0) + 0.3 * phaseTerm.coerceIn(0.0, 1.0) + 0.3 * trendTerm
        var cap = (raw * MAX_CAP).coerceIn(0.0, MAX_CAP)
        if (gates.isNotEmpty()) cap = minOf(cap, GATE_CAP)

        val reasons = mutableListOf<String>()
        reasons += "宽度 %s · 相位 %.0f · 涨停 %d / 跌停 %d".format(
            breadth?.let { "%.0f%%".format(it * 100) } ?: "不可得", phaseScore, limitUp, limitDown,
        )
        reasons += "加权：宽度 %.2f×0.4 + 相位 %.2f×0.3 + 情绪趋势 %.2f×0.3 → 上限 %.0f%%".format(
            breadthTerm.coerceIn(0.0, 1.0), phaseTerm.coerceIn(0.0, 1.0), trendTerm, cap * 100,
        )
        if (h != null && hp != null) {
            reasons += "连板高度 %d → %d 板，晋级率 %s".format(
                hp, h, promo?.let { "%.0f%%".format(it * 100) } ?: "不可得",
            )
        } else {
            reasons += "连板高度：不可得（本地存档不足 2 天），趋势项取中性 0.5"
        }
        sectorRet5d?.let { reasons += "板块 5 日动量中位数 %+.2f%%".format(it) }
        // 波动率闸门：只降不升，且不改变硬闸门已经定下的天花板高低（取 min）
        if (volScale != null) {
            val before = cap
            cap = (cap * volScale.coerceIn(0.0, 1.0)).coerceIn(0.0, MAX_CAP)
            if (cap < before) {
                reasons += "波动率闸门：系数 %.2f → 上限 %.0f%% → %.0f%%".format(
                    volScale, before * 100, cap * 100,
                )
            }
        }
        // 注意：闸门文案里自带 %（如「宽度 22%」），这里必须走 %s 参数，不能把它拼回格式串
        gates.forEach { g -> reasons += "闸门命中：%s → 上限压到 %.0f%%".format(g, GATE_CAP * 100) }

        val label = when {
            // 只看 cap 本身：旧写法额外要求 gates.isNotEmpty()，
            // 于是「极度弱势但三个闸门刚好都没踩中」的 cap=0.05 会显示成「轻仓」——
            // 上限只剩 5% 却说轻仓，与实际可执行仓位不是一个意思。
            cap <= 0.1 -> LABEL_EMPTY
            cap <= 0.25 -> LABEL_LIGHT
            cap <= 0.55 -> LABEL_HALF
            else -> LABEL_HEAVY
        }
        return Result(cap = cap, label = label, gates = gates, reasons = reasons)
    }
}
