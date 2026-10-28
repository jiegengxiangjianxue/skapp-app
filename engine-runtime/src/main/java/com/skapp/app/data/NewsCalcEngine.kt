package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 快讯标题下的实时算法计算结果。
 *
 * 用户诉求：「消息面应该改成快讯和主线，所有计算都直接在快讯的新闻标题下显示」。
 *
 * 做法：每条快讯标题 → 提取提到的股票（名称/代码）→ 用**实时**行情 + 本地引擎
 * 现算（不用云端快照）→ 把结果挂在标题下。
 * 没有提到股票或算不出，就明说，不编造。
 */
data class NewsCalc(
    val newsTitle: String,
    val source: String? = null,
    val ts: String? = null,
    /** 标题中识别到的股票 */
    val stocks: List<NewsStockCalc> = emptyList(),
    /** 标题文本命中的情绪词（规则式，与站点 news_impact 同思路） */
    val sentiment: String = "neu",   // pos | neg | neu
    val sentimentHits: List<String> = emptyList(),
    /** 已写入 GitHub 新闻库的分析影响个股（当前条目无本地实时匹配时展示） */
    val impactCodes: List<String> = emptyList(),
    val boards: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    /** 命中板块的当日涨跌幅（板块维度分析） */
    val boardChg: Map<String, Double?> = emptyMap(),
    /** 命中板块平均涨跌幅：判断消息情绪与板块是否共振 */
    val boardMomentum: Double? = null,
    /** 利好质量判定：真利好 / 利好·防追高套人 / 利空 / 中性 */
    val verdict: String = "",
    val direction: String = "insufficient",
    val positiveReason: String? = null,
    val negativeReason: String? = null,
    val imageUrl: String? = null,
    val status: String = "ok",       // ok | no_stock | insufficient
    val note: String? = null,
)

data class NewsStockCalc(
    val code: String,
    val name: String?,
    val price: Double?,
    val pctChg: Double?,
    val volRatio: Double?,
    val localScore: Double?,        // 中枢引擎综合分（实时算）
    val resonanceRatio: Double? = null,
    val centralSummary: String? = null,
    val signal: String?,            // bullish | bearish | neutral | insufficient
    val topReasons: List<String> = emptyList(),
)

/** 情绪词典（规则式，与站点 news_impact 的规则式情感一致） */
private val POS_WORDS = listOf(
    "利好", "超预期", "大增", "增长", "上涨", "突破", "新高", "涨停", "获批", "中标",
    "合作", "扩产", "回购", "增持", "盈利", "创新高", "景气", "放量", "反弹", "走强",
    "bonus", "beat", "upgrade",
)
private val NEG_WORDS = listOf(
    "利空", "下滑", "下降", "亏损", "下跌", "破发", "跌停", "处罚", "违规", "退市",
    "减持", "解禁", "下调", "风险", "爆雷", "造假", "问询", "停产", "裁员", "疲软",
    "miss", "downgrade",
)

private val TRUE_POS_WORDS = listOf(
    "中标", "签约", "订单", "回购", "增持", "扭亏", "预增", "获批", "量产", "投产",
)
private val RISK_NEG_WORDS = listOf(
    "减持", "解禁", "立案", "处罚", "退市", "造假", "爆雷", "问询", "风险警示", "破产", "停产", "终止",
)

object NewsCalcEngine {

    /** 利好质量判定：带具体词与标的实时状态（真利好 vs 追高套人） */
    fun verdictOf(senti: String, hits: List<String>, title: String,
                  maxPct: Double?): String {
        val t = title.lowercase()
        val risk = hits.any { it.lowercase() in RISK_NEG_WORDS } ||
            RISK_NEG_WORDS.any { t.contains(it.lowercase()) }
        if (risk || senti == "neg") return "利空"
        if (senti != "pos") return "中性"
        if (maxPct != null && maxPct >= 8.0) return "利好·但标的已大涨${"%.1f".format(maxPct)}%，防追高套人"
        val solid = TRUE_POS_WORDS.any { t.contains(it.lowercase()) }
        return if (solid) "真利好" else "利好"
    }

    fun sentimentOf(text: String): Pair<String, List<String>> {
        val t = text.lowercase()
        val pos = POS_WORDS.filter { t.contains(it.lowercase()) }
        val neg = NEG_WORDS.filter { t.contains(it.lowercase()) }
        return when {
            pos.size > neg.size -> "pos" to pos
            neg.size > pos.size -> "neg" to neg
            pos.isNotEmpty() && neg.isNotEmpty() -> "neu" to (pos + neg)
            else -> "neu" to emptyList()
        }
    }

    /** 从标题里识别股票：先代码，后名称（用全市场 symbols 名称表） */
    fun resolveStocks(title: String, symbols: List<SymbolEntry>): List<String> {
        val hits = LinkedHashSet<String>()
        // 只认「6 位 A 股代码」或「hk + 3~5 位港股代码」。
        // 旧写法 (hk)?\d{4,6} 会吃下标题里的年份/点位（2026、3100），
        // 而 Code.normalize 会把 4~5 位数字 padStart 成 6 位——「2026 年」变成 002026，
        // 于是一条与股票无关的快讯被凭空挂上一只票的实时行情（编造关联）。
        val codeHit = Regex("(?<![0-9A-Za-z])(?:hk\\d{3,5}|\\d{6})(?![0-9])", RegexOption.IGNORE_CASE)
            .find(title)?.value
        if (codeHit != null) Code.normalize(codeHit)?.let { hits.add(it) }
        if (hits.isEmpty()) {
            symbols.asSequence()
                // 跨模块 public 属性无法智能转换，先取局部值（与 AskEngine 同构，待合并）
                .mapNotNull { entry -> entry.name?.let { name -> entry to name } }
                .filter { (_, name) -> name.length >= 3 }
                .sortedByDescending { (_, name) -> name.length }
                .firstOrNull { (_, name) -> title.contains(name) }
                ?.let { Code.normalize(it.first.code)?.let { c -> hits.add(c) } }
        }
        return hits.toList()
    }

    /**
     * 对一批快讯，逐条实时算（标题下直接显示）。
     * @param items 快讯（title/source_name/updated）
     * @param symbols 全市场代码表
     */
    suspend fun compute(
        items: List<Map<String, Any?>>,
        symbols: List<SymbolEntry>,
        klineCount: Int = 120,
        boards: List<Pair<String, Double?>> = emptyList(),
        boardMembers: Map<String, List<String>> = emptyMap(),
    ): List<NewsCalc> = coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                val title = (item["title"] ?: item["digest"])?.toString() ?: ""
                val body = listOf("content", "summary", "analysis", "detail")
                    .mapNotNull { item[it]?.toString()?.takeIf { value -> value.isNotBlank() } }
                    .joinToString(" ")
                val analysisText = "$title $body"
                val (senti, hits) = sentimentOf(analysisText)
                val storedVerdict = item["verdict"]?.toString()?.takeIf { it.isNotBlank() }
                val storedBoards = when (val raw = item["boards"] ?: item["sectors"] ?: item["affected_sectors"]) {
                    is List<*> -> raw.mapNotNull { it?.toString() }
                    is String -> raw.split(",", "、").filter { it.isNotBlank() }
                    else -> emptyList()
                }
                val storedThemes = when (val raw = item["themes"] ?: item["theme"]) {
                    is List<*> -> raw.mapNotNull { it?.toString() }
                    is String -> raw.split(",", "、").filter { it.isNotBlank() }
                    else -> emptyList()
                }
                val localPositiveReason = item["positive_reason"]?.toString()
                    ?: if (senti == "pos" && hits.isNotEmpty()) "消息命中偏多词：${hits.joinToString("、")}" else null
                val localNegativeReason = item["negative_reason"]?.toString()
                    ?: if (senti == "neg" && hits.isNotEmpty()) "消息命中偏空词：${hits.joinToString("、")}" else null
                val storedCodes = (item["codes"] as? List<*>)?.mapNotNull { value ->
                    when (value) {
                        is Map<*, *> -> (value["code"] ?: value["symbol"] ?: value["stock_code"])?.toString()
                        else -> value?.toString()
                    }
                } ?: (item["stocks"] as? List<*>)?.mapNotNull { value ->
                    when (value) {
                        is Map<*, *> -> (value["code"] ?: value["symbol"] ?: value["stock_code"])?.toString()
                        else -> value?.toString()
                    }
                } ?: emptyList()
                val codes = (resolveStocks(analysisText, symbols) + storedCodes)
                    .map { it.filter(Char::isDigit).takeLast(6) }
                    .filter { it.length == 6 }
                    .distinct()
                // ── 板块维度：消息命中的板块 + 板块当日动量 + 板块内个股 ──
                val matchedBoards = boards
                    .filter { (name, _) -> name.length >= 2 && analysisText.contains(name) }
                    .map { it.first }
                    .distinct()
                    .take(4)
                val boardChg = matchedBoards.associateWith { name ->
                    boards.firstOrNull { it.first == name }?.second
                }
                val boardMomentum = boardChg.values.filterNotNull().takeIf { it.isNotEmpty() }
                    ?.average()
                val boardCodes = matchedBoards
                    .flatMap { boardMembers[it].orEmpty() }
                    .distinct()
                val codesAll = (codes + boardCodes).distinct()
                if (codesAll.isEmpty()) {
                    return@async NewsCalc(
                        newsTitle = title,
                        source = (item["source_name"] ?: item["source"])?.toString(),
                        ts = (item["updated"] ?: item["ctime"])?.toString(),
                        sentiment = senti,
                        sentimentHits = hits,
                        impactCodes = storedCodes,
                        boards = (storedBoards + matchedBoards).distinct(),
                        themes = storedThemes,
                        boardChg = boardChg,
                        boardMomentum = boardMomentum,
                        verdict = storedVerdict ?: verdictOf(senti, hits, title, null),
                        direction = item["direction"]?.toString()?.takeIf { it.isNotBlank() } ?: senti,
                        positiveReason = localPositiveReason,
                        negativeReason = localNegativeReason,
                        imageUrl = imageUrlOf(item),
                        status = if (matchedBoards.isEmpty()) "no_stock" else "board_only",
                        note = if (matchedBoards.isEmpty()) "标题未提及具体股票与板块，无本地计算"
                        else "命中板块 ${matchedBoards.joinToString("、")}" +
                            (boardMomentum?.let { "（板块均涨 ${"%.2f".format(it)}%）" } ?: "（板块涨幅暂无数据）") +
                            "，板块内个股 ${boardCodes.size} 只待盘中确认",
                    )
                }
                val stocks = codesAll.map { code ->
                    // 实时报价 + 本地引擎（不用云端快照）
                    val q = runCatching { DataCache.quote(code) }.getOrNull()
                    val bars = runCatching { DataCache.kline(code, "qfq", klineCount) }.getOrDefault(emptyList())
                    val eng = if (bars.size >= 30) {
                        runCatching { DataCache.engine(code, "qfq", bars) }.getOrNull()
                    } else null
                    NewsStockCalc(
                        code = code,
                        name = q?.name,
                        price = q?.price ?: bars.lastOrNull()?.close,
                        pctChg = q?.pctChg,
                        volRatio = (eng?.factors?.volRatio),
                        localScore = eng?.centralScore ?: eng?.weightedComposite ?: eng?.composite,
                        resonanceRatio = eng?.resonanceRatio,
                        centralSummary = eng?.summary,
                        signal = when {
                            eng == null -> "insufficient"
                            (eng.centralScore ?: eng.weightedComposite ?: eng.composite) == null -> "insufficient"
                            (eng.centralScore ?: eng.weightedComposite ?: eng.composite)!! >= 65 -> "bullish"
                            (eng.centralScore ?: eng.weightedComposite ?: eng.composite)!! <= 35 -> "bearish"
                            else -> "neutral"
                        },
                        topReasons = eng?.engines
                            ?.filter { it.score != null }
                            ?.sortedByDescending { it.score }
                            ?.take(3)
                            ?.flatMap { it.reasons.take(2) }
                            ?.distinct()
                            ?: emptyList(),
                    )
                }
                NewsCalc(
                    newsTitle = title,
                    source = (item["source_name"] ?: item["source"])?.toString(),
                    ts = (item["updated"] ?: item["ctime"])?.toString(),
                    stocks = stocks,
                    sentiment = senti,
                    sentimentHits = hits,
                    impactCodes = storedCodes,
                    boards = storedBoards,
                    themes = storedThemes,
                    verdict = storedVerdict ?: verdictOf(senti, hits, title, stocks.mapNotNull { it.pctChg }.maxOrNull()),
                    direction = item["direction"]?.toString()?.takeIf { it.isNotBlank() } ?: senti,
                    positiveReason = localPositiveReason,
                    negativeReason = localNegativeReason,
                    imageUrl = imageUrlOf(item),
                    status = "ok",
                )
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }
    }

    private fun imageUrlOf(item: Map<String, Any?>): String? {
        val candidates = listOf("image", "img", "cover", "image_url", "images")
        return candidates.asSequence().flatMap { key ->
            val value = item[key]
            when (value) {
                is String -> sequenceOf(value)
                is List<*> -> value.asSequence().mapNotNull { it?.toString() }
                else -> emptySequence()
            }
        }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
    }
}
