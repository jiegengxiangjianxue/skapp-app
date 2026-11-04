package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 各页面共用输入装配；不调用消息页计算，避免消息分析与个股分析互相递归。 */
object CentralAnalysis {
    data class Parameters(val spec: EngineSpec, val notes: List<String>)
    @Volatile private var parameters: Pair<Long, Parameters>? = null

    fun parametersOf(base: EngineSpec, document: JSONObject?): Parameters {
        if (document == null) return Parameters(base, listOf("动态权重不可达，使用随包规格权重"))
        val weights = document.optJSONObject("weights")
            ?: return Parameters(base, listOf("动态权重缺weights，使用随包规格权重"))
        val stamp = document.optString("ts").takeIf { it.isNotBlank() }
            ?: return Parameters(base, listOf("动态权重缺发布时间，使用随包规格权重"))
        if (timeOf(stamp)?.let { it in 1..System.currentTimeMillis() } != true) return Parameters(base, listOf("动态权重时间非法，使用随包规格权重"))
        val known = base.weights.keys
        val raw = weights.keys().asSequence().associateWith { weights.optDouble(it, Double.NaN) }
        if (raw.values.any { !it.isFinite() || it < 0 || it > 1.5 })
            return Parameters(base, listOf("动态权重越界或非法，整份拒收，使用随包规格权重"))
        val accepted = raw.filterKeys { it in known }
        val unknown = raw.keys - known
        if (accepted.isEmpty()) return Parameters(base, listOf("动态权重无可识别键，使用随包规格权重"))
        return Parameters(base.copy(weights = base.weights + accepted,
            weightsSource = "engine_weights.json@$stamp"),
            listOfNotNull(unknown.takeIf { it.isNotEmpty() }?.let { "未应用未知权重键：${it.joinToString()}" }))
    }

    suspend fun loadParameters(): Parameters = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        parameters?.takeIf { now - it.first < FreshnessPolicy.CLOUD_TTL_MS }?.second ?: run {
            val body = Http.getText(RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A,
                "engine_weights.json"), timeoutMs = 8_000, ua = "skapp-android")
            val document = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            parametersOf(EngineSpecLoader.loadBundled().spec, document).also { parameters = now to it }
        }
    }

    fun timeOf(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(AnalysisSessionPolicy.zone).toInstant().toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(AnalysisSessionPolicy.zone).toInstant().toEpochMilli()
            }.getOrNull()
            ?: value.toLongOrNull()?.takeIf { it > 1_000_000_000 }?.let { if (it < 10_000_000_000L) it * 1000 else it }
    }

    @Volatile private var latestNews: List<EngineChain.NewsEvidence> = emptyList()
    @Volatile var hasNewsContext: Boolean = false
        private set

    fun recordNews(snapshot: NewsSnapshot, symbols: List<SymbolEntry>) {
        latestNews = newsFromSnapshot(snapshot, symbols)
        hasNewsContext = newsAvailable(snapshot)
    }

    fun newsFromSnapshot(snapshot: NewsSnapshot, symbols: List<SymbolEntry>): List<EngineChain.NewsEvidence> =
        snapshot.items.flatMap { item ->
            val title = (item["title"] ?: item["digest"])?.toString().orEmpty()
            val text = title + " " + listOf("content", "summary", "analysis", "detail")
                .mapNotNull { item[it]?.toString() }.joinToString(" ")
            val sentiment = NewsCalcEngine.sentiment(text)
            val source = (item["source_name"] ?: item["source"])?.toString()
            val time = timeOf((item["time"] ?: item["published_at"] ?: item["updated"] ?: item["ctime"] ?: item["ts"])?.toString())
            fun reason(key: String, words: List<String>): String? = item[key]?.toString()
                ?.takeIf { it.isNotBlank() && it != "insufficient" }
                ?: words.takeIf { it.isNotEmpty() }?.joinToString("、")
            val direction = if (sentiment.neg.isNotEmpty()) "neg" else
                when (item["direction"]?.toString()?.lowercase()) {
                    "pos", "bull", "positive" -> "pos"
                    "neg", "bear", "negative" -> "neg"
                    "neu", "neutral" -> "neu"
                    else -> sentiment.senti
                }
            NewsCalcEngine.resolveStocks(text, symbols).map { code ->
                EngineChain.NewsEvidence(code, "$source:$title", title, direction,
                    reason("positive_reason", sentiment.pos), reason("negative_reason", sentiment.neg), time, source)
            }
        }.distinctBy { it.id to it.code }

    fun newsAvailable(snapshot: NewsSnapshot): Boolean = snapshot.items.isNotEmpty() ||
        snapshot.sourceStatus.values.any { it.startsWith("ok", ignoreCase = true) }

    fun newsOf(calcs: List<NewsCalc>): List<EngineChain.NewsEvidence> = (latestNews + calcs.flatMap { n ->
        n.directCodes.distinct().map { code ->
            EngineChain.NewsEvidence(Code.normalize(code) ?: code, "${n.source}:${n.newsTitle}", n.newsTitle, n.direction,
                n.positiveReason, n.negativeReason, timeOf(n.ts), n.source)
        }
    }).distinctBy { it.id to it.code }

    fun flowOf(code: String, row: LiveMarket.Row?, history: List<Map<String, Any?>>, bars: List<KBar>,
               spec: EngineSpec, nowMs: Long): FlowEngine.Result? {
        val fresh = row?.takeIf { it.ts > 0 && nowMs - it.ts in 0..spec.runtime.snapshotStaleMs }
        fresh?.let { FlowEngine.compute(it, p = EngineChain.flowParams(spec)) }
            ?.takeIf { it.score != null }?.let { return it }
        val day = bars.lastOrNull()?.date ?: return null
        val dayMs = runCatching { java.time.LocalDate.parse(day).atStartOfDay(AnalysisSessionPolicy.zone).toInstant().toEpochMilli() }.getOrNull() ?: return null
        if (nowMs - dayMs !in 0..864_000_000L) return null
        val record = history.lastOrNull { it["date"]?.toString()?.take(10) == day } ?: return null
        val source = record["source"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val ratio = (record["main_ratio"] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            ?: return null
        return FlowEngine.fromMainRatio(code, ratio, "历史资金占比@$day 来源$source（非实时横截面）", EngineChain.flowParams(spec))
    }

    fun intradayFlowOf(code: String, live: List<Map<String, Any?>>, minute: List<Map<String, Any?>>,
                       minuteDate: String?, nowMs: Long, spec: EngineSpec): FlowEngine.Result? {
        val day = minuteDate?.let { if (it.length == 8 && it.all { ch -> ch.isDigit() })
            "${it.take(4)}-${it.substring(4,6)}-${it.takeLast(2)}" else it } ?: return null
        val flow = live.lastOrNull() ?: return null
        if (flow["code"]?.toString()?.let { Code.normalize(it) } != Code.normalize(code)) return null
        if (flow["source"]?.toString().isNullOrBlank()) return null
        val stamp = timeOf(flow["time"]?.toString()) ?: return null
        if (nowMs - stamp !in 0..spec.runtime.snapshotStaleMs) return null
        val at = Instant.ofEpochMilli(stamp).atZone(AnalysisSessionPolicy.zone)
        if (at.toLocalDate().toString() != day) return null
        val hhmm = at.hour.toString().padStart(2, '0') + at.minute.toString().padStart(2, '0')
        val point = minute.lastOrNull { it["time"]?.toString()?.replace(":", "") == hhmm } ?: return null
        val amount = (point["amount"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val main = (flow["main_net"] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null
        return FlowEngine.fromMainRatio(code, main/amount, "实时主力净额/同分钟成交额@$day $hhmm（跨源时间对齐）",
            EngineChain.flowParams(spec))
    }

    fun compute(code: String, bars: List<KBar>, snapshot: List<LiveMarket.Row>,
                history: List<Map<String, Any?>> = emptyList(), news: List<EngineChain.NewsEvidence> = emptyList(),
                newsAvailable: Boolean = false, index: IndexDaily.Result? = null,
                params: Parameters = Parameters(EngineSpecLoader.loadBundled().spec, emptyList()),
                nowMs: Long = System.currentTimeMillis(), liveFlow: List<Map<String, Any?>> = emptyList(),
                minute: List<Map<String, Any?>> = emptyList(), minuteDate: String? = null): EngineChain.ChainResult {
        val row = snapshot.firstOrNull { it.code == code && it.ts > 0 && nowMs - it.ts in 0..params.spec.runtime.snapshotStaleMs }
        val flow = intradayFlowOf(code, liveFlow, minute, minuteDate, nowMs, params.spec)
            ?: flowOf(code, row, history, bars, params.spec, nowMs)
        val indexDay = index?.bars?.lastOrNull()?.date?.let { date ->
            runCatching { java.time.LocalDate.parse(date).atStartOfDay(AnalysisSessionPolicy.zone).toInstant().toEpochMilli() }.getOrNull()
        }
        val indexUsable = index != null && indexDay != null && nowMs - indexDay in 0..864_000_000L &&
            index.closes.size >= 21 && index.closes.all { it.isFinite() && it > 0 }

        val result = EngineChain.analyze(EngineChain.Inputs(bars = bars, row = row,
            precomputedFlow = flow, regimeCloses = index?.closes?.takeIf { indexUsable }, nowMs = nowMs,
            code = code, news = news, newsAvailable = newsAvailable), params.spec)
        val provenance = listOfNotNull(index?.let { "指数输入：${it.source}；数据日${it.bars.lastOrNull()?.date ?: "unknown"}；${if (indexUsable) "已校验" else "未过时效/样本校验，未参与"}" })
        val notes = params.notes + (if (flow?.tier == "observed_main") flow.reasons else emptyList()) +
            if (history.isNotEmpty() && flow == null) listOf("已接入${history.size}条资金记录；缺同日真实占比/成交额或数据过期，不能计算资金分") else emptyList()
        return result.copy(degraded = (result.degraded + notes).distinct(), provenance = result.provenance + provenance,
            status = if (result.status == "ok" && notes.isNotEmpty()) "partial" else result.status)
    }

    suspend fun analyze(code: String, bars: List<KBar>, history: List<Map<String, Any?>> = emptyList(),
                        news: List<NewsCalc> = emptyList(), newsAvailable: Boolean = false,
                        liveFlow: List<Map<String, Any?>> = emptyList(), minute: List<Map<String, Any?>> = emptyList(),
                        minuteDate: String? = null): EngineChain.ChainResult = coroutineScope {
        val params = async { loadParameters() }
        val index = async { IndexDaily.fetch() }
        withContext(Dispatchers.Default) {
            compute(code, bars, LiveMarket.lastSnapshot(), history, newsOf(news), newsAvailable,
                index.await(), params.await(), liveFlow = liveFlow, minute = minute, minuteDate = minuteDate)
        }
    }
}
