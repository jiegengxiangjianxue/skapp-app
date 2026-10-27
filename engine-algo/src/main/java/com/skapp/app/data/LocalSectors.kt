package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 板块强弱 / 看板的**本地实时**版本。
 *
 * ## 为什么能端上算
 *
 * 板块强弱本质是「成分股涨跌幅的加权聚合」。原先这一步在云端 CI 跑，因为要全市场行情 +
 * 个股→板块映射。现在两样东西端上都有了：
 *
 * - `LiveMarket.snapshot()`：全市场实时盘口（缓存内，clist 约 11 页）
 * - `SectorLink.load()`：库里的 `stock_sector_map` / `industry_map_em` 映射表
 *
 * 于是聚合可以在端上做，且**是真实时**：板块涨幅随行情变，不是昨天的归档。
 *
 * ## 口径红线
 *
 * - 聚合只算映射覆盖到的股票，未映射的进「未知」，覆盖率会一起返回，不许把未知算进板块。
 * - `chg5d` / 板块 20 日动量这类需要历史序列的字段本地给不了 → 留空（不是 0）。
 * - 涨停判定按各板块涨跌幅上限（主板 10%、创业/科创 20%、北交所 30%），不一律用 9.6%。
 */
object LocalSectors {

    /** 聚合成一行板块。 */
    data class Row(
        val name: String,
        val n: Int,
        val chg1d: Double?,
        val heat: Double,      // 成交额合计（元）
        val up: Int,
        val down: Int,
        val limitUp: Int,
        val limitDown: Int = 0,
        val leader: String?,
        val leaderPct: Double?,
    )

    data class Result(
        val rows: List<Row>,
        val mapped: Int,
        val total: Int,
        val unmapped: Int,
        val ts: String,
    ) {
        val coverage: Double get() = if (total == 0) 0.0 else mapped.toDouble() / total
    }

    /**
     * 现算板块强弱。
     *
     * @return null = 映射表或行情拿不到（调用方应回退到 [Db.sectors] 归档并标明「回看」）
     */
    /**
     * @param snapshotIn 已经拉到的全市场快照；心跳/引擎池里传进来可省一次取数，
     *        也保证同一轮里脉搏、板块、选股看到的是**同一个时点**。
     */
    suspend fun compute(
        force: Boolean = false,
        snapshotIn: List<LiveMarket.Row>? = null,
    ): Result? = withContext(Dispatchers.IO) {
        val rows = snapshotIn ?: runCatching { LiveMarket.snapshot(force) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext null
        val board = runCatching { SectorLink.load(force) }.getOrNull() ?: return@withContext null
        aggregate(rows, sectorOf = { board.sectorNameOf(it) })
    }

    /**
     * 纯聚合：给一批快照行 + 「代码→板块」查表函数，算出各板块的实时强弱。
     *
     * 抽成纯函数是为了能测——这里的错都很难在界面上看出来：把未映射的股票塞进
     * 「未知」板块会凭空多出一个假板块；涨停判定如果一律用 9.6%，创业板 20% 的涨停
     * 就永远统计不到；平均涨幅若把 pctChg 为空的停牌股算成 0，板块会被系统性拉低。
     */
    fun aggregate(rows: List<LiveMarket.Row>, sectorOf: (String) -> String?, ts: String = ""): Result {
        var mapped = 0
        var unmapped = 0
        val agg = LinkedHashMap<String, MutableAgg>()
        for (r in rows) {
            val sector = sectorOf(r.code)
            if (sector.isNullOrBlank()) {
                unmapped++
                continue
            }
            mapped++
            agg.getOrPut(sector) { MutableAgg(sector) }.add(r)
        }
        return Result(
            rows = agg.values.map { it.toRow() }.sortedByDescending { it.heat },
            mapped = mapped,
            total = rows.size,
            unmapped = unmapped,
            ts = ts.ifBlank {
                CstTime.iso()
            },
        )
    }

    /** 看板页的 board 结构（与云端 latest.json 同形，界面不用改）。 */
    fun boardMap(res: Result?, pulse: LivePulse?, snapshot: List<LiveMarket.Row> = emptyList()): Map<String, Any?> {
        if (res == null || pulse == null) {
            return mapOf(
                "status" to "insufficient",
                "reason" to "本地实时行情或板块映射不可达（未使用云端归档冒充）",
                "source" to "local",
            )
        }
        val ts = res.ts
        fun row(r: LiveMarket.Row) = mapOf(
            "code" to r.code,
            "name" to r.name,
            "price" to r.price,
            "pctChg" to r.pctChg,
        )
        val byPct = snapshot.filter { it.pctChg != null }
        // 涨停判定统一走 Row.atLimitUp（有昨收按价判，无昨收才退回涨幅近似），
        // 不再各处自己写 "涨幅 ≥ 上限 − 0.3" —— 那个 0.3 的余量在各板上不一致，
        // 30cm 的北交所会漏、10cm 的会把 +9.7% 误判成涨停。
        val limitUp = byPct.filter { it.atLimitUp }
        val limitDown = byPct.filter { it.atLimitDown }
        return linkedMapOf(
            "status" to "ok",
            "source" to "local",
            "ts" to ts,
            "market" to mapOf(
                "涨" to pulse.up,
                "跌" to pulse.down,
                "平" to pulse.flat,
                "涨停" to pulse.limitUp,
                "跌停" to pulse.limitDown,
                "中位涨幅" to pulse.medianPct,
                "成交额(亿)" to (pulse.totalAmount ?: 0.0) / 1e8,
                "主力净额(亿)" to (pulse.mainNetTotal ?: 0.0) / 1e8,
            ),
            "phase" to mapOf("label" to pulse.label, "score" to pulse.phaseScore),
            "indices" to emptyList<Map<String, Any?>>(),   // 指数另走 MarketDataClient，不在这里编
            "gainers" to byPct.sortedByDescending { it.pctChg }.take(20).map { row(it) },
            "losers" to byPct.sortedBy { it.pctChg }.take(20).map { row(it) },
            "limit_up" to limitUp.take(40).map { row(it) },
            "limit_down" to limitDown.take(40).map { row(it) },
            "sectors" to res.rows.map { s ->
                mapOf(
                    "name" to s.name,
                    "n" to s.n,
                    "chg_pct" to s.chg1d,
                    "heat" to s.heat,
                    "up" to s.up,
                    "down" to s.down,
                    "limit_up" to s.limitUp,
                    "leader" to s.leader,
                    "leader_pct" to s.leaderPct,
                )
            },
            "coverage" to mapOf(
                "mapped" to res.mapped,
                "total" to res.total,
                "unmapped" to res.unmapped,
                "label" to "覆盖 ${"%.0f".format(res.coverage * 100)}%（未映射的股票不计入任何板块）",
            ),
        )
    }

    /**
     * 板块聚合 → [SectorsSnapshot]（首屏 sectors 卡用）。
     *
     * 只给 industries（本地能算的那一层）：concepts 需要概念归属表，端上同样没有，
     * 留空并写进 degradedReason，别拿行业当概念糊上去。
     */
    fun snapshot(res: Result?, concepts: List<Board> = emptyList()): SectorsSnapshot {
        if (res == null) {
            return SectorsSnapshot(
                ts = null,
                status = "unavailable",
                degradedReason = "本地实时行情或板块映射不可达（未使用云端归档冒充）",
            )
        }
        return SectorsSnapshot(
            ts = res.ts,
            status = "ok",
            degradedReason = "本地实时聚合：覆盖 ${(res.coverage * 100).toInt()}%（${res.mapped}/${res.total}），概念板块需概念归属表，本地不可得",
            freshBoards = res.rows.size,
            staleBoards = 0,
            industries = res.rows.map { r ->
                Board(
                    bk = r.name,
                    name = r.name,
                    totalMktcap = null,
                    nStocks = r.n,
                    heat = r.heat,
                    chg1d = r.chg1d,
                    chg5d = null,
                )
            },
            concepts = concepts,
            boardMembers = emptyMap(),
        )
    }

    /** 市场宽度（涨跌分布 / 涨跌停 / 成交额），界面宽度卡片用。 */
    fun breadth(pulse: LivePulse?): Map<String, Any?> {
        if (pulse == null || pulse.total == 0) {
            return mapOf("status" to "unavailable", "source" to "local", "reason" to "实时行情不可达")
        }
        return mapOf(
            "status" to "ok",
            "source" to "local",
            "total" to pulse.total,
            "up" to pulse.up,
            "down" to pulse.down,
            "flat" to pulse.flat,
            "limitUp" to pulse.limitUp,
            "limitDown" to pulse.limitDown,
            "medianPct" to pulse.medianPct,
            "breadth" to pulse.breadth,
            "amount" to pulse.totalAmount,
            "mainNet" to pulse.mainNetTotal,
            "label" to pulse.label,
        )
    }

    private class MutableAgg(val name: String) {
        var n = 0
        var sumPct = 0.0
        var pctN = 0
        var heat = 0.0
        var up = 0
        var down = 0
        var limitUp = 0
        var limitDown = 0
        var leader: String? = null
        var leaderPct: Double? = null

        fun add(r: LiveMarket.Row) {
            // 停牌（无价无量）不进板块统计：它会把板块家数虚增、把均值往 0 稀释，
            // 让「板块整体在涨」被读成「板块整体不动」。
            if (!r.tradable) return
            n++
            r.pctChg?.let {
                sumPct += it
                pctN++
                if (it > 0) up++ else if (it < 0) down++
                if (r.atLimitUp) limitUp++
                if (r.atLimitDown) limitDown++
                if (leaderPct == null || it > leaderPct!!) {
                    leaderPct = it
                    leader = r.name ?: r.code
                }
            }
            heat += r.amount ?: 0.0
        }

        fun toRow() = Row(
            name = name,
            n = n,
            chg1d = if (pctN == 0) null else sumPct / pctN,
            heat = heat,
            up = up,
            down = down,
            limitUp = limitUp,
            limitDown = limitDown,
            leader = leader,
            leaderPct = leaderPct,
        )
    }
}
