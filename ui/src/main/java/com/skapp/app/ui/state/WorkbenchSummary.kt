package com.skapp.app.ui.state

import com.skapp.app.data.CentralAnalysis
import com.skapp.app.data.Code
import com.skapp.app.data.MarketLimits
import com.skapp.app.ui.UiState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal object WorkbenchSummary {
    data class Entry(val text: String, val code: String? = null)
    data class Section(val id: String, val title: String, val status: String,
                       val source: String, val timestamp: String?, val entries: List<Entry>)

    fun fresh(stamp: String?, nowMs: Long, ttlMs: Long = 300_000L): Boolean =
        CentralAnalysis.timeOf(stamp)?.let { nowMs - it in 0..ttlMs } == true

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun display(value: Any?): String = number(value)?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "暂无数据"

    fun sections(s: UiState, nowMs: Long = System.currentTimeMillis()): List<Section> {
        val board = s.board
        val stamp = board["ts"]?.toString()
        val local = board["source"] == "local"
        val boardReady = local && board["status"] == "ok" && fresh(stamp, nowMs)
        val marketComplete = boardReady && board["coverageVerified"] == true
        val market = board["market"] as? Map<*, *>
        val marketEntries = if (marketComplete && market != null) listOf(
            Entry("上涨 ${display(market["涨"])} / 下跌 ${display(market["跌"])} / 平盘 ${display(market["平"])}"),
            Entry("涨停 ${display(market["涨停"])} / 跌停 ${display(market["跌停"])}（本地近似统计）"),
            Entry("成交额 ${display(market["成交额(亿)"])} 亿元 · 主力净额 ${display(market["主力净额(亿)"])} 亿元"),
        ) else listOf(Entry("全市场统计未就绪、分页不完整或已过期；行情采样 ${board["sampleSize"] ?: "未知"} / 预期 ${board["expectedTotal"] ?: "未知"}；异动榜 ${s.marketPulse.size} 只是候选样本，不代表全市场。"))
        val sectorRows = if (boardReady) (board["sectors"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty() else emptyList()
        val leaders = sectorRows.filter { number(it["chg_pct"]) != null }
            .sortedByDescending { number(it["chg_pct"]) }.take(5)
            .map { Entry("${it["name"] ?: "未知板块"} · ${display(it["chg_pct"])}% · 采样 ${it["n"] ?: "未知"} 只") }

        val news = s.news.items.filter { item ->
            val source = (item["source_name"] ?: item["source"])?.toString()
            val time = (item["time"] ?: item["published_at"] ?: item["ts"])?.toString()
            !source.isNullOrBlank() && fresh(time, nowMs, 86_400_000L)
        }.distinctBy { (it["source_name"] ?: it["source"]) to (it["title"] ?: it["digest"]) }
        val directions = news.map { item ->
            val declared = item["direction"]?.toString()
            val source = (item["source_name"] ?: item["source"])?.toString()
            val title = (item["title"] ?: item["digest"])?.toString()
            val time = CentralAnalysis.timeOf((item["time"] ?: item["published_at"] ?: item["ts"])?.toString())
            val analysis = s.newsCalcs.firstOrNull {
                it.newsTitle == title && it.source == source && time != null && CentralAnalysis.timeOf(it.ts) == time
            }
            declared?.takeIf { it in setOf("pos", "positive", "bull", "neg", "negative", "bear") }
                ?: analysis?.direction
        }
        val pos = directions.count { it in setOf("pos", "positive", "bull") }
        val neg = directions.count { it in setOf("neg", "negative", "bear") }
        val newsEntries = if (news.isEmpty()) listOf(Entry("没有通过来源与24小时有效期校验的消息。")) else
            listOf(Entry("有效消息 ${news.size} 条 · 利好 $pos / 利空 $neg；未定性 ${news.size - pos - neg} 条，不补成中性结论。")) +
                news.take(4).map { Entry("${it["title"] ?: it["digest"] ?: "无标题"} · ${it["source_name"] ?: it["source"]}") }
        val newsStamp = news.mapNotNull { (it["time"] ?: it["published_at"] ?: it["ts"])?.toString() }
            .maxByOrNull { CentralAnalysis.timeOf(it) ?: 0L }

        val analyzed = s.screened.filter { row -> row.snapshotAtMs?.let { nowMs - it in 0..300_000L } == true }
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        val artifacts = listOf(s.localRealtimePicks, s.localMorningPicks, s.localMiddayPicks, s.localPremarketPicks)
            .filter { it.date == date && it.status == "ok" && fresh(it.calculationTime, nowMs) }
        val eligible = artifacts.flatMap { artifact -> artifact.picks.filter { pick ->
            val snapshot = (artifact.metadata["snapshotByCode"] as? Map<*, *>)?.get(pick.code) as? Map<*, *>
            val evidence = (artifact.metadata["evidenceByCode"] as? Map<*, *>)?.get(pick.code) as? Map<*, *>
            val snapshotTime = (snapshot?.get("atMs") as? Number)?.toLong()
            val evidenceTime = (evidence?.get("snapshotAtMs") as? Number)?.toLong()
            val expires = (evidence?.get("expiresAtMs") as? Number)?.toLong()
            val period = try { java.time.LocalDate.parse(evidence?.get("reportPeriod")?.toString()) } catch (_: Exception) { null }
            val proven = evidence != null && snapshot != null && snapshot["source"] == "LocalScreener/LiveMarket.Row" && snapshotTime != null &&
                nowMs - snapshotTime in 0..300_000L && evidenceTime != null && evidenceTime in 1..nowMs &&
                expires != null && expires >= nowMs && period != null && period.toString() <= date &&
                number(evidence["eps"]) != null && number(evidence["dps"]) != null &&
                evidence["barsBound"] == true && !evidence["source"]?.toString().isNullOrBlank() &&
                evidence["adjustmentEvidence"]?.toString()?.startsWith("cnfin:hfq_factor:") == true
            proven && pick.buyable == true && pick.snapshotStatus == "live" && Code.isMainBoard(pick.code) &&
                pick.pctChg?.let { pct -> pct.isFinite() && pct < MarketLimits.LIMIT_UP_PCT } == true
        } }.distinctBy { it.code }
        val candidates = analyzed.distinctBy { it.code }.sortedByDescending { it.canonicalScore ?: Double.NEGATIVE_INFINITY }.take(5)
        val candidateEntries = listOf(Entry("本地有效推荐 ${eligible.size} 只 · 本轮可用观察样本 ${analyzed.size} 只（不是全市场覆盖）")) +
            eligible.take(5).map { Entry("推荐 · ${it.name ?: it.code} ${it.code} · ${it.reason ?: "详情查看证据与退出条件"}", it.code) } +
            candidates.filter { row -> eligible.none { it.code == row.code } }.map {
                Entry("观察 · ${it.name ?: it.code} ${it.code} · 中枢 ${display(it.canonicalScore)}\n${it.reason ?: it.riskFlag ?: "尚未通过正式推荐出口"}", it.code)
            }
        val risks = (s.dataGaps.map { "${it.key}：${it.value}" } +
            analyzed.mapNotNull { it.riskFlag } + analyzed.mapNotNull { it.reason } +
            listOfNotNull(s.localPicksMsg, s.screenMsg)).filter { it.isNotBlank() }.distinct().take(8)
        return listOf(
            Section("market", "市场与资金", if (marketComplete) "ok" else "insufficient", if (marketComplete) "本地全市场快照聚合" else "本地行情采样（全覆盖未核验）", stamp, marketEntries),
            Section("sectors", "板块与主线", if (leaders.isNotEmpty()) "ok" else "insufficient", "本地行情 × 板块映射", stamp,
                leaders.ifEmpty { listOf(Entry("暂无通过时效校验的板块强弱；不以旧主题报告代替今日主线。")) }),
            Section("news", "消息与催化", if (news.isNotEmpty()) "ok" else "insufficient", "逐条来源及发布时间校验", newsStamp, newsEntries),
            Section("candidates", "候选与行动", if (analyzed.isNotEmpty() || eligible.isNotEmpty()) "partial" else "insufficient",
                "本地引擎 / 正式推荐出口", s.screenedAt, candidateEntries),
            Section("risk", "证据缺口与失效条件", "observe", "本地门禁与数据源状态", null,
                risks.map { Entry(it) }.ifEmpty { listOf(Entry("行情过期、财务／复权缺证据、消息反转或退出条件触发时，不维持原推荐。无错误记录不代表风险为零。")) }),
        )
    }

    fun asMap(s: UiState, nowMs: Long = System.currentTimeMillis()): Map<String, Any?> {
        val sections = sections(s, nowMs)
        val ready = sections.any { it.status == "ok" || it.status == "partial" }
        return mapOf("status" to if (ready) "ok" else "insufficient", "source" to "local_realtime",
            "date" to Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),
            "ts" to sections.firstOrNull { it.id == "market" && it.status == "ok" }?.timestamp,
            "text" to sections.joinToString("\n\n") { section -> section.title + "\n" + section.entries.joinToString("\n") { it.text } },
            "reason" to if (ready) null else "本地有效行情、消息及分析证据尚未就绪；没有使用云端旧结论兜底")
    }
}
