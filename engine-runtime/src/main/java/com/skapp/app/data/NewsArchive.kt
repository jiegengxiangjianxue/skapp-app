package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [NewsCalc] ⇄ GitHub 消息库条目 的转换。
 *
 * 消息库不是新闻原文备份，而是「这条消息落到哪些票、什么方向」的结构化结论：
 * 早盘二次分析要的是可聚合的字段，不是再读一遍标题。
 *
 * 一个容易写错的点：`impact` 为空不等于「这条消息没关系」。`NewsCalcEngine` 对
 * 没识别出具体票、也没命中板块的消息给 `status=no_stock`，这本身是有效信息——
 * 早盘分析要能区分「这条消息没落到票上」和「库里根本没有这条消息」。所以照存不误。
 */
object NewsArchive {

    /** 一条消息落库的字段名，读回时按同一套解析。 */
    private const val KEY_TS = "ts"

    fun toJson(calc: NewsCalc, date: String): JSONObject {
        val benefit = calc.stocks
            .filter { isBenefit(calc, it) }
            .mapNotNull { stock -> stock.code.takeIf { it.isNotBlank() } }
            .distinct()
        val harm = calc.stocks
            .filter { isHarm(calc, it) }
            .mapNotNull { stock -> stock.code.takeIf { it.isNotBlank() } }
            .distinct()

        return JSONObject().apply {
            put("date", date)
            put(KEY_TS, calc.ts.orEmpty())
            put("src", calc.source.orEmpty())
            put("title", calc.newsTitle)
            put("sentiment", calc.sentiment)
            put("verdict", calc.verdict)
            put("direction", calc.direction)
            put("status", calc.status)
            put("benefit", JSONArray(benefit))
            put("harm", JSONArray(harm))
            put("impact", JSONArray(
                calc.stocks.mapNotNull { it.code.takeIf { c -> c.isNotBlank() } }.distinct()
            ))
            put("themes", JSONArray(calc.themes))
            put("boards", JSONArray(calc.boards))
            calc.boardMomentum?.let { put("boardMomentum", it) }
            calc.positiveReason?.let { put("posReason", it) }
            calc.negativeReason?.let { put("negReason", it) }
        }
    }

    /** 利好方向且标的不在「已大涨防追高」里才算受益；`verdict` 优先级高于 `sentiment`。 */
    private fun isBenefit(calc: NewsCalc, stock: NewsStockCalc): Boolean {
        if (calc.verdict.contains("利空")) return false
        if (calc.sentiment != "pos" && !calc.verdict.contains("利好")) return false
        // 「利好·但标的已大涨」是套人，不算受益——这正是 verdictOf 存在的原因
        if (calc.verdict.contains("防追高")) return false
        return true
    }

    /** 利空方向，或有风险词的标的算受损。 */
    private fun isHarm(calc: NewsCalc, stock: NewsStockCalc): Boolean {
        if (calc.verdict.contains("利空")) return true
        return calc.sentiment == "neg"
    }

    /**
     * 从消息库条目还原成分析用的行。
     *
     * 只取早盘重算需要的字段：`ts`（排序/去重）、`benefit`/`harm`（消息修正的依据）、
     * `sentiment`（强度）、`status`（判断是不是 no_stock 条目）。
     */
    fun readRows(items: JSONArray?): List<JSONObject> {
        if (items == null) return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.takeIf { it.optString(KEY_TS).isNotBlank() }
        }
    }

    /** 按 ts 去重合并，ts 升序——与 [PicksArchiveQueue] 的合并口径一致。 */
    fun mergeByTs(existing: List<JSONObject>, incoming: List<JSONObject>): List<JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        (existing + incoming).forEach { obj ->
            val ts = obj.optString(KEY_TS)
            if (ts.isNotBlank()) map[ts] = obj
        }
        return map.entries.sortedBy { it.key }.map { it.value }
    }

    /** 统计覆盖区间，供早盘判断「消息库有没有断层」。 */
    fun coverage(rows: List<JSONObject>): Pair<String, String>? {
        val sorted = rows.mapNotNull { it.optString(KEY_TS).takeIf { ts -> ts.isNotBlank() } }.sorted()
        return if (sorted.isEmpty()) null else sorted.first() to sorted.last()
    }
}
