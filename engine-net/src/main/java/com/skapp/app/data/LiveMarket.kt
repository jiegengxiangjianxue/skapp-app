package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 实时全市场行情源（东财 clist，盘中真实盘口）。
 *
 * ## 为什么单独拆出来
 *
 * 原先「拉全市场快照」这段逻辑写在 `LocalScreener.marketSnapshot` 里，结果被三处各拉一遍：
 * 选股、策略卡、界面。盘中一次刷新就是三次十几页的重复请求，而且每处拿到的**时点还不一样**
 * （同一屏里「全市场 5000 只」和「筛选池」可能隔着十几秒）。
 *
 * 现在统一在这里拉一次、共享一份短 TTL 缓存：同一次刷新里所有分析看到的是**同一个时点**，
 * 也把请求数从 3×N 压到 1×N。
 *
 * ## 数据口径
 *
 * - 只允许实时接口。云端快照不在这里出现——本层是「链路」，不是「库」。
 * - 涨跌停判定按涨跌幅近似（主板 ±10%、ST ±5%、创业板/科创板 ±20%）：
 *   clist 不直接给涨停价，精确判定要再拉一次涨停价表，代价不划算，这里近似并在字段上写明。
 *
 * ## 缓存
 *
 * 盘中 5s（够一屏用，且不会把「刚拉到的」重复拉）、非盘中 60s。
 */
object LiveMarket {

    /** 一行实时盘口。字段名与东财 clist 对应关系写在每个属性后面。 */
    data class Row(
        val code: String,
        val name: String?,
        val price: Double?,        // f2  最新价
        val pctChg: Double?,       // f3  涨跌幅 %
        val volumeLots: Double?,   // f5  成交量（手）
        val amount: Double?,       // f6  成交额（元）
        val amplitude: Double?,    // f7  振幅 %
        val turnoverRate: Double?, // f8  换手率 %
        val pe: Double?,           // f9  市盈率（动）
        val volRatio: Double?,     // f10 量比
        val mv: Double?,           // f20 总市值（元）
        val mainNet: Double?,      // f62 主力净流入（元）
        val ts: Long,              // 抓取时刻
    ) {
        /** 涨跌幅上限：主板 10%（ST 5%）、创业板/科创板 20%、北交所 30%。 */
        val limitCap: Double
            get() {
                val base = when {
                    code.startsWith("300") || code.startsWith("301") || code.startsWith("688") -> 20.0
                    code.startsWith("8") || code.startsWith("4") || code.startsWith("92") -> 30.0
                    else -> 10.0
                }
                return if (base == 10.0 && name != null && name.contains("ST", ignoreCase = true)) 5.0 else base
            }

        /**
         * 涨停判定的抖动余量。
         *
         * 涨停价按「昨收×(1+上限) 四舍五入到分」定，于是显示出来的涨幅天然偏离上限：
         * 10cm 常见 9.8~10.0、20cm 常见 19.7~20.0、30cm 常见 29.5~30.0。
         * 余量因此按上限的 3.5% 取（10→0.35 / 20→0.7 / 30→1.05），
         * 而不是写死 0.35——写死的话 30cm 板几乎永远判不出涨停。
         * ST 的 5cm 板另设 0.2 下限：低价 ST 股四舍五入后涨幅可能只有 4.8%。
         */
        private val limitSlack: Double get() = (limitCap * 0.035).coerceAtLeast(0.2)

        /** 是否涨停。 */
        val atLimitUp: Boolean
            get() {
                val c = pctChg ?: return false
                return c >= limitCap - limitSlack
            }

        /** 是否跌停（判定与上涨同理）。 */
        val atLimitDown: Boolean
            get() {
                val c = pctChg ?: return false
                return c <= -(limitCap - limitSlack)
            }

        /** 主板 60/00/000 才算「可交易主板」，其余（创业板/科创板/北交所）默认排除。 */
        val mainBoard: Boolean
            get() = code.length == 6 && (code.startsWith("60") || code.startsWith("00")) &&
                !code.startsWith("300") && !code.startsWith("301") && !code.startsWith("688")
    }

    private const val PAGE_SIZE = 500
    private const val MAX_PAGES = 40

    @Volatile private var cached: List<Row> = emptyList()
    @Volatile private var cachedAt = 0L

    private fun ttlMs(): Long = if (MarketHours.isTrading() || MarketHours.isAuction()) 5_000L else 60_000L

    /**
     * 全市场实时快照。
     *
     * @param force 跳缓存（用户手动刷新时用）
     * @return 真实抓取结果；网络全挂时返回空列表（**不返回假数据**，由上层显示「暂无数据」）
     */
    suspend fun snapshot(force: Boolean = false): List<Row> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - cachedAt < ttlMs()) return@withContext cached
        val fetched = fetchAll()
        if (fetched.isNotEmpty()) {
            cached = fetched
            cachedAt = now
            return@withContext fetched
        }
        // 盘中拉不到时宁可给上一份，并保留真实抓取时间，让上层能标「陈旧」。
        cached
    }

    private fun fetchPage(page: Int): List<Row> {
        val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=$page&pz=$PAGE_SIZE&po=1&fid=f3" +
            "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048" +
            "&fields=f2,f3,f5,f6,f7,f8,f9,f10,f12,f14,f20,f62"
        val txt = httpGet(url) ?: return emptyList()
        val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
        val ts = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("f12").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Row(
                code = code,
                name = o.optString("f14").takeIf { it.isNotEmpty() },
                price = o.num("f2"),
                pctChg = o.num("f3"),
                volumeLots = o.num("f5"),
                amount = o.num("f6"),
                amplitude = o.num("f7"),
                turnoverRate = o.num("f8"),
                pe = o.num("f9"),
                volRatio = o.num("f10"),
                mv = o.num("f20"),
                mainNet = o.num("f62"),
                ts = ts,
            )
        }
    }

    private fun fetchAll(): List<Row> {
        val out = LinkedHashMap<String, Row>()
        var page = 1
        while (page <= MAX_PAGES) {
            val batch = fetchPage(page)
            if (batch.isEmpty()) break
            batch.forEach { out.putIfAbsent(it.code, it) }
            if (batch.size < PAGE_SIZE) break
            page++
        }
        if (out.isEmpty()) return emptyList()
        // 北交所/港股通等混在里面，按 6 位纯数字代码归一（保留原始码，纯位数判断由 mainBoard 做）。
        return out.values.filter { it.code.length == 6 && it.code.all(Char::isDigit) }
    }

    private fun JSONObject.num(key: String): Double? {
        val v = opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString().toDoubleOrNull()
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
            setRequestProperty("Referer", "https://wap.eastmoney.com/")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            // 东财会按 gzip 返回：不解压就是乱码（原来 LocalScreener 里的桥已经踩过这个坑）
            val enc = conn.contentEncoding
            val stream = if (enc == "gzip" || enc == "x-gzip")
                java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
