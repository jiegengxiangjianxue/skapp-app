package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 引擎池里那几块「原以为端上算不了」的部分，本地实时版本。
 *
 * ## 为什么现在能算了
 *
 * A 阶段把 cycle_pool / rps_rotation / strategy_card / ai_pick 全塞进 `missing`，
 * 理由是「需要全市场长周期序列」。重新核对数据后有三块其实端上就有原料：
 *
 * | 缺的 | 原料 | 结论 |
 * |---|---|---|
 * | `rps_rotation` | 东财 clist 的 **f24 60 日涨跌幅 / f25 年初至今**，全市场一次批量 | 能算百分位；20/120/250 日要逐票日 K，仍缺（留 null 并写明，不填 0） |
 * | `strategy_card` | 现成的 `StrategyCard.scan()`（本来就是本地：快照预筛 + 候选逐只日 K 算 pos240） | 直接接上 |
 * | `ai_pick` | LocalScreener 的评分与入选数 | 能给「本地评分精选」，但**不是 LLM**，`basis=local-score` 必须标出来 |
 * | `cycle_pool` | `LocalSectors` 的板块实时强弱 + 成交额占比 | 能给 1 日横截面的板块周期分档；历史周期序列仍缺 |
 *
 * 另外封板率（seal）也能算：f15 最高价 vs 昨收 ×(1+上限) 就是涨停价，
 * 「摸过涨停但没封住」即炸板，`seal = 涨停 / (涨停 + 炸板)`。
 *
 * ## 红线
 *
 * - 算不出的字段一律 null / -1 并写进 note，**绝不用 0 或旧值冒充**。
 * - 本地口径与云端 LLM/长序列口径不同时，字段名沿用但 `basis`/`note` 必须说清来源。
 */
object LiveExtras {

    private fun iso(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))
    private fun day(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

    // ── 封板率（market_phase.seal）────────────────────────────

    data class Seal(val limitUp: Int, val broken: Int, val ratio: Double?)

    /**
     * 封板率 = 涨停 / (涨停 + 炸板)。
     *
     * 炸板靠「最高价摸到涨停价、现价没封住」判，需要 f15 最高价与 f18 昨收；
     * 两者缺失的票不计入分母（宁少算，不把不可得当没炸板）。
     */
    fun seal(rows: List<LiveMarket.Row>): Seal {
        var up = 0
        var broken = 0
        for (r in rows) {
            val lp = r.limitUpPrice ?: continue
            val px = r.price ?: continue
            if (px >= lp - 0.011) up++ else if (r.brokenBoard) broken++
        }
        val denom = up + broken
        return Seal(limitUp = up, broken = broken, ratio = if (denom == 0) null else up.toDouble() / denom)
    }

    // ── RPS（涨幅百分位）────────────────────────────────────

    /**
     * RPS 涨幅百分位：某一票的涨幅在全市场里排到第几百分位（0~100）。
     *
     * 只算有原料的两条：
     * - `rps60` ← f24 60 日涨跌幅；
     * - `rps250` 留 null（≈ 年初至今不等价，不拿 f25 冒充 250 日）。
     *
     * `strongScore` = 60 日百分位 ×0.7 + 当日百分位 ×0.3（当日过热的不许单独拉满）。
     */
    fun rps(
        rows: List<LiveMarket.Row>,
        ts: Long = System.currentTimeMillis(),
        top: Int = 20,
    ): EngineFeed.RpsPayload? {
        val pool60 = rows.mapNotNull { it.chg60d }
        if (pool60.size < 100) return null      // 样本太少，百分位没有意义
        val sorted60 = pool60.sorted()
        val poolDay = rows.mapNotNull { it.pctChg }.sorted()
        if (poolDay.isEmpty()) return null

        fun pct(sortedPool: List<Double>, v: Double): Double {
            var lo = 0
            var hi = sortedPool.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sortedPool[mid] <= v) lo = mid + 1 else hi = mid
            }
            return lo.toDouble() / sortedPool.size * 100.0
        }

        val ranked = rows.mapNotNull { r ->
            val c60 = r.chg60d ?: return@mapNotNull null
            val p60 = pct(sorted60, c60)
            val pDay = r.pctChg?.let { pct(poolDay, it) } ?: 0.0
            Triple(r, p60, p60 * 0.7 + pDay * 0.3)
        }.sortedByDescending { it.third }.take(top)

        val list = ranked.mapIndexed { i, (r, p60, score) ->
            EngineFeed.RpsTop(
                rank = i + 1,
                symbol = r.code,
                strongScore = Math.round(score * 10) / 10.0,
                rps20 = null,
                rps60 = Math.round(p60 * 10) / 10.0,
                rps120 = null,
                rps250 = null,
            )
        }
        return EngineFeed.RpsPayload(
            ts = iso(ts),
            date = day(ts),
            top = list,
            note = "本地实时：rps60 = 全市场 60 日涨幅（东财 f24）百分位，样本 ${pool60.size} 只；" +
                "20/120/250 日需逐票日 K，端上不拉，故留空（不是 0）",
        )
    }

    // ── 周期池（板块周期分档）────────────────────────────────

    /**
     * 板块周期分档（1 日横截面口径）。
     *
     * 判定用「板块涨幅 + 上涨家数占比 + 成交额占比」三条，刻意保守——
     * 只有一天数据时，任何更细的周期结论都是编的：
     * - 主升：涨 > 1.5% 且 上涨占比 > 60%
     * - 修复：涨 > 0 且 上涨占比 > 50%
     * - 退潮：跌 < 0 且 上涨占比 < 50%
     * - 杀跌：跌 < -1.5%
     */
    fun cycle(
        sectors: LocalSectors.Result?,
        ts: Long = System.currentTimeMillis(),
        top: Int = 12,
    ): EngineFeed.CycleMeta? {
        if (sectors == null || sectors.rows.isEmpty()) return null
        val totalHeat = sectors.rows.sumOf { it.heat }.takeIf { it > 0.0 }
        val rows = sectors.rows.take(top).map { s ->
            val upRatio = if (s.n == 0) 0.0 else s.up.toDouble() / s.n
            EngineFeed.CycleRow(
                name = s.name,
                chg1d = s.chg1d?.let { Math.round(it * 100) / 100.0 },
                heatShare = totalHeat?.let { h -> Math.round(s.heat / h * 1000) / 1000.0 },
                nUp = s.up,
                nDown = s.down,
                nStocks = s.n,
                phase = phaseOf(s.chg1d, upRatio),
            )
        }
        return EngineFeed.CycleMeta(
            index = "LOCAL_SECTOR",
            indexName = "本地板块强弱（实时聚合）",
            nDays = 1,
            spanStart = day(ts),
            spanEnd = day(ts),
            note = "端上按当日横截面分档（涨幅+上涨占比+成交额占比）；历史周期序列（20/60 日轮动节奏）本地不可得，" +
                "所以这是「今天站在哪一档」，不是「周期走到第几天」",
            rows = rows,
        )
    }

    private fun phaseOf(chg: Double?, upRatio: Double): String = when {
        chg == null -> "不可得"
        chg > 1.5 && upRatio > 0.6 -> "主升"
        chg > 0 && upRatio > 0.5 -> "修复"
        chg < -1.5 -> "杀跌"
        chg < 0 && upRatio < 0.5 -> "退潮"
        else -> "震荡"
    }

    // ── 策略卡 ────────────────────────────────────────────

    /** StrategyCard 本来就是本地算（快照预筛 + 候选逐只日 K 算 pos240），这里只做结构转换。 */
    suspend fun strategy(limit: Int = 12): EngineFeed.StrategyCard? = withContext(Dispatchers.IO) {
        val r = runCatching { StrategyCard.scan(limit) }.getOrNull() ?: return@withContext null
        EngineFeed.StrategyCard(
            ts = iso(r.timestamp),
            strategy = r.strategy,
            tradeOk = r.tradeOk,
            candidates = r.candidates.map { c ->
                EngineFeed.StrategyCandidate(
                    symbol = c.symbol,
                    close = c.close,
                    retPct = c.retPct,
                    vratio = c.volRatio,
                    pos240 = c.pos240 ?: 0.0,
                )
            },
            note = "本地实时：全球闸门 ${String.format(Locale.CHINA, "%.0f", r.globalScore)}（> -15 才进）；" +
                "回测口径 5日胜率 ${String.format(Locale.CHINA, "%.1f%%", r.backtest.winRate5d)}" +
                "（基线 ${String.format(Locale.CHINA, "%.1f%%", r.backtest.baseline5d)}，样本 ${r.backtest.n}）",
        )
    }

    // ── AI 精选（本地评分，不是 LLM）──────────────────────────

    fun aiPick(
        snapshot: List<LiveMarket.Row>,
        screened: List<LocalScreener.Screened>,
        ts: Long = System.currentTimeMillis(),
    ): EngineFeed.AiPick = EngineFeed.AiPick(
        ts = iso(ts),
        nScanned = snapshot.size,
        nCandidates = screened.count { (it.centralScore ?: 0.0) > 0.0 },
        basis = "local-score",
        note = "端上评分规则（12 引擎共振 + 相位闸门）选出的候选数，**不是 LLM 结论**；" +
            "云端 ai_pick_v4 是 LLM 归档，两者口径不同，不可直接比较",
    )
}
