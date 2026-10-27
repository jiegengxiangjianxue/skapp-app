package com.skapp.app.data

/**
 * 一天的本地全市场快照 —— **只存算得准、且明天还用得上**的最小集合。
 *
 * ## 为什么需要它
 *
 * 引擎池一直有几块字段只能写 -1 / null 并标「本地不可得」：连板高度、晋级率、
 * 首板/2 板家数、封板率历史、板块周期序列、长周期 RPS。它们缺的不是算法，
 * 是**多日全市场涨停序列**这张底表。东财 clist 只给「此刻」，不给昨天；
 * 而昨天的涨停名单一旦没存下来，永远补不回来。
 *
 * 所以从今天起：每轮心跳/引擎装配顺手把当日快照落一份到本地文件，
 * 第二天起这些字段就能真算——**存量不足就照旧报不可得，不拿 0 顶**。
 *
 * ## 存什么（每只票 5 个字段）
 *
 * code / 涨跌幅 / 成交额 / 是否涨停 / 收盘价。
 *
 * - 涨停用 [LiveMarket.Row.atLimitUp] 的口径算完再存：**判定只在落库那一刻做一次**，
 *   避免日后回算时口径漂移（历史上不同版本用过不同余量）。
 * - 只存这 5 个字段是为体积：5000 只 ≈ 150KB 原文，gzip 后 ~50KB/天。
 *   要算 RPS120 再存 120 天也就 6MB，完全可以放在手机上。
 */
data class DayMarketSnapshot(
    val date: String,
    val ts: Long,
    /** 是否为收盘终版（true=当日最后一次写入，之后不再改） */
    val final: Boolean,
    val entries: List<Entry>,
    val sectors: List<SectorDay> = emptyList(),
) {
    data class Entry(
        val code: String,
        val pctChg: Double?,
        val amount: Double?,
        val limitUp: Boolean,
        val close: Double?,
    )

    data class SectorDay(
        val name: String,
        val chg1d: Double?,
        val up: Int,
        val down: Int,
        val heat: Double,
    )

    fun limitUpCodes(): Set<String> = entries.asSequence().filter { it.limitUp }.map { it.code }.toSet()

    fun closeOf(code: String): Double? = entries.firstOrNull { it.code == code }?.close
}

/**
 * 连板 / 晋级 / 首板统计 —— 纯函数，输入是**按日期升序**的本地快照序列。
 *
 * ## 每个数字的准确含义（别混）
 *
 * - `limitUp`：今日涨停家数。
 * - `firstBoard`：今日涨停且**昨日没涨停** = 首板。
 * - `ge2`：今日涨停且昨日也涨停 = 2 板及以上（连板）。
 * - `height`：**连板高度** = 从今日往回数、连续涨停的最长天数。
 * - `promo`：**晋级率** = ge2 / 昨日涨停家数。衡量「昨天的板今天还能不能顶住」。
 * - `ydayLimitUpAvgPct`：昨日涨停股今日平均涨幅（打板赚钱效应，跌了就是亏钱效应）。
 *
 * 只有 1 天数据时 `firstBoard/ge2/promo` 一律不可得（null / -1），
 * 因为「昨天没涨停」和「昨天没数据」是两件事，不能混为一谈。
 */
object LimitBoardCalc {

    data class Stats(
        val date: String,
        val limitUp: Int,
        val firstBoard: Int,
        val ge2: Int,
        val height: Int,
        val heightCodes: List<String>,
        val promo: Double?,
        val ydayLimitUpAvgPct: Double?,
        val ydayLimitUp: Int,
        val daysUsed: Int,
    ) {
        /** 有几天数据可用（少于 2 天时连板相关字段都不可得）。 */
        val hasHistory: Boolean get() = daysUsed >= 2
    }

    /**
     * @param days 按日期升序；最后一天视为「今日」。至少 2 天才能算首板/晋级。
     */
    fun compute(days: List<DayMarketSnapshot>): Stats? {
        if (days.isEmpty()) return null
        val today = days.last()
        val todayLimitUp = today.limitUpCodes()
        if (todayLimitUp.isEmpty() && today.entries.isEmpty()) return null

        val prev = days.getOrNull(days.size - 2)
        val prevLimitUp = prev?.limitUpCodes().orEmpty()

        val firstBoard = if (prev == null) -1 else todayLimitUp.count { it !in prevLimitUp }
        val ge2 = if (prev == null) -1 else todayLimitUp.count { it in prevLimitUp }
        val promo = if (prev == null || prevLimitUp.isEmpty()) null
        else ge2.toDouble() / prevLimitUp.size

        val ydayAvg = if (prev == null || prevLimitUp.isEmpty()) null else {
            val pcts = prevLimitUp.mapNotNull { code ->
                today.entries.firstOrNull { it.code == code }?.pctChg
            }
            if (pcts.isEmpty()) null else pcts.average()
        }

        // 连板高度：从今日往回逐日检查「当天和之前每天是否都涨停」
        val heightCodes = mutableListOf<String>()
        var height = 0
        for (code in todayLimitUp) {
            var run = 1
            var idx = days.size - 2
            while (idx >= 0) {
                val day = days[idx]
                if (day.entries.isEmpty()) break
                if (code !in day.limitUpCodes()) break
                run++
                idx--
            }
            if (run > height) {
                height = run
                heightCodes.clear()
                heightCodes += code
            } else if (run == height) {
                heightCodes += code
            }
        }

        return Stats(
            date = today.date,
            limitUp = todayLimitUp.size,
            firstBoard = firstBoard,
            ge2 = ge2,
            height = height,
            heightCodes = heightCodes,
            promo = promo,
            ydayLimitUpAvgPct = ydayAvg,
            ydayLimitUp = prevLimitUp.size,
            daysUsed = days.size,
        )
    }
}

/**
 * 板块历史动量 —— 用本地快照里的板块日涨幅序列算 5/20 日动量与连续走强天数。
 *
 * 只有 1 天数据时返回空列表（不拿「今天涨 3%」冒充「周期第 N 天」）。
 */
object SectorHistoryCalc {

    data class Momentum(
        val name: String,
        val ret5d: Double?,
        val ret20d: Double?,
        val upStreak: Int,
        val days: Int,
    )

    /** @param days 按日期升序的本地快照（每份含 sectors）。 */
    fun momentum(days: List<DayMarketSnapshot>, top: Int = 12): List<Momentum> {
        if (days.size < 2) return emptyList()
        // 汇总每个板块的日涨幅序列（缺当天数据的板块跳过，不插 0）
        val series = LinkedHashMap<String, MutableList<Double>>()
        val lastHeat = HashMap<String, Double>()
        for (day in days) {
            for (s in day.sectors) {
                val chg = s.chg1d ?: continue
                series.getOrPut(s.name) { mutableListOf() } += chg
                lastHeat[s.name] = s.heat
            }
        }
        if (series.isEmpty()) return emptyList()

        fun compound(chgs: List<Double>): Double =
            chgs.fold(1.0) { acc, c -> acc * (1 + c / 100.0) } - 1.0

        return series.map { (name, chgs) ->
            val streak = chgs.reversed().takeWhile { it > 0 }.size
            Momentum(
                name = name,
                ret5d = if (chgs.size >= 5) compound(chgs.takeLast(5)) * 100 else null,
                ret20d = if (chgs.size >= 20) compound(chgs.takeLast(20)) * 100 else null,
                upStreak = streak,
                days = chgs.size,
            )
        }.sortedWith(
            // 5 日动量优先；没有 5 日数据的（新攒的）退到后面，避免噪声排到前面
            compareByDescending<Momentum> { it.ret5d ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.upStreak }
                .thenByDescending { lastHeat[it.name] ?: 0.0 },
        ).take(top)
    }
}
