package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 日 K 线一根 */
data class KBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val vol: Double,
)

/**
 * K 线来源：腾讯前复权日 K（app 详情页画图用）。
 * 与站点 stock-detail.js 的直连兜底同源；失败返回空列表，UI 显示「暂无数据」。
 */
object KlineApi {

    suspend fun fetchDayKline(code: String, count: Int = 320): List<KBar> = withContext(Dispatchers.IO) {
        val p = SkappApi.prefixed(code)
        val url = RemoteSources.tencentKline(p, count)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "skapp-android")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val node = root.optJSONObject("data")?.optJSONObject(p) ?: return@withContext emptyList()
            val arr = node.optJSONArray("qfqday") ?: node.optJSONArray("day")
                ?: return@withContext emptyList()
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
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }
}
