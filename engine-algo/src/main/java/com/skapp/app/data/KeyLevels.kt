package com.skapp.app.data

/**
 * 十关键位引擎 — 端侧版 (2026-10-22)
 * 用户周期假设验证: 从 3 月顶 4141.65 每 100 点台阶, 三个真实台阶底误差 <15 点
 * 5 年触碰统计: 3800/3900 反弹 78-79%, 3600 72%, 3500 58% (最强支撑), 3400 最弱
 * 全本地计算: K 线到手即出, 不依赖云端
 */
object KeyLevels {

    data class Levels(
        val resistance: Double,   // 阻力位: 区间大顶
        val pressure: Double,     // 压力位: 最近 swing 顶
        val bearPressure: Double, // 承压位: 台阶上沿
        val rebound: Double,      // 反弹位: 斐波 0.382
        val chip: Double,         // 筹码位: 近 250 日 VWAP 中枢
        val moneyCost: Double,    // 资金位: 主力成本区
        val pullback: Double,     // 回调位
        val support: Double,      // 支撑位: swing 底/台阶
        val dipBuy: Double,       // 抄底位: 下一台阶
        val trade: String,        // 买卖位分档建议
        val cur: Double,
        val ladderBelow: List<Double>,
        val ladderAbove: List<Double>,
    )

    fun compute(bars: List<KBar>, ladderTop: Double? = null): Levels? {
        if (bars.size < 80) return null
        val highs = bars.map { it.high }
        val lows = bars.map { it.low }
        val closes = bars.map { it.close }
        val vols = bars.map { it.vol }
        val c = closes.last()
        val w = 5

        // swing 顶底
        val tops = mutableListOf<Pair<Int, Double>>()
        val bots = mutableListOf<Pair<Int, Double>>()
        for (i in w until highs.size - w) {
            if (highs[i] == highs.slice(i - w..i + w).max()) tops.add(i to highs[i])
            if (lows[i] == lows.slice(i - w..i + w).min()) bots.add(i to lows[i])
        }

        // 100 点台阶 (指数用 4141.65 顶, 个股用区间大顶)
        val top = ladderTop ?: tops.maxOfOrNull { it.second } ?: return null
        val topRound = kotlin.math.round(top / 100) * 100
        val ladder = (1..8).map { topRound - 100 * it }
        val below = ladder.filter { it < c }.sortedDescending()
        val above = ladder.filter { it > c }

        // 筹码中枢: 近 250 日量加权价格中位
        val n = minOf(250, bars.size)
        val px = (bars.size - n until bars.size).map {
            (bars[it].high + bars[it].low) / 2 to vols[it]
        }
        val sortedByPx = px.sortedBy { it.first }
        val totalV = px.sumOf { it.second }
        var acc = 0.0; var chipPx = sortedByPx.first().first
        for ((p, v) in sortedByPx) { acc += v; if (acc >= totalV / 2) { chipPx = p; break } }

        val topRecent = tops.takeLast(3).maxOfOrNull { it.second } ?: top
        val botRecent = bots.takeLast(3).minOfOrNull { it.second } ?: lows.min()

        val pressure = (tops.map { it.second }.filter { it > c }.minOrNull() ?: above.minOrNull() ?: topRecent)
        val support = (bots.map { it.second }.filter { it < c }.maxOrNull() ?: below.firstOrNull() ?: botRecent)
        val rebound = chipPx + (chipPx - botRecent) * 0.382
        val pullback = chipPx - (topRecent - chipPx) * 0.382

        val trade = when {
            c > pressure -> "突破压力, 突破位${"%.0f".format(pressure)}之上加仓"
            c <= support * 1.01 -> "贴近支撑${"%.0f".format(support)}, 分批抄底区; 破${"%.0f".format((below.firstOrNull() ?: support) - 50)}止损"
            else -> "区间${"%.0f".format(support)}-${"%.0f".format(pressure)} 观望; 抄底参考${below.firstOrNull() ?: support}"
        }

        return Levels(
            resistance = topRecent, pressure = pressure, bearPressure = above.firstOrNull() ?: pressure,
            rebound = rebound, chip = chipPx, moneyCost = chipPx, pullback = pullback,
            support = support, dipBuy = below.firstOrNull() ?: botRecent,
            trade = trade, cur = c, ladderBelow = below, ladderAbove = above)
    }
}
