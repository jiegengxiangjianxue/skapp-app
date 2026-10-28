package com.skapp.app.data

import org.json.JSONObject

/**
 * 云端涨停名单历史 → 本地日级快照序列。
 *
 * 端上本来靠 `market_days/` 囤 420 天日级快照来算连板：东财只给「此刻」的涨停池，
 * 昨天的名单删了就补不回来。现在历史搬到云端（stocks-ai-view 的
 * `scripts/build_limit_history.py` 用全市场日线回算，260 个交易日），端上只留当天，
 * 需要历史时把这份名单还原成 [DayMarketSnapshot] 交给 [LimitBoardCalc]。
 *
 * ## 哪些字段是真的、哪些必须标不可得
 *
 * - `limitUp` / `touchedLimit`：云端只存「收盘封住的」，没有「摸过没封住」→
 *   `hasTouchInfo=false`，封板率据此标不可得，而不是算出 100%（那是「今天没炸板」
 *   和「我们不知道」的区别）
 * - `high` / `low` / `open`：云端名单不带 → `hasRangeInfo=false`
 * - `pctChg` / `amount` / `close`：不带 → 填 null，不拿 0 冒充
 */
object LimitHistory {

    /** 云端名单还原成按日期升序的快照序列；没有 days 数组或有问题的行直接跳过。 */
    fun parse(json: JSONObject?): List<DayMarketSnapshot> {
        val arr = json?.optJSONArray("days") ?: return emptyList()
        val out = ArrayList<DayMarketSnapshot>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val date = o.optString("date").take(10)
            if (date.length < 10) continue
            val codes = o.optJSONArray("codes") ?: continue
            val entries = ArrayList<DayMarketSnapshot.Entry>(codes.length())
            for (j in 0 until codes.length()) {
                val code = codes.optString(j).takeIf { it.isNotBlank() } ?: continue
                entries.add(
                    DayMarketSnapshot.Entry(
                        code = code,
                        pctChg = null,
                        amount = null,
                        limitUp = true,
                        close = null,
                    ),
                )
            }
            out.add(
                DayMarketSnapshot(
                    date = date,
                    ts = 0L,
                    final = true,
                    entries = entries,
                    hasTouchInfo = false,
                    hasRangeInfo = false,
                ),
            )
        }
        return out.sortedBy { it.date }
    }

    /** 云端产物自带的窗口范围（界面/诊断说明覆盖率用）。 */
    fun window(json: JSONObject?): Pair<String?, String?> {
        val cov = json?.optJSONObject("coverage") ?: return null to null
        val first = cov.optString("first_date").takeIf { it.isNotBlank() }
        val last = cov.optString("last_date").takeIf { it.isNotBlank() }
        return first to last
    }
}
