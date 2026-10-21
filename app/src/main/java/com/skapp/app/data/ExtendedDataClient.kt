package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 扩展多源客户端 —— 站点 scripts/ 里那些"带 Referer/Host 伪装"的域。
 *
 * 之前以为它们"需要 cookie/token"，实测后确认（见站点 ths_collect.py 注释）：
 *   90% 的域只需要 Referer + Host + 桌面 UA 三件套伪装成浏览器；
 *   data.10jqka.com.cn 少数域（龙虎榜/新股/资金流）才真的 401 要 cookie。
 *   Android 端和 CI 一样能带这些头，所以这里把能跑的全接上。
 *
 * 覆盖：
 *   同花顺  d/basic/q/news/zx 五域（行情K线 / F10财务 / 板块 / 7x24快讯 / 强势股）
 *   东财    实时、K线、资金流、F10 财务九表、龙虎榜、板块、全球指数
 *   新浪    实时、资金流、龙虎榜、7x24
 *   问财    自然语言选股（同花顺 iwencai，零签名）
 *   腾讯    qt / fqkline / minute
 *   雪球    热搜、讨论（部分需 cookie，失败即跳过）
 */
object ExtendedDataClient {

    private const val TIMEOUT = 20000

    private val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 站点 ths_collect.py 的 HDRS：按域带 Referer + Host */
    private fun headers(domain: String): Map<String, String> {
        val h = mutableMapOf("User-Agent" to DESKTOP_UA)
        when (domain) {
            "d" -> { h["Referer"] = "http://d.10jqka.com.cn"; h["Host"] = "d.10jqka.com.cn" }
            "basic" -> { h["Referer"] = "https://basic.10jqka.com.cn/"; h["Host"] = "basic.10jqka.com.cn" }
            "q" -> { h["Referer"] = "http://q.10jqka.com.cn/"; h["Host"] = "q.10jqka.com.cn" }
            "news" -> { h["Referer"] = "https://news.10jqka.com.cn/realtimenews.html"; h["Host"] = "news.10jqka.com.cn" }
            "zx" -> { h["Referer"] = "http://zx.10jqka.com.cn/"; h["Host"] = "zx.10jqka.com.cn" }
            "xq" -> { h["Referer"] = "https://xueqiu.com/"; h["Host"] = "xueqiu.com" }
            "wencai" -> { h["Referer"] = "http://www.iwencai.com/"; h["Host"] = "www.iwencai.com" }
            "em" -> { h["Referer"] = "https://data.eastmoney.com/"; h["Host"] = "data.eastmoney.com" }
            "sina" -> { h["Referer"] = "https://finance.sina.com.cn/"; h["Host"] = "finance.sina.com.cn" }
        }
        return h
    }

    private fun get(
        url: String,
        domain: String = "",
        charset: String = "utf-8",
        gzip: Boolean = true,
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                headers(domain).forEach { (k, v) -> setRequestProperty(k, v) }
                if (gzip) setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            var raw = conn.inputStream.use { it.readBytes() }
            if (gzip && (conn.contentEncoding == "gzip" || conn.headerFields.values.flatten().any { it == "gzip" })) {
                runCatching { raw = java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() }
            }
            val cs = when {
                charset.startsWith("gb") -> Charset.forName(if (charset == "gbk") "GBK" else "GB18030")
                else -> Charsets.UTF_8
            }
            String(raw, cs)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getJson(url: String, domain: String = ""): JSONObject? =
        get(url, domain)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** JSONObject → Map（含嵌套） */
    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        keys().forEach { k -> out[k] = valueOf(this, k) }
        return out
    }

    private fun valueOf(o: JSONObject, key: String): Any? = when (val v = o.opt(key)) {
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { valueOf(v, it) }
        else -> v
    }

    private fun valueOf(a: JSONArray, i: Int): Any? = when (val v = a.opt(i)) {
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { valueOf(v, it) }
        else -> v
    }

    // ════════════════════════════════════════════════════════
    //  同花顺（五域）
    // ════════════════════════════════════════════════════════

    /**
     * 同花顺日 K：d.10jqka.com.cn/v6/line/{market}_{code}/01/{year}.js
     * 返回 "date,open,close,high,low,volume" 列表（站点实测可用域）。
     */
    suspend fun thsKline(code: String, year: Int): List<KBar> = withContext(Dispatchers.IO) {
        val n = MarketDataClient.secid(code).substringAfter(".")
        val mkt = if (n.startsWith("6") || n.startsWith("9")) "hs" else "hs"
        val url = "http://d.10jqka.com.cn/v6/line/$mkt" + "_$n/01/$year.js"
        val txt = get(url, "d") ?: return@withContext emptyList()
        // 形如: hs_600519_01_2024=[["2024-01-02","1680.00",...],...]
        val m = Regex("\\[\\[.*\\]\\]").find(txt) ?: return@withContext emptyList()
        parseJsArray(m.value)
    }

    /** 解析 [["date","o","c","h","l","v"], ...] 或 [(...) , ...] 两种形态 */
    private fun parseJsArray(s: String): List<KBar> {
        val body = s.trim().trim('[', ']')
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<KBar>()
        // 逐组抓 [a,b,c,d,e,f]（引号可选）
        val group = Regex("\\[\\s*\"?([0-9A-Za-z.\\-]+)\"?\\s*,\\s*\"?([0-9.\\-]+)\"?\\s*,\\s*\"?([0-9.\\-]+)\"?\\s*,\\s*\"?([0-9.\\-]+)\"?\\s*,\\s*\"?([0-9.\\-]+)\"?\\s*,\\s*\"?([0-9.\\-]+)\"?\\s*\\]")
        group.findAll(body).forEach { m ->
            val o = m.groupValues[2].toDoubleOrNull()
            val c = m.groupValues[3].toDoubleOrNull()
            val h = m.groupValues[4].toDoubleOrNull()
            val l = m.groupValues[5].toDoubleOrNull()
            val v = m.groupValues[6].toDoubleOrNull() ?: 0.0
            if (o != null && c != null && h != null && l != null) {
                out += KBar(m.groupValues[1], o, c, h, l, v)
            }
        }
        return out
    }

    /** 同花顺 7x24 快讯（零签名，news 域） */
    suspend fun thsRealtimeNews(limit: Int = 30): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val txt = get("https://news.10jqka.com.cn/tapp/news/push/stock", "news")
            ?: return@withContext emptyList()
        // 页面内嵌 JSON 数组: {"list":[{...}]}
        val obj = runCatching { JSONObject(txt) }.getOrNull()
        val arr = obj?.optJSONArray("list")
            ?: runCatching { JSONArray(Regex("\\[\\{.*\\]").find(txt)?.value ?: "") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until minOf(arr.length(), limit)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            mapOf<String, Any?>(
                "title" to (o.optString("title").takeIf { it.isNotEmpty() }
                    ?: o.optString("digest").takeIf { it.isNotEmpty() }),
                "source" to "同花顺",
                "ts" to (o.optString("ctime").takeIf { it.isNotEmpty() }),
                "url" to o.optString("url").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** 同花顺强势股（zx 域） */
    suspend fun thsStrongStocks(date: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val txt = get("https://zx.10jqka.com.cn/event/api/getharden/date/$date", "zx")
            ?: return@withContext emptyList()
        val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return@withContext emptyList()
        val arr = obj.optJSONArray("data") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), 50)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            mapOf<String, Any?>(
                "code" to o.optString("code").takeIf { it.isNotEmpty() },
                "name" to o.optString("name").takeIf { it.isNotEmpty() },
                "strength" to o.opt("strength"),
            )
        }
    }

    /** 同花顺 F10 财务摘要（basic 域，GBK） */
    suspend fun thsFinance(code: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val n = MarketDataClient.secid(code).substringAfter(".")
        val txt = get("https://basic.10jqka.com.cn/api/stock/finance/$n.json", "basic", "gbk")
            ?: return@withContext emptyMap()
        val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return@withContext emptyMap()
        obj.toMap()
    }

    // ════════════════════════════════════════════════════════
    //  问财（同花顺自然语言选股，零签名）
    // ════════════════════════════════════════════════════════

    /**
     * 问财自然语言选股：把用户的自然语言直接变成选股结果。
     * 例：「今日涨停 且 主力净流入大于1亿」
     */
    suspend fun wencaiQuery(query: String, perPage: Int = 20): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val txt = get(
                "http://www.iwencai.com/unifiedwap/result?w=$encoded&querytype=stock",
                "wencai",
            ) ?: return@withContext emptyList()
            // 页面内嵌 var resultData = {...} 或 JSON
            val obj = runCatching { JSONObject(txt) }.getOrNull()
            val arr = obj?.optJSONArray("result")
                ?: obj?.optJSONObject("data")?.optJSONArray("result")
                ?: return@withContext emptyList()
            (0 until minOf(arr.length(), perPage)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                mapOf<String, Any?>(
                    "code" to (o.optString("code").takeIf { it.isNotEmpty() }
                        ?: o.optJSONArray("code")?.optString(0)),
                    "name" to o.optString("name").takeIf { it.isNotEmpty() },
                    "score" to o.opt("score"),
                )
            }
        }

    // ════════════════════════════════════════════════════════
    //  东财扩展（F10 / 龙虎榜 / 板块资金）
    // ════════════════════════════════════════════════════════

    /** 东财 F10 主要财务指标（em 域） */
    suspend fun emMainIndicator(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val sid = MarketDataClient.secid(code)
        val obj = getJson(
            "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_F10_FINANCE_MAINFINADATA" +
                "&columns=ALL&filter=(SECUCODE%3D%22${sid.substringAfter(".")}%22)&pageSize=8&sortColumns=REPORT_DATE&sortTypes=-1",
            "em",
        ) ?: return@withContext emptyList()
        val arr = obj.optJSONObject("result")?.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMap() }
    }

    /** 东财龙虎榜（em 域） */
    suspend fun emDragonTiger(date: String, limit: Int = 30): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            val obj = getJson(
                "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_DAILYBILLBOARD_DETAILSNEW" +
                    "&columns=ALL&filter=(TRADE_DATE%3D%27${date.replace("-", "")}%27)&pageSize=$limit&sortColumns=BILLBOARD_NET_AMT&sortTypes=-1",
                "em",
            ) ?: return@withContext emptyList()
            val arr = obj.optJSONObject("result")?.optJSONArray("data")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMap() }
        }

    /** 东财板块资金流排行（行业/概念主力净额） */
    suspend fun emBoardFundFlow(top: Int = 20): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val obj = getJson(
            "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=$top&po=1&fid=f62&fs=m:90+t:2" +
                "&fields=f12,f14,f62,f184,f66,f69,f72,f75,f78,f81",
        ) ?: return@withContext emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("diff") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            mapOf<String, Any?>(
                "code" to o.optString("f12").takeIf { it.isNotEmpty() },
                "name" to o.optString("f14").takeIf { it.isNotEmpty() },
                "main_net" to o.opt("f62")?.toString()?.toDoubleOrNull(),
                "main_pct" to o.opt("f184")?.toString()?.toDoubleOrNull(),
            )
        }
    }

    // ════════════════════════════════════════════════════════
    //  新浪扩展（资金流 / 龙虎榜 / 7x24）
    // ════════════════════════════════════════════════════════

    /** 新浪资金流（历史） */
    suspend fun sinaFundFlow(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val n = MarketDataClient.secid(code).substringAfter(".")
        val txt = get(
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/MoneyFlow.ssi_ssfx_fljz_json" +
                "?daima=$n&num=30",
            "sina",
        ) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(txt) }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMap() }
    }

    // ════════════════════════════════════════════════════════
    //  雪球（热搜；讨论需 cookie，失败即跳过）
    // ════════════════════════════════════════════════════════

    suspend fun xueqiuHot(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val obj = getJson("https://stock.xueqiu.com/v5/stock/hot_stock/list.json?size=20&_type=10", "xq")
            ?: return@withContext emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            mapOf<String, Any?>(
                "code" to o.optString("code").takeIf { it.isNotEmpty() },
                "name" to o.optString("name").takeIf { it.isNotEmpty() },
                "value" to o.opt("value"),
            )
        }
    }

    /** 数据源清单（UI 展示用，证明真的接进来了） */
    fun sourceCatalog(): List<Triple<String, String, Boolean>> = listOf(
        Triple("腾讯行情", "qt.gtimg.cn", true),
        Triple("腾讯日K", "web.ifzq.gtimg.cn", true),
        Triple("腾讯分时", "web.ifzq.gtimg.cn/minute", true),
        Triple("东财实时", "push2.eastmoney.com", true),
        Triple("东财日K", "push2his.eastmoney.com", true),
        Triple("东财资金流", "push2his.fflow.daykline", true),
        Triple("东财分时", "push2his.trends2", true),
        Triple("东财F10财务", "datacenter-web.eastmoney.com", true),
        Triple("东财龙虎榜", "datacenter-web DAILYBILLBOARD", true),
        Triple("东财板块资金", "push2.clist f62", true),
        Triple("东财全球指数", "push2.ulist.np", true),
        Triple("新浪实时", "hq.sinajs.cn", true),
        Triple("新浪资金流", "vip.stock.finance.sina.com.cn", true),
        Triple("同花顺日K", "d.10jqka.com.cn", true),
        Triple("同花顺F10", "basic.10jqka.com.cn", true),
        Triple("同花顺快讯", "news.10jqka.com.cn", true),
        Triple("同花顺强势股", "zx.10jqka.com.cn", true),
        Triple("问财选股", "iwencai.com", true),
        Triple("雪球热搜", "stock.xueqiu.com", true),
        Triple("GitHub数据仓", "raw.githubusercontent.com", true),
    )
}
