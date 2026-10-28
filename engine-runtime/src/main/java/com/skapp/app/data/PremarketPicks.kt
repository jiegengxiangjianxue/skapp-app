package com.skapp.app.data

import org.json.JSONObject

/**
 * 早盘推股名单：09:00–09:30 可变、09:30 冻结。
 *
 * ## 为什么名单在开盘前还要改
 *
 * 夜里云端出的 A/B 候选和本地 `LocalPicks.PREMARKET` 只看得到上一交易日收盘为止的数据。
 * 早间一条「中标」「预增」就能让名单换人——所以这段时间的正确做法不是「晚上算一次就锁死」，
 * 而是拿最新消息反复修正同一份草稿，直到 09:30 才落盘冻结。
 *
 * ## 修正口径
 *
 * 只有两类消息影响选股：**受益**（利好且标的不在「已大涨防追高」里）与**受损**（利空）。
 * 中性消息、没落到票上的消息（`no_stock`）不参与打分——否则一条「宏观要闻」会把所有票
 * 都抬一格，等于没调。
 *
 * 单条消息的权重封顶：同一只票被三条「真利好」刷分并不比一条「真利好」更值钱，
 * 取**最大单条**再加一点条数微调，避免同一事件被多家媒体转载时把排名刷歪。
 */
object PremarketPicks {

    enum class Status { DRAFT, FROZEN }

    /** 消息对个股的作用方向与强度。 */
    data class NewsHit(
        val code: String,
        val benefit: Boolean,
        val harm: Boolean,
        val delta: Double,
        val reason: String,
    )

    data class DraftPick(
        val code: String,
        val name: String?,
        val price: Double?,
        val pctChg: Double?,
        val baseScore: Double?,
        val newsDelta: Double,
        val finalScore: Double,
        val benefit: Boolean,
        val harm: Boolean,
        val why: List<String>,
        val source: String, // local | news
    )

    data class Draft(
        val status: Status,
        val date: String,
        val updatedAt: String,
        val picks: List<DraftPick>,
        /** 消息库有没有断层：早盘窗口前一夜没开 App、补拉也不够时会置 true。 */
        val dataGap: Boolean,
        val coveredFrom: String? = null,
        val coveredUntil: String? = null,
        val note: String? = null,
    )

    /** 单条消息的加减分：真利好 > 普通利好 > 板块级；利空一律重罚（宁可漏，不可错）。 */
    private fun deltaOf(benefit: Boolean, harm: Boolean, verdict: String?): Double = when {
        harm -> -10.0
        !benefit -> 0.0
        verdict?.contains("真利好") == true -> 8.0
        verdict?.contains("利好") == true -> 5.0
        // 「利好·防追高」在 NewsArchive 里已归到 benefit=false；这里兜底给 0
        else -> 3.0
    }

    /**
     * 从本地深算产物 + 消息库 + 实时消息算出早盘草稿。
     *
     * @param base      本地盘前深算（`LocalPicks.build(PREMARKET)`），可为空 artifact
     * @param stored    消息库当天条目（JSON）
     * @param live      本轮实时算好的消息
     * @param frozen    是否已过 09:30（冻结后仍返回同一份，但 status=FROZEN）
     */
    fun build(
        base: StructuredPickArtifact?,
        stored: List<JSONObject>,
        live: List<NewsCalc>,
        date: String,
        now: String,
        frozen: Boolean,
        coverage: Pair<String, String>? = null,
    ): Draft {
        val hits = collectHits(stored, live)

        // 基础候选：本地深算的达标票，分数直接用于排序
        val baseByCode = base?.picks.orEmpty().associateBy { it.code }

        // 消息命中的票里，本地没覆盖到的（例如夜里没算、或板块成员）也给一个基准分，
        // 但不能凭空超过本地深算出来的票：基准取 MIN_SCORE 附近
        val messageOnly = hits.keys.filter { it !in baseByCode }
            .sortedByDescending { code ->
                hits[code].orEmpty().maxOfOrNull { it.delta } ?: 0.0
            }
            .take(5)

        val candidates = (baseByCode.keys + messageOnly).map { code ->
            val pick = baseByCode[code]
            val codeHits = hits[code].orEmpty()
            val newsDelta = if (codeHits.isEmpty()) 0.0 else {
                // 最大单条 + 条数微调（第二条起每条 +1，封顶 +3）
                val peak = codeHits.maxOf { it.delta }
                val bonus = ((codeHits.size - 1) * 1.0).coerceAtMost(3.0)
                (peak + bonus).coerceIn(-15.0, 12.0)
            }
            val baseScore = pick?.score ?: (LocalPicks.MIN_SCORE - 5.0)
            DraftPick(
                code = code,
                name = pick?.name,
                price = pick?.price,
                pctChg = pick?.pctChg,
                baseScore = pick?.score,
                newsDelta = newsDelta,
                finalScore = baseScore + newsDelta,
                benefit = codeHits.any { it.benefit },
                harm = codeHits.any { it.harm },
                why = buildList {
                    pick?.why?.take(2)?.forEach { add(it) }
                    codeHits.take(2).forEach { add("消息：" + it.reason) }
                    if (pick == null) add("本地深算未覆盖，由消息面单独提示")
                },
                source = if (pick != null) "local" else "news",
            )
        }

        // 受损票不进推荐（利空方向即使分数高也不该出现在「推股」里），
        // 但保留在 why 里说明理由，避免用户以为漏算
        val recommended = candidates
            .filter { !it.harm && it.finalScore >= LocalPicks.MIN_SCORE }
            .sortedByDescending { it.finalScore }
            .take(LocalPicks.MAX_PICKS)

        val (from, until) = coverage ?: (null to null)
        val gap = isGap(from, until, date, now)

        val note = buildString {
            base?.reason?.let { append(it) }
            if (hits.isNotEmpty()) {
                append("；消息面修正 ${hits.size} 只（受益 ${hits.values.count { l -> l.any { it.benefit } }}，"
                    + "受损 ${hits.values.count { l -> l.any { it.harm } }}）")
            }
            if (gap) append("；消息库存在断层，早盘结论的置信度下降")
        }.trim('；')

        return Draft(
            status = if (frozen) Status.FROZEN else Status.DRAFT,
            date = date,
            updatedAt = now,
            picks = recommended,
            dataGap = gap,
            coveredFrom = from,
            coveredUntil = until,
            note = note.ifBlank { null },
        )
    }

    /** 汇总消息库 + 实时消息里对个股的作用（同 code 合并成列表）。 */
    private fun collectHits(stored: List<JSONObject>, live: List<NewsCalc>): Map<String, List<NewsHit>> {
        val out = LinkedHashMap<String, MutableList<NewsHit>>()

        fun add(code: String, benefit: Boolean, harm: Boolean, delta: Double, reason: String) {
            if (code.isBlank() || (!benefit && !harm)) return
            out.getOrPut(code) { mutableListOf() }.add(NewsHit(code, benefit, harm, delta, reason))
        }

        stored.forEach { row ->
            val verdict = row.optString("verdict")
            val title = row.optString("title").take(24)
            val benefitCodes = row.optJSONArray("benefit")
            val harmCodes = row.optJSONArray("harm")
            val delta = deltaOf(benefit = true, harm = false, verdict = verdict)
            benefitCodes?.let { arr ->
                for (i in 0 until arr.length()) {
                    add(arr.optString(i), true, false, delta, title)
                }
            }
            harmCodes?.let { arr ->
                for (i in 0 until arr.length()) {
                    add(arr.optString(i), false, true, -10.0, title)
                }
            }
        }

        live.forEach { calc ->
            calc.stocks.forEach { stock ->
                val benefit = !calc.verdict.contains("利空") &&
                    (calc.sentiment == "pos" || calc.verdict.contains("利好")) &&
                    !calc.verdict.contains("防追高")
                val harm = calc.verdict.contains("利空") || calc.sentiment == "neg"
                add(stock.code, benefit, harm, deltaOf(benefit, harm, calc.verdict), calc.newsTitle.take(24))
            }
        }

        return out
    }

    /**
     * 判断消息库是否在早盘窗口前有断层。
     *
     * 早盘窗口从 09:00 起；要求库覆盖到 08:50 之后，否则视为缺口。
     * 只有日期是当天时才判断——历史日期的库天然不会覆盖到当天早盘。
     */
    private fun isGap(from: String?, until: String?, date: String, now: String): Boolean {
        if (until == null) return true
        if (dateOf(now) != date) return true
        return timeOf(until) < "08:50" || dateOf(until) != date
    }

    /** 冻结判定：只在 09:30 之后冻结；跨夜重启后靠落盘日期判断，不靠内存。 */
    fun isFrozen(now: String): Boolean = timeOf(now) >= "09:30"

    /** 取 "HH:mm"：同时兼容 `2026-10-29T09:12`（ISO）与 `2026-10-29 09:12`（站点口径）。 */
    private fun timeOf(ts: String): String = ts.substringAfter('T').substringAfter(' ').take(5)

    /** 取 "yyyy-MM-dd"。 */
    private fun dateOf(ts: String): String = ts.take(10)
}
