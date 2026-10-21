package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * stocks-holo 仓客户端 —— 网页版个股详情的真正数据源。
 *
 * 仓：jiegengxiangjianxue1/stocks-holo，一股一目录（sh600000 / sz000001 / hk00700），
 * 每目录 8 个文件：
 *   holo.json       摘要（现价/涨跌/市值/PE/PB）
 *   kline.json      日 K（本站点实测 81746 字节 ≈ 上千根）
 *   technical.json  技术面（MA/MACD/RSI/KDJ 等）
 *   finance.json    财务
 *   moneyflow.json  资金流历史
 *   news.json       该股新闻
 *   sentiment.json  情绪
 *   meta.json       元信息（覆盖状态/数据时点）
 *
 * 这是之前最大的缺口：app 没读这个仓，所以多维评分价格与真实价不一致、
 * 技术参数/k线/因子都缺输入。
 */
object HoloClient {

    // GitHub raw 在部分网络下不稳，contents API 更可靠；带 token 还能读私有仓
    private const val API = "https://api.github.com/repos"
    private const val REPO = "jiegengxiangjianxue1/stocks-holo"

    // token 由 app 设置页配置；不填时读公开仓的公开文件
    private var token: String? = null
    fun setToken(t: String?) { token = t?.takeIf { it.isNotBlank() } }

    /** sh600519 / sz000001 / hk00700 —— holo 仓用带前缀代码做目录名 */
    fun dirName(code: String): String = SkappApi.prefixed(code)

    /** 文件路径 → 文本。优先 contents API；raw 兜底（有 token 时也行）。 */
    private fun getText(url: String, charset: String = "utf-8"): String? {
        // url 形如 raw 或 API; 统一走 API（更稳）
        val apiUrl = when {
            url.contains("raw.githubusercontent.com") -> {
                // raw.githubusercontent.com/owner/repo/main/path → api.github.com/repos/owner/repo/contents/path
                url.replace("https://raw.githubusercontent.com/", "$API/")
                    .replace("/main/", "/contents/")
                    .replace("/master/", "/contents/")
            }
            else -> url
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "skapp-android")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Accept", "application/vnd.github.raw")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val cs = if (charset == "gbk") Charset.forName("GBK") else Charsets.UTF_8
            BufferedReader(InputStreamReader(conn.inputStream, cs)).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject? =
        getText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getArr(url: String): JSONArray? =
        getText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** 该股在 holo 仓是否被覆盖 */
    suspend fun exists(code: String): Boolean = withContext(Dispatchers.IO) {
        val d = dirName(code)
        val conn = runCatching {
            (URL("$API/$REPO/contents/$d/meta.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10000
                readTimeout = 10000
            }
        }.getOrNull()
        runCatching { conn?.responseCode == HttpURLConnection.HTTP_OK }.getOrDefault(false)
            .also { runCatching { conn?.disconnect() } }
    }

    /** 摘要 */
    suspend fun holo(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/holo.json")?.toMap()
    }

    /** 元信息（覆盖状态/时点） */
    suspend fun meta(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/meta.json")?.toMap()
    }

    /** 技术面 */
    suspend fun technical(code: String): Map<String, Double?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/technical.json")?.toDoubleMap()
    }

    /** 财务 */
    suspend fun finance(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/finance.json")?.toMap()
    }

    /** 资金流历史 */
    suspend fun moneyflow(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val arr = getArr("$API/$REPO/contents/${dirName(code)}/moneyflow.json")
            ?: getJson("$API/$REPO/contents/${dirName(code)}/moneyflow.json")?.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMap() }
    }

    /** 该股新闻 */
    suspend fun news(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val arr = getArr("$API/$REPO/contents/${dirName(code)}/news.json")
            ?: getJson("$API/$REPO/contents/${dirName(code)}/news.json")?.optJSONArray("items")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMap() }
    }

    /** 情绪 */
    suspend fun sentiment(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/sentiment.json")?.toMap()
    }

    /**
     * 日 K。holo 仓 kline.json 可能是：
     *   {"bars":[[date,o,c,h,l,v],...]} 或 [[...],...] 或 {"kline":[...]}
     */
    suspend fun kline(code: String): List<KBar> = withContext(Dispatchers.IO) {
        val d = dirName(code)
        val txt = getText("$API/$REPO/contents/$d/kline.json") ?: return@withContext emptyList()
        val arr: JSONArray? = runCatching { JSONArray(txt) }.getOrNull()
            ?: runCatching {
                JSONObject(txt).let { o ->
                    o.optJSONArray("bars") ?: o.optJSONArray("kline") ?: o.optJSONArray("data")
                }
            }.getOrNull()
        if (arr == null || arr.length() == 0) return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i) ?: return@mapNotNull null
            if (r.length() < 6) return@mapNotNull null
            val date = r.optString(0).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            KBar(
                date = date,
                open = r.optDouble(1),
                close = r.optDouble(2),
                high = r.optDouble(3),
                low = r.optDouble(4),
                vol = r.optDouble(5),
            )
        }
    }

    /** 一次拉全（并行）——详情页用 */
    suspend fun full(code: String): HoloBundle = coroutineScope {
        val metaD = async(Dispatchers.IO) { meta(code) }
        val holoD = async(Dispatchers.IO) { holo(code) }
        val techD = async(Dispatchers.IO) { technical(code) }
        val finD = async(Dispatchers.IO) { finance(code) }
        val flowD = async(Dispatchers.IO) { moneyflow(code) }
        val newsD = async(Dispatchers.IO) { news(code) }
        val sentiD = async(Dispatchers.IO) { sentiment(code) }
        val klD = async(Dispatchers.IO) { kline(code) }
        HoloBundle(
            code = code,
            meta = metaD.await(),
            holo = holoD.await(),
            technical = techD.await(),
            finance = finD.await(),
            moneyflow = flowD.await(),
            news = newsD.await(),
            sentiment = sentiD.await(),
            kline = klD.await(),
        )
    }
}

data class HoloBundle(
    val code: String,
    val meta: Map<String, Any?>? = null,
    val holo: Map<String, Any?>? = null,
    val technical: Map<String, Double?>? = null,
    val finance: Map<String, Any?>? = null,
    val moneyflow: List<Map<String, Any?>> = emptyList(),
    val news: List<Map<String, Any?>> = emptyList(),
    val sentiment: Map<String, Any?>? = null,
    val kline: List<KBar> = emptyList(),
) {
    val covered: Boolean get() = meta != null || holo != null || kline.isNotEmpty()
}

/** JSONObject → Double map（本地小工具，避免依赖 SkappApi 的私有扩展） */
private fun JSONObject.toDoubleMap(): Map<String, Double?> {
    val out = LinkedHashMap<String, Double?>()
    keys().forEach { k ->
        out[k] = when (val v = opt(k)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
    return out
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    keys().forEach { k ->
        out[k] = when (val v = opt(k)) {
            is JSONObject -> v.toMap()
            is JSONArray -> (0 until v.length()).map { i -> v.opt(i) }
            else -> v
        }
    }
    return out
}
