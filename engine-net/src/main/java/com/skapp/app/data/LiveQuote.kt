package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时报价（自选 / 推股 / 决策卡补价用）。
 *
 * ## 为什么不直接打单票接口
 *
 * 自选 30 只、推股 10 只、决策 10 只——按原实现每个板块各打一次批量报价接口，
 * 一轮刷新 3 次请求，还互相不知道对方拉过什么。而 [LiveMarket.snapshot] 本来就是
 * **全市场**盘口（缓存内），这些票几乎必然已经在里面。
 *
 * 所以顺序是：先在全市场快照里查（零额外请求）→ 查不到的（新股/停牌/港股）才批量补拉一次。
 * 时间戳统一换成 ISO 字符串，界面的「报价超过 10 分钟算陈旧」判断才有效。
 */
object LiveQuote {

    private val iso get() = CstTime.fmt("yyyy-MM-dd HH:mm:ss")

    /** 全市场快照行 → 报价模型。 */
    fun quoteOf(row: LiveMarket.Row): Quote = Quote(
        code = row.code,
        name = row.name.orEmpty(),
        price = row.price,
        pctChg = row.pctChg,
        ts = iso.format(Date(row.ts)),
    )

    /**
     * 批量报价：先吃快照，剩下真没有的再补拉。
     *
     * @return 6 位归一化代码 → 报价
     */
    suspend fun quotes(codes: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        val wanted = codes.mapNotNull { Code.normalize(it) }.distinct()
        if (wanted.isEmpty()) return@withContext emptyMap()

        val out = LinkedHashMap<String, Quote>()
        val rows = runCatching { LiveMarket.snapshot() }.getOrDefault(emptyList()).associateBy { it.code }
        for (code in wanted) {
            rows[code]?.let { out[code] = quoteOf(it) }
        }

        val missing = wanted.filter { it !in out }
        if (missing.isNotEmpty()) {
            // 补拉只针对快照覆盖不到的少数票（港股、停牌、刚上市）
            runCatching { SkappApi.fetchQuotes(missing.map { Code.prefixed(it) }) }
                .getOrDefault(emptyMap())
                .forEach { (rawCode, q) ->
                    val code = Code.normalize(rawCode) ?: return@forEach
                    out[code] = q
                }
        }
        out
    }
}
