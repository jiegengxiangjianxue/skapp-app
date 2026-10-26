package com.skapp.app.data

/**
 * 数据模型。所有字段都允许为空 —— 上游缺数据时必须显示「暂无数据」，
 * 绝不编造（项目红线）。
 */

data class Quote(
    val code: String,
    val name: String = "",
    val price: Double? = null,
    val pctChg: Double? = null,
    val ts: String? = null,
)

/** 引擎评分：多来源真实分数，key 为引擎名 */
data class BundleDetail(
    val status: String = "insufficient",  // ok | insufficient
    val reason: String? = null,
    val ts: String? = null,
    val quote: Map<String, Double?> = emptyMap(),
    val technical: Map<String, Double?> = emptyMap(),
    val financialLatest: Map<String, Any?> = emptyMap(),
)

/** 个股详情（API + 分片 bundle 合并） */
data class StockDetail(
    val code: String,
    val name: String? = null,
    val engineScores: Map<String, Double?> = emptyMap(),
    val boards: List<String> = emptyList(),
    val detail: BundleDetail? = null,
    val aiQa: AiQa? = null,
    val marketPhase: Map<String, Any?> = emptyMap(),
)

/** AI 问答：内容只来自 GitCode AI 基于真实数据的回答 */
data class AiQa(
    val status: String = "insufficient",
    val reason: String? = null,
    val why: String? = null,
    val risk: String? = null,
    val focus: String? = null,
    val model: String? = null,
    val ts: String? = null,
)

data class DecisionCard(
    val code: String,
    val name: String? = null,
    val price: Double? = null,
    val pctChg: Double? = null,
    val composite: Double? = null,
    val reasons: List<String> = emptyList(),
    val action: String? = null,
    val confidence: Double? = null,
    val entry: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
    val holdingDays: Int? = null,
    val risk: String? = null,
    val why: Map<String, Any?> = emptyMap(),
    val dims: Map<String, Any?> = emptyMap(),
    val detail: BundleDetail? = null,
)

data class Decision(
    val ts: String? = null,
    val engine: String? = null,
    val marketRegime: String? = null,
    val regimeDetail: String? = null,
    val positionAdvice: Map<String, Any?> = emptyMap(),
    val cards: List<DecisionCard> = emptyList(),
    val avoid: List<Any?> = emptyList(),
    val evidence: Map<String, Any?> = emptyMap(),
    val sources: Map<String, Any?> = emptyMap(),
)

data class PickSignal(
    val code: String,
    val name: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val pctChg: Double? = null,
    val score: Double? = null,
    val buyable: Boolean? = null,
    val snapshotStatus: String? = null,
    val engine: String? = null,
    val resonance: Double? = null,
    val reason: String? = null,
    val calculationTime: String? = null,
    val why: List<String> = emptyList(),
    val detail: BundleDetail? = null,
)

data class StructuredPickArtifact(
    val generatedAt: String? = null,
    val calculationTime: String? = null,
    val date: String? = null,
    val session: String? = null,
    val engine: String? = null,
    val status: String = "insufficient",
    val reason: String? = null,
    val picks: List<PickSignal> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class CloseReview(
    val generatedAt: String? = null,
    val date: String? = null,
    val status: String = "insufficient",
    val reason: String? = null,
    val stages: Map<String, Map<String, Any?>> = emptyMap(),
)

data class WinrateMatrix(
    val ts: String? = null,
    val sourceTs: String? = null,
    val source: String? = null,
    val horizons: Map<String, Map<String, Any?>> = emptyMap(),
)

data class TodayPicks(
    val ts: String? = null,
    val date: String? = null,
    val session: String? = null,
    val market: Map<String, Any?> = emptyMap(),
    val signals: List<PickSignal> = emptyList(),
    val values: List<PickSignal> = emptyList(),
    val limitUp: List<PickSignal> = emptyList(),
    val sources: Map<String, Any?> = emptyMap(),
)

data class SymbolEntry(
    val code: String,
    val name: String?,
)

class SymbolIndex(sources: List<SymbolEntry> = emptyList()) {
    val byCode: Map<String, SymbolEntry>
    val byName: Map<String, List<SymbolEntry>>
    val entries: List<SymbolEntry>

    init {
        val merged = sortedMapOf<String, SymbolEntry>()
        sources.forEach { entry ->
            val code = SkappApi.normalize(entry.code) ?: return@forEach
            val name = entry.name?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val previous = merged[code]?.name
            val preferred = when {
                previous == null -> name
                name == null -> previous
                name.any { it in '\u3400'..'\u9fff' } && previous.none { it in '\u3400'..'\u9fff' } -> name
                else -> previous
            }
            merged[code] = SymbolEntry(code, preferred)
        }
        byCode = merged.toMap()
        entries = byCode.values.toList()
        byName = entries.filter { !it.name.isNullOrBlank() }.groupBy { searchKey(it.name.orEmpty()) }
    }

    fun search(query: String, limit: Int = 50): List<SymbolEntry> {
        val key = searchKey(query)
        if (key.isEmpty() || limit <= 0) return emptyList()
        val normalizedCode = SkappApi.normalize(query)
        val exact = listOfNotNull(normalizedCode?.let(byCode::get)) + byName[key].orEmpty()
        return (exact.asSequence() + entries.asSequence().filter {
            (normalizedCode != null && it.code.contains(normalizedCode)) ||
                searchKey(it.name.orEmpty()).contains(key)
        }).distinctBy { it.code }.take(limit).toList()
    }

    private fun searchKey(value: String): String = value.lowercase(java.util.Locale.ROOT).filterNot { it.isWhitespace() }
}

data class MarketSnapshot(
    val ts: String? = null,
    val market: Map<String, Any?> = emptyMap(),
    val phase: Any? = null,
    val hotNews: List<Map<String, Any?>> = emptyList(),
)

/** 消息面：移植站点 news_* 四个引擎产出 */
data class NewsSnapshot(
    val schemaVersion: String? = null,
    val date: String? = null,
    val n: Int? = null,
    val ts: String? = null,
    val digest: Map<String, Any?> = emptyMap(),
    /** 统一新闻 schema：id/title/source/time/verdict/direction/reason/stocks/status/image */
    val items: List<Map<String, Any?>> = emptyList(),
    val themes: List<Map<String, Any?>> = emptyList(),
    val impacts: List<Map<String, Any?>> = emptyList(),
    val transmission: List<Map<String, Any?>> = emptyList(),
    val usedFallback: Boolean = false,
)

/** 板块 */
data class Board(
    val bk: String,
    val name: String? = null,
    val totalMktcap: Double? = null,
    val nStocks: Int? = null,
    val heat: Double? = null,
    val chg1d: Double? = null,
    val chg5d: Double? = null,
)

data class SectorsSnapshot(
    val ts: String? = null,
    val industries: List<Board> = emptyList(),
    val concepts: List<Board> = emptyList(),
    val boardMembers: Map<String, Map<String, Any?>> = emptyMap(),
)

/** 用户提问 → 命中个股 → 答案 */
data class AskResult(
    val question: String,
    val matched: List<StockDetail> = emptyList(),
    val answer: String? = null,
    val sources: Map<String, Any?> = emptyMap(),
    val error: String? = null,
)
