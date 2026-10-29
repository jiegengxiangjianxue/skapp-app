package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 指数日 K 通道 —— 给端上 [RegimeGate] 供数。
 *
 * ## 为什么需要它（实测结论，2026-10-29）
 * 1. holo 仓 **没有指数**：`stocks-holo/main/{sh000001,sh000300,sz399001}/kline.json` 全部 404。
 *    所以 [HoloClient.kline] 这条路对指数是死的。
 * 2. 腾讯 `fqkline/get?param=sh000001,day,,,60,qfq` → 200，返回数组键是 **`day`**
 *    （指数没有复权口径，即使传了 qfq 也只给 `day`）。这正是 [MarketDataClient.tencentKline]
 *    只认 `qfqday` 时会拿空表的原因——不能直接复用个股的日 K 入口。
 * 3. 东财 `push2his/api/qt/stock/kline/get?secid=1.000001` → 200，`klines` 为
 *    `"date,open,close,high,low,vol"` 逗号串。
 * 两个源都实测可用，故这里做成「腾讯 → 东财」双源，任一个失败就换另一个。
 *
 * ## 口径
 * 指数无复权概念，返回的就是原始点位；数据结构复用 [KBar]，`vol` 为手数/成交量（不参与 regime 判定）。
 */
object IndexDaily {

    const val DEFAULT_INDEX = "sh000001"

    private const val TIMEOUT = 15000

    data class Result(val code: String, val bars: List<KBar>, val source: String) {
        val closes: List<Double> get() = bars.map { it.close }
    }

    /**
     * 腾讯 secid 与东财 secid 的换算式：
     * sh → 1.xxxxxx，sz → 0.xxxxxx，bj → 0.xxxxxx（东财北交所也走 0）。
     * 已带前缀的（sh000001）原样用；**纯数字按个股规则补前缀**（000300 → sz000300，
     * 与沪深 300 的真实 secid `1.000300` 不同），所以调指数时一律带 sh/sz 前缀。
     */
    fun secid(code: String): String = txCode(code).let { p ->
        val market = when {
            p.startsWith("sh") -> "1"
            p.startsWith("sz") -> "0"
            p.startsWith("bj") -> "0"
            else -> "1"
        }
        val bare = p.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        "$market.$bare"
    }

    /**
     * 带市场前缀的代码。**必须先判前缀**：`SkappApi.prefixed` 对已带前缀的输入
     * （normalize 原样返回）会再补一次，得到 "szsh000001" 这种废码 —— 端上指数通道
     * 曾经就是这么静默拿空表的。
     */
    private fun txCode(code: String): String {
        val raw = code.trim().lowercase()
        val hasPrefix = raw.startsWith("sh") || raw.startsWith("sz") || raw.startsWith("bj")
        return if (hasPrefix) raw else SkappApi.prefixed(raw)
    }

    /** 腾讯：数组键固定 `day`（指数无复权）。 */
    private fun tencent(code: String, count: Int): List<KBar> {
        val tx = txCode(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tx,day,,,$count,"
        val txt = Http.getText(url, timeoutMs = TIMEOUT, referer = "https://gu.qq.com/") ?: return emptyList()
        return parseTencent(txt, tx)
    }

    /** 拆出来是为了能用真实响应做解析测试（不联网）。 */
    internal fun parseTencent(txt: String, tx: String): List<KBar> {
        val node = runCatching { JSONObject(txt) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject(tx) ?: return emptyList()
        val arr = node.optJSONArray("day") ?: node.optJSONArray("qfqday") ?: return emptyList()
        return parseArrays(arr)
    }

    /** 东财：klines 是逗号串数组。 */
    private fun eastmoney(code: String, count: Int): List<KBar> {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=${secid(code)}&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56" +
            "&klt=101&fqt=1&end=20500101&lmt=$count"
        val txt = Http.getText(url, timeoutMs = TIMEOUT, referer = "https://quote.eastmoney.com/")
            ?: return emptyList()
        return parseEastmoney(txt)
    }

    /** 拆出来是为了能用真实响应做解析测试（不联网）。 */
    internal fun parseEastmoney(txt: String): List<KBar> {
        val arr = runCatching { JSONObject(txt) }.getOrNull()
            ?.optJSONObject("data")?.optJSONArray("klines") ?: return emptyList()
        val out = ArrayList<KBar>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optString(i).split(",")
            if (p.size < 6) continue
            val o = p[1].toDoubleOrNull() ?: continue
            val c = p[2].toDoubleOrNull() ?: continue
            val h = p[3].toDoubleOrNull() ?: continue
            val l = p[4].toDoubleOrNull() ?: continue
            val v = p[5].toDoubleOrNull() ?: 0.0
            if (c <= 0.0) continue
            out += KBar(date = p[0], open = o, close = c, high = h, low = l, vol = v)
        }
        return out
    }

    private fun parseArrays(arr: JSONArray): List<KBar> {
        val out = ArrayList<KBar>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            if (r.length() < 6) continue
            val date = r.optString(0).takeIf { it.isNotEmpty() } ?: continue
            val o = r.optDouble(1, Double.NaN)
            val c = r.optDouble(2, Double.NaN)
            val h = r.optDouble(3, Double.NaN)
            val l = r.optDouble(4, Double.NaN)
            val v = r.optDouble(5, 0.0)
            if (c.isNaN() || c <= 0.0) continue
            out += KBar(date = date, open = o, close = c, high = h, low = l, vol = v)
        }
        return out
    }

    /**
     * 拉指数日 K。先腾讯后东财；两源都空则返回 null（调用方必须如实降级，
     * 不许拿 0 根或个股 K 线顶替）。
     */
    suspend fun fetch(code: String = DEFAULT_INDEX, count: Int = 320): Result? {
        val c = if (code.isBlank()) DEFAULT_INDEX else code
        val tx = runCatching { tencent(c, count) }.getOrDefault(emptyList())
        if (tx.size >= 2) return Result(c, tx.sortedBy { it.date }, "腾讯 fqkline day（指数不复权）")
        val em = runCatching { eastmoney(c, count) }.getOrDefault(emptyList())
        if (em.size >= 2) return Result(c, em.sortedBy { it.date }, "东财 push2his fqt=1（指数不复权）")
        return null
    }

    /** 只要收盘序列（regime 判定输入）。 */
    suspend fun closes(code: String = DEFAULT_INDEX, count: Int = 320): List<Double> =
        fetch(code, count)?.closes ?: emptyList()
}
