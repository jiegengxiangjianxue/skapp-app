package com.skapp.app.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 波动率目标闸门（端上版，参数取自云端回测校准）。
 *
 * ## 它解决什么
 *
 * [PositionCap] 只看宽度/相位/情绪趋势，**完全不看波动**：同样「宽度 55%、相位 20」的两种行情，
 * 一种是年化波动 12% 的温和上行，另一种是 35% 的高波动震荡，给同一个仓位上限。
 * 波动大的时候同样的仓位实际风险敞口是两倍，这是仓位管理里最廉价的一条改进。
 *
 * ## 公式（与云端 `risk_budget.py` 的 vol_scale 同一条，只是喂给它的是端上自算的收益序列）
 *
 *     realized = stdev(最近 window 个交易日全市场平均涨幅) × sqrt(252)
 *     scale    = clamp(vol_target / realized, floor, 1.0)
 *     cap'     = cap × scale
 *
 * `floor` 与上限 1.0 一起构成「只降不升」：波动率低时不加杠杆，只把高波动期压下来。
 *
 * ## 参数来源（不是经验值）
 *
 * `vol_target=0.12 / max_pos=0.8 / window=60` 由 `scripts/calibrate_risk_regime.py` 在
 * 5 个指数 500 个交易日（2024-10-08 ~ 2026-10-28）上网格搜索 + 时间三折得来，
 * 样本外最大回撤 7.0%（满仓 20.2%）。参数漂移由 `VolTargetParityTest` 对着
 * `testdata/parity/golden_v2.json` 的 `vol_target.params` 卡住。
 *
 * ## 不可得就明说
 *
 * 样本不足（本地存档不够 window/2 天）时 `scale=1.0` 且 `source=insufficient`，
 * 不拿「中性 0.5」冒充——压低仓位与不压是两件事，不能靠猜。
 */
object VolTargetGate {

    const val VERSION = 1

    data class Params(
        val volTargetAnnual: Double = 0.12,
        val window: Int = 60,
        val floor: Double = 0.2,
        val maxPos: Double = 0.8,
    )

    data class Result(
        val scale: Double,
        val realizedVol: Double?,
        val sampleDays: Int,
        /** ok / insufficient */
        val status: String,
        val reasons: List<String>,
    )

    fun compute(dailyReturns: List<Double>, p: Params = Params()): Result {
        val usable = dailyReturns.filter { it.isFinite() }
        val minNeed = maxOf(20, p.window / 2)
        if (usable.size < minNeed) {
            return Result(
                scale = 1.0, realizedVol = null, sampleDays = usable.size, status = "insufficient",
                reasons = listOf("样本 ${usable.size} 天 < 需要 $minNeed 天，波动率闸门不生效（不拿中性值冒充）"),
            )
        }
        val win = usable.takeLast(p.window)
        val mean = win.average()
        val varSum = win.sumOf { (it - mean) * (it - mean) } / win.size
        val realized = sqrt(varSum) * sqrt(252.0)
        if (!(realized > 0.0)) {
            return Result(
                scale = 1.0, realizedVol = realized, sampleDays = win.size, status = "ok",
                reasons = listOf("已实现波动率 ≈ 0，闸门不压仓"),
            )
        }
        val scale = (p.volTargetAnnual / realized).coerceIn(p.floor, 1.0)
        val reasons = mutableListOf<String>()
        reasons += "年化波动 %.1f%% / 目标 %.1f%% → 仓位系数 %.2f".format(realized * 100, p.volTargetAnnual * 100, scale)
        if (scale < 1.0) {
            reasons += "高波动期按比例压仓（只降不升：波动低时不加杠杆）"
        } else {
            reasons += "波动低于目标，闸门不压仓"
        }
        return Result(scale = scale, realizedVol = realized, sampleDays = win.size, status = "ok", reasons = reasons)
    }

    /** 从「每日全市场平均涨幅」序列里算，端上存档只需 code→pct 一列，不需要指数日线。 */
    fun dailyMarketReturns(rowsByDay: List<List<Double?>>): List<Double> =
        rowsByDay.mapNotNull { day ->
            val vals = day.filterNotNull().filter { abs(it).isFinite() }
            if (vals.isEmpty()) null else vals.average()
        }
}
