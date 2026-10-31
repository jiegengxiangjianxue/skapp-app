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
 *
 * `reasoning` 是给用户看的分析思路（识股 → 判定依据 → 行情/板块 → 数据缺口）。
 * 之前只给一句「无明确异动 · 方向待确认」，用户看不出为什么这么判、缺了什么数据。
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
    val status: String = "ok",       // ok | no_stock | board_only | symbols_missing
    val note: String? = null,
    /** 分析思路：逐行说明识股、判定依据、行情与缺口，界面按行展示 */
    val reasoning: List<String> = emptyList(),
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

/** 一次情绪打分：多空词分开保留，判定与理由都要能说清「命中了什么」。 */
data class NewsSentiment(val senti: String, val pos: List<String>, val neg: List<String>) {
    val hits: List<String> get() = (pos + neg).distinct()
}

/** 情绪词典（规则式，与站点 news_impact 的规则式情感一致） */
private val POS_WORDS = listOf(
    "利好", "超预期", "大增", "增长", "上涨", "突破", "新高", "涨停", "获批", "中标",
    "合作", "扩产", "回购", "增持", "盈利", "创新高", "景气", "放量", "反弹", "走强",
    "扭亏", "预增", "投产", "量产", "签约", "订单", "回款", "增资", "复牌", "摘帽", "翻倍",
    "bonus", "beat", "upgrade",
)
private val NEG_WORDS = listOf(
    "利空", "下滑", "下降", "亏损", "下跌", "破发", "跌停", "处罚", "违规", "退市",
    "减持", "解禁", "下调", "风险", "爆雷", "造假", "问询", "停产", "裁员", "疲软",
    "预亏", "首亏", "计提", "商誉减值", "诉讼", "冻结", "承压", "破净", "退市风险",
    "miss", "downgrade",
)

private val TRUE_POS_WORDS = listOf(
    "中标", "签约", "订单", "回购", "增持", "扭亏", "预增", "获批", "量产", "投产",
)
private val RISK_NEG_WORDS = listOf(
    "减持", "解禁", "立案", "处罚", "退市", "造假", "爆雷", "问询", "风险警示", "破产", "停产", "终止",
)

/** 6 位 A 股代码或 hk + 3~5 位港股代码；前后不能贴字母数字（防止吃下年份/点位/金额） */
private val CODE_RE = Regex(
    "(?<![0-9A-Za-z])(?:hk\\d{3,5}|\\d{6})(?![0-9])",
    RegexOption.IGNORE_CASE,
)

/** 快讯里高频出现、但指向的是数据源而非个股的名称 */
private val SOURCE_NAME_BLACKLIST = setOf("同花顺", "大智慧", "choice", "wind")

/** 3 字名称后接这些词时按数据源语境处理（「同花顺数据显示」「Wind 终端」） */
private val AMBIGUOUS_NAME_SUFFIXES = listOf("数据显示", "数据", "终端")

object NewsCalcEngine {

    private const val INSUFFICIENT = "insufficient"

    /** 数据层的 "insufficient" 是「没算」占位符，不是结论，不能当 verdict/direction 覆盖本地判定。 */
    private fun Map<String, Any?>.meaningful(key: String): String? =
        this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != INSUFFICIENT }

    /**
     * 利好质量判定 + 理由：带具体词与标的实时状态（真利好 vs 追高套人）。
     * 返回的 second 直接进界面，说明「凭什么这么判」。
     */
    fun verdictDetailed(senti: String, hits: List<String>, title: String,
                        maxPct: Double?): Pair<String, String> {
        val t = title.lowercase()
        val risk = (hits.filter { h -> RISK_NEG_WORDS.any { it.equals(h, ignoreCase = true) } } +
            RISK_NEG_WORDS.filter { w -> t.contains(w.lowercase()) && hits.none { it.equals(w, true) } })
            .distinct()
        if (risk.isNotEmpty()) return "利空" to "命中风险事件词「${risk.joinToString("、")}」"
        if (senti == "neg") {
            return "利空" to if (hits.isNotEmpty()) "命中偏空词「${hits.joinToString("、")}」" else "规则判定偏空"
        }
        if (senti != "pos") {
            return "中性" to if (hits.isEmpty()) "未见多空词，按中性处理"
            else "多空词同时出现（「${hits.joinToString("、")}」），净方向不明"
        }
        val solid = TRUE_POS_WORDS.filter { t.contains(it.lowercase()) }
        if (maxPct != null && maxPct >= 8.0) {
            return "利好·但标的已大涨${
                "%.1f".format(maxPct)
            }%，防追高套人" to "标的当日已涨 ${"%.2f".format(maxPct)}%，消息落地时价格已在高位"
        }
        return if (solid.isNotEmpty()) "真利好" to "命中实质利好词「${solid.joinToString("、")}」"
        else "利好" to "命中偏多词「${hits.joinToString("、")}」，但无订单/中标类实质事件"
    }

    /** 利好质量判定（只要结论，旧签名保留） */
    fun verdictOf(senti: String, hits: List<String>, title: String,
                  maxPct: Double?): String =
        verdictDetailed(senti, hits, title, maxPct).first

    fun sentiment(text: String): NewsSentiment {
        val t = text.lowercase()
        val pos = POS_WORDS.filter { t.contains(it.lowercase()) }
        val neg = NEG_WORDS.filter { t.contains(it.lowercase()) }
        val senti = when {
            pos.size > neg.size -> "pos"
            neg.size > pos.size -> "neg"
            pos.isNotEmpty() && neg.isNotEmpty() -> "neu"
            else -> "neu"
        }
        return NewsSentiment(senti, pos, neg)
    }

    fun sentimentOf(text: String): Pair<String, List<String>> =
        sentiment(text).let { it.senti to it.hits }

    /**
     * 从标题里识别股票：先代码，后名称（用全市场 symbols 名称表）。
     * 名称路径返回**全部**命中（一条快讯可能同时提多只票）；代码路径保持「代码优先」，
     * 避免同一条快讯因名称表与代码不一致被挂上两只票。
     */
    fun resolveStocks(title: String, symbols: List<SymbolEntry>): List<String> {
        val hits = LinkedHashSet<String>()
        // 只认「6 位 A 股代码」或「hk + 3~5 位港股代码」。
        // 旧写法 (hk)?\d{4,6} 会吃下标题里的年份/点位（2026、3100），
        // 而 Code.normalize 会把 4~5 位数字 padStart 成 6 位——「2026 年」变成 002026，
        // 于是一条与股票无关的快讯被凭空挂上一只票的实时行情（编造关联）。
        CODE_RE.findAll(title).forEach { m -> Code.normalize(m.value)?.let { hits.add(it) } }
        // 代码优先：一条快讯出现多个代码时只认第一个，避免同一条挂上两只无关票
        if (hits.isNotEmpty()) return hits.take(1).toList()
        val lowered = title.lowercase()
        symbols.asSequence()
            // 跨模块 public 属性无法智能转换，先取局部值（与 AskEngine 同构，待合并）
            .mapNotNull { entry -> entry.name?.takeIf { it.length >= 3 }?.let { name -> entry to name } }
            .filter { (_, name) -> !isAmbiguousName(lowered, name) }
            .forEach { (entry, name) ->
                if (title.contains(name, ignoreCase = true)) Code.normalize(entry.code)?.let { hits.add(it) }
            }
        // 3 只上限：再多就不是「这条消息关联的个股」，多挂票会稀释可信度
        return hits.take(3).toList()
    }

    /**
     * 名称表里的数据源专名与 3 字通用名有歧义：
     * 「同花顺数据显示，昨日两融余额回升」不是同花顺（300033）的个股新闻。
     * 这类命中若挂上实时行情，就是凭空编造关联（项目红线）。
     */
    private fun isAmbiguousName(loweredTitle: String, name: String): Boolean {
        val key = name.lowercase()
        if (key in SOURCE_NAME_BLACKLIST) return true
        if (name.length != 3) return false
        val idx = loweredTitle.indexOf(key)
        if (idx < 0) return false
        return AMBIGUOUS_NAME_SUFFIXES.any { suffix -> loweredTitle.startsWith(suffix, idx + key.length) }
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
                val senti = sentiment(analysisText)
                val hits = senti.hits
                val storedVerdict = item.meaningful("verdict")
                val storedDirection = item.meaningful("direction")
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
                    ?: if (senti.senti == "pos" && hits.isNotEmpty()) "消息命中偏多词：${hits.joinToString("、")}" else null
                val localNegativeReason = item["negative_reason"]?.toString()
                    ?: if (senti.senti == "neg" && hits.isNotEmpty()) "消息命中偏空词：${hits.joinToString("、")}" else null
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

                var codes = (resolveStocks(title, symbols) + storedCodes)
                    .map { Code.digits6(it) }
                    .filter { it.length == 6 }
                    .distinct()
                // 标题没点名、云端也没给 codes 时再看正文：正文里的股票名是最后的线索
                if (codes.isEmpty() && body.isNotBlank()) {
                    codes = resolveStocks(analysisText, symbols)
                        .map { Code.digits6(it) }
                        .filter { it.length == 6 }
                        .distinct()
                }
                val codesAll = (codes + boardCodes).distinct()
                val nameOf = { code: String ->
                    symbols.firstOrNull { Code.normalize(it.code) == code }?.name
                }

                // 代码表与候选代码都为空：识股彻底失效，必须说清原因，
                // 否则界面只显示「未提及具体股票」，用户会误以为消息里真没股票。
                if (symbols.isEmpty() && codesAll.isEmpty()) {
                    val (verdict, why) = verdictDetailed(senti.senti, hits, title, null)
                    val verdictLine = if (storedVerdict != null) "判定（数据层）：$storedVerdict"
                    else "判定：$verdict —— $why"
                    return@async NewsCalc(
                        newsTitle = title,
                        source = (item["source_name"] ?: item["source"])?.toString(),
                        ts = (item["updated"] ?: item["ctime"])?.toString(),
                        sentiment = senti.senti,
                        sentimentHits = hits,
                        impactCodes = storedCodes,
                        boards = (storedBoards + matchedBoards).distinct(),
                        themes = storedThemes,
                        boardChg = boardChg,
                        boardMomentum = boardMomentum,
                        verdict = storedVerdict ?: verdict,
                        direction = storedDirection ?: senti.senti,
                        positiveReason = localPositiveReason,
                        negativeReason = localNegativeReason,
                        imageUrl = imageUrlOf(item),
                        status = "symbols_missing",
                        note = "全市场代码表未加载（0 条）：股票名无法对到代码，个股与行情待恢复后补算。",
                        reasoning = buildList {
                            add("识股：代码表为空（0 条），标题/正文里的股票名匹配不到代码")
                            add(verdictLine)
                            if (matchedBoards.isNotEmpty()) {
                                add("板块：${matchedBoards.joinToString("、")}（板块内个股待代码表恢复）")
                            }
                            add("缺口：重开应用会重试拉取代码表，成功后本条自动补算")
                        },
                    )
                }

                if (codesAll.isEmpty()) {
                    val (verdict, why) = verdictDetailed(senti.senti, hits, title, null)
                    val verdictLine = if (storedVerdict != null) "判定（数据层）：$storedVerdict"
                    else "判定：$verdict —— $why"
                    return@async NewsCalc(
                        newsTitle = title,
                        source = (item["source_name"] ?: item["source"])?.toString(),
                        ts = (item["updated"] ?: item["ctime"])?.toString(),
                        sentiment = senti.senti,
                        sentimentHits = hits,
                        impactCodes = storedCodes,
                        boards = (storedBoards + matchedBoards).distinct(),
                        themes = storedThemes,
                        boardChg = boardChg,
                        boardMomentum = boardMomentum,
                        verdict = storedVerdict ?: verdict,
                        direction = storedDirection ?: senti.senti,
                        positiveReason = localPositiveReason,
                        negativeReason = localNegativeReason,
                        imageUrl = imageUrlOf(item),
                        status = if (matchedBoards.isEmpty()) "no_stock" else "board_only",
                        note = if (matchedBoards.isEmpty()) "标题未提及具体股票与板块，无本地计算"
                        else "命中板块 ${matchedBoards.joinToString("、")}" +
                            (boardMomentum?.let { "（板块均涨 ${"%.2f".format(it)}%）" } ?: "（板块涨幅暂无数据）") +
                            "，板块内个股 ${boardCodes.size} 只待盘中确认",
                        reasoning = buildList {
                            add("识股：标题与正文未匹配到 A 股代码表内的股票名或代码（代码表 ${symbols.size} 条已加载；港股/美股名不在表内）")
                            add(verdictLine)
                            if (matchedBoards.isNotEmpty()) {
                                val chg = boardMomentum?.let { "，板块均涨 ${"%.2f".format(it)}%" } ?: "，板块涨幅暂无"
                                add("板块：${matchedBoards.joinToString("、")}$chg，板块内 ${boardCodes.size} 只个股待确认")
                            } else {
                                add("缺口：标题未指向个股或板块，本条只能做文字层面的方向判断")
                            }
                        },
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
                        name = q?.name ?: nameOf(code),
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
                val maxPct = stocks.mapNotNull { it.pctChg }.maxOrNull()
                val (verdict, verdictWhy) = verdictDetailed(senti.senti, hits, title, maxPct)
                // 数据层给了判定就以它为准，理由行也按数据层口径写，避免「显示利好、理由写中性」
                val verdictLine = if (storedVerdict != null) "判定（数据层）：$storedVerdict"
                else "判定：$verdict —— $verdictWhy"
                NewsCalc(
                    newsTitle = title,
                    source = (item["source_name"] ?: item["source"])?.toString(),
                    ts = (item["updated"] ?: item["ctime"])?.toString(),
                    stocks = stocks,
                    sentiment = senti.senti,
                    sentimentHits = hits,
                    impactCodes = storedCodes,
                    boards = (matchedBoards + storedBoards).distinct(),
                    themes = storedThemes,
                    boardChg = boardChg,
                    boardMomentum = boardMomentum,
                    verdict = storedVerdict ?: verdict,
                    direction = storedDirection ?: senti.senti,
                    positiveReason = localPositiveReason,
                    negativeReason = localNegativeReason,
                    imageUrl = imageUrlOf(item),
                    status = "ok",
                    note = if (stocks.any { it.pctChg == null }) "部分标的行情未取到（行情接口未返回），结论以已有数据为准" else null,
                    reasoning = buildList {
                        add("识股：" + codes.joinToString("、") { c -> nameOf(c)?.let { "$it($c)" } ?: c })
                        add(verdictLine)
                        add("行情：" + stocks.joinToString("；") { s ->
                            val pct = s.pctChg?.let { "%+.2f%%".format(it) } ?: "行情未取到"
                            val score = s.localScore?.let { "引擎 ${"%.1f".format(it)}" } ?: "引擎分未就绪"
                            "${s.name ?: s.code} $pct · $score"
                        })
                        if (matchedBoards.isNotEmpty()) {
                            val chg = boardMomentum?.let { "，均涨 ${"%.2f".format(it)}%" } ?: "，涨幅暂无"
                            add("板块：${matchedBoards.joinToString("、")}$chg")
                        }
                    },
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
