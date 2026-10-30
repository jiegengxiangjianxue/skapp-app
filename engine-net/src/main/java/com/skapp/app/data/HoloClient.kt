package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    // raw 在部分网络下是整段不通（实测 15s 零字节），而同一份文件走 contents API 0.78s 就回。
    // 详情页要连读 technical/finance/moneyflow/news 多个文件，raw 不通时每个都白等一个满超时，
    // 所以 raw 只给 3s；失败一次就把通道粘到 contents API，冷却期内不再试 raw。
    private const val RAW_TIMEOUT_MS = 3_000
    private const val RAW_KLINE_TIMEOUT_MS = 10_000
    private const val RAW_COOLDOWN_MS = 10 * 60 * 1000L
    @Volatile private var rawDownUntil = 0L

    // token 由 app 设置页配置；不填时读公开仓的公开文件
    private var token: String? = null
    fun setToken(t: String?) { token = t?.takeIf { it.isNotBlank() } }

    /** sh600519 / sz000001 / hk00700 —— holo 仓用带前缀代码做目录名 */
    fun dirName(code: String): String = SkappApi.prefixed(code)

    /** 文件路径 → 文本。raw 优先（公开仓免鉴权、CDN 不限制 60 次/时）；失败才走 contents API（有 token 时用）。
     *  2026-10-24 修：原实现强制走 contents API，无 token 时 60 次/时限流导致详情页财务/资金流/新闻全空。 */
    private fun getText(url: String, charset: String = "utf-8"): String? {
        val rawUrl = when {
            url.startsWith("$API/") ->
                url.removePrefix("$API/").let { rest ->
                    val i = rest.indexOf("/contents/")
                    if (i < 0) url else "https://raw.githubusercontent.com/${rest.substring(0, i)}/main/${rest.substring(i + 10)}"
                }
            else -> url
        }
        val apiUrl = when {
            rawUrl.contains("raw.githubusercontent.com") ->
                rawUrl.replace("https://raw.githubusercontent.com/", "$API/")
                    .replace("/main/", "/contents/")
                    .replace("/master/", "/contents/")
            else -> null
        }
        val stickyApi = apiUrl?.takeIf { System.currentTimeMillis() < rawDownUntil }
        // 粘性期仍保留 raw 兜底：contents API 也会 404/限流，单通道独木会让冷却期内一直空
        val targets = if (stickyApi != null) listOfNotNull(stickyApi, rawUrl) else listOfNotNull(rawUrl, apiUrl)
        for (target in targets) {
            val isRaw = !target.startsWith("$API")
            val p = fetchProbe(target, charset, !isRaw, rawTimeout(isRaw, target))
            if (p.body != null) return p.body
            // 只有网络层失败才判 raw 死（-1 连接/超时、5xx 网关）；404/403 是文件级问题，
            // 换通道同样没有，连坐整条 raw 会让冷却期内所有文件都白等一个满超时
            if (isRaw && (p.code == -1 || p.code >= 500)) {
                rawDownUntil = System.currentTimeMillis() + RAW_COOLDOWN_MS
            }
        }
        return null
    }

    private fun fetchProbe(url: String, charset: String, withAuth: Boolean, timeout: Int): Http.Probe {
        val headers = buildMap {
            if (withAuth) {
                token?.let { put("Authorization", "Bearer $it") }
                put("Accept", "application/vnd.github.raw")
            }
        }
        return Http.probe(
            url = url,
            charset = Http.charset(charset),
            timeoutMs = timeout,
            ua = "skapp-android",
            headers = headers,
        )
    }

    /** kline.json 比 technical/finance 大一个量级，3s 常常只够建连，单独给 10s */
    private fun rawTimeout(isRaw: Boolean, url: String): Int = when {
        !isRaw -> RAW_TIMEOUT_MS * 5
        url.endsWith("/kline.json") -> RAW_KLINE_TIMEOUT_MS
        else -> RAW_TIMEOUT_MS
    }

    private fun getJson(url: String): JSONObject? =
        getText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getArr(url: String): JSONArray? =
        getText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** 该股在 holo 仓是否被覆盖 */
    suspend fun exists(code: String): Boolean = withContext(Dispatchers.IO) {
        val d = dirName(code)
        // raw HEAD 免鉴权，token 缺失时也能判断
        val conn = runCatching {
            (URL("https://raw.githubusercontent.com/$REPO/main/$d/meta.json").openConnection() as HttpURLConnection).apply {
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
        getJson("$API/$REPO/contents/${dirName(code)}/holo.json")?.toPlainMap()
    }

    /** 元信息（覆盖状态/时点） */
    suspend fun meta(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/meta.json")?.toPlainMap()
    }

    /** 技术面 */
    suspend fun technical(code: String): Map<String, Double?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/technical.json")?.toDoubleMap()
    }

    /** 财务 */
    suspend fun finance(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/finance.json")?.toPlainMap()
    }

    /** 资金流历史 */
    suspend fun moneyflow(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val arr = getArr("$API/$REPO/contents/${dirName(code)}/moneyflow.json")
            ?: getJson("$API/$REPO/contents/${dirName(code)}/moneyflow.json")?.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toPlainMap() }
    }

    /** 该股新闻 */
    suspend fun news(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val arr = getArr("$API/$REPO/contents/${dirName(code)}/news.json")
            ?: getJson("$API/$REPO/contents/${dirName(code)}/news.json")?.optJSONArray("items")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toPlainMap() }
    }

    /** 情绪 */
    suspend fun sentiment(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        getJson("$API/$REPO/contents/${dirName(code)}/sentiment.json")?.toPlainMap()
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

