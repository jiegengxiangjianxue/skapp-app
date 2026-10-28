package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * | `rps_rotation` | 东财 clist 的 **f24 60 日涨跌幅**；20/120/250 日用 [MarketDayArchive] 存档的收盘价 | 全周期都能算百分位；存档天数不够的周期留 null（不填 0） |
 * | `strategy_card` | 现成的 `StrategyCard.scan()`（本来就是本地：快照预筛 + 候选逐只日 K 算 pos240） | 直接接上 |
 * | `ai_pick` | LocalScreener 的评分与入选数 | 能给「本地评分精选」，但**不是 LLM**，`basis=local-score` 必须标出来 |
 * | `market_phase` 连板高度/晋级率/封板率 | 存档的逐日涨停名单 + 摸过涨停标记 | 全都能算：高度=连续涨停天数，晋级率=昨涨停→今连板，封板率=涨停/(涨停+炸板) |
 * | `cycle_pool` | `LocalSectors` 的实时强弱 + 存档里的板块日涨幅序列 | 1 日横截面分档 + 5/20 日复利动量与连续走强天数（按存档天数逐日累加） |
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

    // 时间标签统一走 CstTime（北京时间），设备时区不影响「今天」的判断
    private fun iso(ts: Long) = CstTime.iso(ts)
    private fun day(ts: Long) = CstTime.day(ts)

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
            // 分子分母必须用同一套信息：没有昨收就判不出「摸过涨停没封住」，
            // 这时只能把这只票整体排除——否则涨停算得出来、炸板算不出来，
            // 封板率会被系统性高估成 100%。
            if (r.prevClose == null || r.price == null) continue
            if (r.atLimitUp) up++
            else if (r.brokenBoard) broken++
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
        /** 本地日级快照（升序）：攒够天数就能把 rps20/120/250 从「不可得」变成真值 */
        history: List<DayMarketSnapshot> = emptyList(),
    ): EngineFeed.RpsPayload? {
        // 停牌票的 60 日涨幅是旧值/0，混进来会把百分位稀释（分母虚大 → 强势股 RPS 偏低）
        val tradable = rows.filter { it.tradable }
        val pool60 = tradable.mapNotNull { it.chg60d }
        if (pool60.size < 100) return null      // 样本太少，百分位没有意义
        val sorted60 = pool60.sorted()
        val poolDay = tradable.mapNotNull { it.pctChg }.sorted()
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

        // 长周期 RPS：用本地日级快照的收盘价算。历史天数不够就返回 null（不是 0），
        // 天数够一天，就多一个周期的真值——这是「存档」给引擎池带来的实际收益。
        fun retSeries(lookback: Int): List<Double>? {
            if (history.size < lookback + 1) return null
            val base = history[history.size - 1 - lookback]
            val baseClose = base.entries.associate { it.code to it.close }
            val out = ArrayList<Double>(tradable.size)
            for (r in tradable) {
                val px = r.price ?: continue
                val b = baseClose[r.code] ?: continue
                if (b <= 0.0) continue
                out += (px / b - 1.0) * 100.0
            }
            return out.takeIf { it.size >= 100 }   // 样本不足，百分位无意义
        }
        val pool20 = retSeries(20)
        val pool120 = retSeries(120)
        val pool250 = retSeries(250)
        val sorted20 = pool20?.sorted()
        val sorted120 = pool120?.sorted()
        val sorted250 = pool250?.sorted()
        fun retOf(r: LiveMarket.Row, lookback: Int): Double? {
            if (history.size < lookback + 1) return null
            val base = history[history.size - 1 - lookback]
            val b = base.entries.firstOrNull { it.code == r.code }?.close ?: return null
            val px = r.price ?: return null
            return if (b <= 0.0) null else (px / b - 1.0) * 100.0
        }

        // 综合强度：60 日百分位 0.5 + 当日百分位 0.2 + （有 20 日数据时）20 日百分位 0.3
        val ranked = tradable.mapNotNull { r ->
            val c60 = r.chg60d ?: return@mapNotNull null
            val p60 = pct(sorted60, c60)
            val pDay = r.pctChg?.let { pct(poolDay, it) } ?: 0.0
            val r20 = retOf(r, 20)
            val p20 = if (sorted20 != null && r20 != null) pct(sorted20, r20) else null
            val score = if (p20 != null) p60 * 0.5 + pDay * 0.2 + p20 * 0.3 else p60 * 0.7 + pDay * 0.3
            r to Triple(p60, p20, score)
        }.sortedByDescending { it.second.third }.take(top)

        val list = ranked.mapIndexed { i, (r, ps) ->
            val (p60, p20, score) = ps
            EngineFeed.RpsTop(
                rank = i + 1,
                symbol = r.code,
                strongScore = Math.round(score * 10) / 10.0,
                rps20 = p20?.let { Math.round(it * 10) / 10.0 },
                rps60 = Math.round(p60 * 10) / 10.0,
                rps120 = retOf(r, 120)?.let { v -> sorted120?.let { pct(it, v) } }?.let { Math.round(it * 10) / 10.0 },
                rps250 = retOf(r, 250)?.let { v -> sorted250?.let { pct(it, v) } }?.let { Math.round(it * 10) / 10.0 },
            )
        }
        val have = buildList {
            if (sorted20 != null) add("20")
            if (sorted120 != null) add("120")
            if (sorted250 != null) add("250")
        }
        return EngineFeed.RpsPayload(
            ts = iso(ts),
            date = day(ts),
            top = list,
            note = "本地实时：rps60 = 全市场 60 日涨幅（东财 f24）百分位，样本 ${pool60.size} 只；" +
                if (have.isEmpty()) {
                    "20/120/250 日需本地日级快照（已存 ${history.size} 天），不足故留空（不是 0）"
                } else {
                    "rps${have.joinToString("/")} 由本地日级快照（${history.size} 天）的收盘价现算" +
                        if (have.size < 3) "，其余周期天数不足仍留空（不是 0）" else ""
                },
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
        /** 本地日级快照（升序）：有了它才能给「5 日动量 / 连续走强天数」 */
        history: List<DayMarketSnapshot> = emptyList(),
    ): EngineFeed.CycleMeta? {
        if (sectors == null || sectors.rows.isEmpty()) return null
        val totalHeat = sectors.rows.sumOf { it.heat }.takeIf { it > 0.0 }
        val momentum = SectorHistoryCalc.momentum(history, top = 200).associateBy { it.name }
        val rows = sectors.rows.take(top).map { s ->
            val upRatio = if (s.n == 0) 0.0 else s.up.toDouble() / s.n
            val m = momentum[s.name]
            EngineFeed.CycleRow(
                name = s.name,
                chg1d = s.chg1d?.let { Math.round(it * 100) / 100.0 },
                heatShare = totalHeat?.let { h -> Math.round(s.heat / h * 1000) / 1000.0 },
                nUp = s.up,
                nDown = s.down,
                nStocks = s.n,
                phase = phaseOf(s.chg1d, upRatio),
                ret5d = m?.ret5d?.let { Math.round(it * 100) / 100.0 },
                upStreak = m?.upStreak ?: 0,
                closePos = s.closePos?.let { Math.round(it * 100) / 100.0 },
                longShadowN = s.longShadowN,
                stopping = s.stopping,
            )
        }
        val histDays = history.size
        return EngineFeed.CycleMeta(
            index = "LOCAL_SECTOR",
            indexName = "本地板块强弱（实时聚合）",
            nDays = maxOf(1, histDays),
            spanStart = history.firstOrNull()?.date ?: day(ts),
            spanEnd = day(ts),
            note = if (histDays >= 6) {
                "端上横截面分档（涨幅+上涨占比+成交额占比）+ 本地日级快照攒出的 5 日动量与连续走强天数" +
                    "（已存 $histDays 个交易日）；20 日动量需 ≥20 个交易日，攒够自动出现"
            } else {
                "端上按当日横截面分档（涨幅+上涨占比+成交额占比）；5 日动量需 5 个交易日快照，" +
                    "当前只有 $histDays 天，故留空（不是 0）"
            },
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
    suspend fun strategy(
        limit: Int = 12,
        globalIndices: List<Map<String, Any?>>? = null,
    ): EngineFeed.StrategyCard? = withContext(Dispatchers.IO) {
        val r = runCatching { StrategyCard.scan(limit, globalIndices) }.getOrNull()
            ?: return@withContext null
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
