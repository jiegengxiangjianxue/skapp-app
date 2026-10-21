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
    val price: Double? = null,
    val pctChg: Double? = null,
    val score: Double? = null,
    val engine: String? = null,
    val why: List<String> = emptyList(),
    val detail: BundleDetail? = null,
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

data class MarketSnapshot(
    val ts: String? = null,
    val market: Map<String, Any?> = emptyMap(),
    val phase: Any? = null,
    val hotNews: List<Map<String, Any?>> = emptyList(),
)

/** 消息面：移植站点 news_* 四个引擎产出 */
data class NewsSnapshot(
    val ts: String? = null,
    val digest: Map<String, Any?> = emptyMap(),
    val items: List<Map<String, Any?>> = emptyList(),
    val themes: List<Map<String, Any?>> = emptyList(),
    val impacts: List<Map<String, Any?>> = emptyList(),
    val transmission: List<Map<String, Any?>> = emptyList(),
)

/** 板块 */
data class Board(
    val bk: String,
    val name: String? = null,
    val totalMktcap: Double? = null,
    val nStocks: Int? = null,
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
