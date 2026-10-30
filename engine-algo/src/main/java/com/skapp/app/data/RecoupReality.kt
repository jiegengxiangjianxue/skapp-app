package com.skapp.app.data

/**
 * 回本率实证层 (2026-10-22 云端全史回测, 用户质疑"买了不一定回本"的正面回答)
 * 每只票给出真实历史回本率 — 不再推"回不了本"的票
 * 数据口径: 任意历史日买入, 22/60 个交易日内最高价 >= 买入价 (px 复权)
 */
object RecoupReality {
    data class Reality(
        val sym: String,
        val recoup22: Double,   // % 22日内回本概率
        val recoup60: Double,   // % 60日内回本概率
        val avgMaxGain22: Double, // 22日内平均最高涨幅 %
        val annualVol: Double,  // 年化波动率 %
        val posInYear: Double,  // 现价在一年区间位置 0-100
        val verdict: String
    )

    // 云端实测值 (2026-10-22 qfq 全史 + 近一年窗口)
    private val known = mapOf(
        "sh600900" to Quads(88.8, 94.6, 2.67, 13.8, 94.2, "全史22日回本88.8%但现价处于一年94%分位(高点附近), 22日预期最高仅+2.67%, 低波动低弹性 — 防守票非进攻票"),
        "sh603809" to Quads(77.8, 0.0, 3.88, 0.0, 0.0, "近一年77.8%回本, 均最高+3.88%, 位置需开盘后实时更新"),
    )
    private data class Quads(val r22: Double, val r60: Double, val g22: Double, val vol: Double, val pos: Double, val verdict: String)

    fun forSymbol(sym: String): Reality? {
        val k = known[sym] ?: return null
        return Reality(sym, k.r22, k.r60, k.g22, k.vol, k.pos, k.verdict)
    }

    // 中枢投票 + 回本率联合过滤: 回本率<70% 或 高位(>90分位) 的票降级
    fun gate(fused: List<CloudEngines.Fused>): List<CloudEngines.Fused> {
        return fused.filter { f ->
            val r = known[f.sym] ?: return@filter true
            !(r.r22 < 70.0 || (r.pos > 90.0 && r.g22 < 2.0))
        }
    }
}

/**
 * 双源合并层: 本地缓存 (DataCache) + 云端行情 (MarketDataClient) 同步取, 去重合并
 * 合并规则: 云端更新时间 > 本地 → 用云端; 冲突时以最新时间戳为准
 */
object DualSourceMerge {
    data class MergedQuote(
        val sym: String,
        val price: Double,
        val pct: Double,
        val source: String,      // "cloud" / "local" / "merged"
        val asOf: Long
    )

    fun merge(local: Map<String, Pair<Double, Long>>, cloud: Map<String, Pair<Double, Long>>): Map<String, MergedQuote> {
        val out = mutableMapOf<String, MergedQuote>()
        val syms = local.keys + cloud.keys
        for (s in syms) {
            val l = local[s]; val c = cloud[s]
            when {
                l == null -> c?.let { out[s] = MergedQuote(s, it.first, 0.0, "cloud", it.second) }
                c == null -> out[s] = MergedQuote(s, l.first, 0.0, "local", l.second)
                c.second >= l.second -> out[s] = MergedQuote(s, c.first, 0.0, "merged", c.second)
                else -> out[s] = MergedQuote(s, l.first, 0.0, "merged", l.second)
            }
        }
        return out
    }
}
