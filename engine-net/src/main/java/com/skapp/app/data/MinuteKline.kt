package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分钟级实时 K 线。
 *
 * 源取舍（2026-10 容器内实测）：
 *   - 东财 kline 的 klt=5/15/30/60 一律 `{"rc":102,"data":null}`，不可用；
 *   - 新浪 CN_MarketDataService.getKLineData 返回 `day` 带完整时间，无外层包装，解析最干净，作主源；
 *   - 腾讯 ifzq mkline 返回 code=0 + 数组，作兜底（同一家日 K 已用过，字段约定一致）。
 *   - 1 分钟：新浪 scale=1 实测返回带 amount 的完整分钟序列（2026-10-30 容器内验证），
 *     腾讯 m1 虽返回 code=0，但 data.<code> 下挂了行情快照，分钟数组需另取，故仍以新浪为主源。
 *
 * 盘中最后一根是**未完成 bar**：调用方要拿它做统计必须自己剔除，否则量能与指标都被半根柱子拉低。
 */
object MinuteKline {

    enum class Period(val scale: Int, val tx: String) {
        M1(1, "m1"), M5(5, "m5"), M15(15, "m15"), M30(30, "m30"), M60(60, "m60")
    }

    suspend fun fetch(code: String, period: Period = Period.M5, count: Int = 240): List<KBar> =
        withContext(Dispatchers.IO) {
            val tx = txCode(code) ?: return@withContext emptyList()
            sina(tx, period, count).ifEmpty { tencent(tx, period.tx, count) }
        }

    private fun sina(tx: String, period: Period, count: Int): List<KBar> = runCatching {
        val url = "https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_=/CN_MarketDataService.getKLineData" +
            "?symbol=$tx&scale=${period.scale}&ma=no&datalen=$count"
        parseSina(get(url))
    }.getOrDefault(emptyList())

    private fun tencent(tx: String, key: String, count: Int): List<KBar> = runCatching {
        parseTencent(get("https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=$tx,$key,,$count"), tx, key)
    }.getOrDefault(emptyList())

    /** 新浪 JSONP：`var _=([{"day":"2026-10-30 11:10:00",...}]);`，只取数组部分再按 JSON 解析。 */
    internal fun parseSina(txt: String?): List<KBar> {
        if (txt.isNullOrBlank()) return emptyList()
        val start = txt.indexOf("([")
        val end = txt.lastIndexOf("])")
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { JSONArray(txt.substring(start + 1, end + 1)) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            bar(
                date = o.optString("day"),
                open = o.optString("open"),
                close = o.optString("close"),
                high = o.optString("high"),
                low = o.optString("low"),
                vol = o.optString("volume"),
            )
        }
    }

    /**
     * 腾讯 mkline：`data.<code>.<key>[i] = [时间, 开, 收, 高, 低, 量, ...]`。
     * 顺序沿用腾讯 fqkline 的既有约定（开/收/高/低/量），与日 K 解析保持一致。
     */
    internal fun parseTencent(txt: String?, tx: String, key: String): List<KBar> {
        if (txt.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONObject(tx)?.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            if (a.length() < 6) return@mapNotNull null
            bar(
                date = a.optString(0),
                open = a.optString(1),
                close = a.optString(2),
                high = a.optString(3),
                low = a.optString(4),
                vol = a.optString(5),
            )
        }
    }

    private fun bar(date: String, open: String, close: String, high: String, low: String, vol: String): KBar? {
        val o = open.toDoubleOrNull() ?: return null
        val c = close.toDoubleOrNull() ?: return null
        val h = high.toDoubleOrNull() ?: return null
        val l = low.toDoubleOrNull() ?: return null
        if (date.isBlank() || c <= 0.0) return null
        return KBar(date = date, open = o, close = c, high = h, low = l, vol = vol.toDoubleOrNull() ?: 0.0)
    }

    /** 6 位代码 → 腾讯符号；已带前缀的原样返回。 */
    internal fun txCode(code: String): String? {
        val c = code.trim().lowercase()
        if (c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj")) return c
        val d = c.filter { it.isDigit() }
        if (d.length != 6) return null
        return when (d.first()) {
            '6', '5', '9' -> "sh$d"
            '4', '8' -> "bj$d"
            else -> "sz$d"
        }
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                setRequestProperty("Referer", "https://finance.sina.com.cn/")
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
