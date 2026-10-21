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
    val status: String = "ok",       // ok | no_stock | insufficient
    val note: String? = null,
)

data class NewsStockCalc(
    val code: String,
    val name: String?,
    val price: Double?,
    val pctChg: Double?,
    val volRatio: Double?,
    val localScore: Double?,        // 本地引擎综合分（实时算）
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

object NewsCalcEngine {

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
        val codeHit = Regex("(hk)?\\d{4,6}").find(title)?.value
        if (codeHit != null) SkappApi.normalize(codeHit)?.let { hits.add(it) }
        if (hits.isEmpty()) {
            symbols.asSequence()
                .filter { it.name != null && it.name.length >= 3 }
                .sortedByDescending { it.name!!.length }
                .firstOrNull { title.contains(it.name!!) }
                ?.let { SkappApi.normalize(it.code)?.let { c -> hits.add(c) } }
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
    ): List<NewsCalc> = coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                val title = (item["title"] ?: item["digest"])?.toString() ?: ""
                val (senti, hits) = sentimentOf(title)
                val codes = resolveStocks(title, symbols)
                if (codes.isEmpty()) {
                    return@async NewsCalc(
                        newsTitle = title,
                        source = (item["source_name"] ?: item["source"])?.toString(),
                        ts = (item["updated"] ?: item["ctime"])?.toString(),
                        sentiment = senti,
                        sentimentHits = hits,
                        status = "no_stock",
                        note = "标题未提及具体股票，无个股计算",
                    )
                }
                val stocks = codes.map { code ->
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
                        localScore = eng?.composite,
                        signal = when {
                            eng == null -> "insufficient"
                            eng.composite == null -> "insufficient"
                            eng.composite >= 65 -> "bullish"
                            eng.composite <= 35 -> "bearish"
                            else -> "neutral"
                        },
                        topReasons = eng?.engines
                            ?.filter { it.score != null }
                            ?.sortedByDescending { it.score }
                            ?.take(2)
                            ?.flatMap { it.reasons.take(1) }
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
                    status = "ok",
                )
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }
    }
}
