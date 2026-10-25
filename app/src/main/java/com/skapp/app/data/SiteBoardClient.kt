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

/**
 * 网页版板块数据聚合 —— 把站点的「实时总结 / 多池选股 / 看板 / 消息」搬到 app。
 */
object SiteBoardClient {

    // 统一使用 RemoteSources 的主仓，Contents API 仅作为真实 fallback。
    private val API = RemoteSources.base(RemoteSources.OWNER_A, RemoteSources.REPO_A)
    private var token: String? = null
    fun setToken(t: String?) { token = t?.takeIf { it.isNotBlank() } }

    private fun getJson(url: String): JSONObject? {
        // raw 优先（免鉴权、不走 60 次/时 API 限流）；API 仅作兜底（有 token 时读私有仓）
        val raw = url
        val api = if (raw.startsWith(RemoteSources.RAW)) {
            raw.replace(RemoteSources.RAW, "https://api.github.com/repos")
                .replaceFirst("/main/", "/contents/")
                .replaceFirst("/master/", "/contents/")
        } else null
        for (u in listOfNotNull(raw, api)) {
            val r = fetchOnce(u)
            if (r != null) return r
        }
        return null
    }

    private fun fetchOnce(u: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
                if (u.startsWith("https://api.github.com/repos/")) {
                    token?.let { setRequestProperty("Authorization", "Bearer $it") }
                    setRequestProperty("Accept", "application/vnd.github.raw")
                }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val enc = conn.contentEncoding
            val stream = if (enc == "gzip" || enc == "x-gzip")
                java.util.zip.GZIPInputStream(conn.inputStream)
            else conn.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                .let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    data class Pool(
        val id: String,
        val name: String,
        val subtitle: String? = null,
        val items: List<PoolItem> = emptyList(),
        val kpis: Map<String, Any?> = emptyMap(),
        val ts: String? = null,
        val status: String = "ok",
        val reason: String? = null,
    )

    data class PoolItem(
        val code: String,
        val name: String? = null,
        val score: Double? = null,
        val price: Double? = null,
        val pctChg: Double? = null,
        val extra: Map<String, Any?> = emptyMap(),
    )

    suspend fun pools(): List<Pool> = coroutineScope {
        val goodD = async(Dispatchers.IO) { getJson("$API/good_stock_pool.json") }
        val sectorD = async(Dispatchers.IO) { getJson("$API/sector_pool.json") }
        val cycleD = async(Dispatchers.IO) { getJson("$API/cycle_pool_v2.json") }
        val lowD = async(Dispatchers.IO) { getJson("$API/lowpool.json") }
        val compD = async(Dispatchers.IO) { getJson("$API/composite_pool_v3_eom.json") }
        val limitD = async(Dispatchers.IO) { getJson("$API/limitup_attribution.json") }
        val moneyD = async(Dispatchers.IO) { getJson("$API/moneyflow.json") }

        val out = ArrayList<Pool>()

        goodD.await()?.let { o ->
            val eligibleArr = o.optJSONArray("eligible")
            val eligibleObj = o.optJSONObject("eligible")
            val items: List<PoolItem> = when {
                eligibleArr != null -> eligibleArr.toItems()
                eligibleObj != null -> {
                    val out = ArrayList<PoolItem>()
                    val keys = eligibleObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        eligibleObj.optJSONArray(k)?.let { out.addAll(it.toItems()) }
                    }
                    out
                }
                else -> emptyList()
            }
            out.add(Pool(
                id = "good",
                name = "推荐池",
                subtitle = "好股票池 · eligible",
                items = items.take(50),
                kpis = mapOf(
                    "eligible" to lenOf(o.opt("eligible")),
                    "watch" to lenOf(o.opt("watch")),
                    "reject" to lenOf(o.opt("reject")),
                ),
                ts = o.optString("asof").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("good", "推荐池", status = "insufficient", reason = "good_stock_pool.json 不可达"))

        sectorD.await()?.let { o ->
            val t1 = o.optJSONArray("tier1")?.toItems() ?: emptyList()
            val t2 = o.optJSONArray("tier2")?.toItems() ?: emptyList()
            out.add(Pool(
                id = "sector",
                name = "板块池",
                subtitle = "T1 " + o.optInt("tier1_count") + " · T2 " + o.optInt("tier2_count") + " · T3 " + o.optInt("tier3_count"),
                items = (t1 + t2).take(50),
                kpis = mapOf(
                    "total_sectors" to o.optInt("total_sectors"),
                    "tier1" to o.optInt("tier1_count"),
                ),
                ts = o.optString("date").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("sector", "板块池", status = "insufficient", reason = "sector_pool.json 不可达"))

        cycleD.await()?.let { o ->
            val stocks = o.optJSONArray("stocks")?.toItems() ?: emptyList()
            out.add(Pool(
                id = "cycle",
                name = "周期池",
                subtitle = "周期轮动 · " + (o.optJSONObject("meta")?.optString("index") ?: ""),
                items = stocks.take(50),
                kpis = mapOf("n" to stocks.size),
                ts = o.optJSONObject("meta")?.optString("ts"),
            ))
        } ?: out.add(Pool("cycle", "周期池", status = "insufficient", reason = "cycle_pool_v2.json 不可达"))

        lowD.await()?.let { o ->
            val strong = o.optJSONArray("strong")?.toItems() ?: emptyList()
            val watch = o.optJSONArray("watch_list")?.toItems() ?: emptyList()
            out.add(Pool(
                id = "low",
                name = "低位池",
                subtitle = "strong_buy " + o.optInt("strong_buy") + " · watch " + o.optInt("watch"),
                items = (strong + watch).take(50),
                kpis = mapOf(
                    "strong_buy" to o.optInt("strong_buy"),
                    "watch" to o.optInt("watch"),
                    "total_filtered" to o.optInt("total_filtered"),
                ),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("low", "低位池", status = "insufficient", reason = "lowpool.json 不可达"))

        compD.await()?.let { o ->
            out.add(Pool(
                id = "composite",
                name = "综合池",
                subtitle = o.optString("algo").takeIf { it.isNotEmpty() },
                items = o.optJSONArray("picks")?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("composite", "综合池", status = "insufficient", reason = "composite_pool_v3_eom.json 不可达"))

        limitD.await()?.let { o ->
            val arr = o.optJSONArray("items") ?: o.optJSONArray("stocks") ?: o.optJSONArray("attribution")
            out.add(Pool(
                id = "limitup",
                name = "涨停归因",
                subtitle = "价格带 · 封板时段 · 连板 · 行业",
                items = arr?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("limitup", "涨停归因", status = "insufficient", reason = "limitup_attribution.json 不可达"))

        moneyD.await()?.let { o ->
            val arr = o.optJSONArray("top") ?: o.optJSONArray("stocks") ?: o.optJSONArray("items")
            out.add(Pool(
                id = "moneyflow",
                name = "资金流",
                subtitle = "主力净额榜",
                items = arr?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            ))
        } ?: out.add(Pool("moneyflow", "资金流", status = "insufficient", reason = "moneyflow.json 不可达"))

        out
    }

    suspend fun board(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Any?>()
        val src = getJson("$API/latest.json") ?: getJson("$API/market_now.json")
        if (src == null) {
            out["status"] = "insufficient"
            out["reason"] = "latest.json / market_now.json 均不可达"
            return@withContext out
        }
        out["ts"] = src.optString("ts").takeIf { it.isNotEmpty() } ?: src.optString("asof")
        out["market"] = src.optJSONObject("market")?.toMap() ?: emptyMap<String, Any>()
        out["phase"] = src.opt("phase")
        // 字段映射（2026-10-25 修）：latest.json/market_now.json 的真实字段名与看板期望的不一致，
        // 原代码取 gainers/losers/limit_up/sectors 恒为空 → 看板涨跌统计/涨跌幅榜/涨停池/板块热度全空白。
        // 真实字段：limit_up_pool / limit_down_pool / ths_strong / tx_sector_ranking / themes
        out["indices"] = src.optJSONArray("indices")?.toMapList()
            ?: src.optJSONObject("indices")?.toMap()
            ?: src.optJSONArray("index_kline")?.toMapList()
            ?: emptyMap<String, Any>()
        out["gainers"] = src.optJSONArray("gainers")?.toMapList()
            ?: src.optJSONArray("ths_strong")?.toMapList()
            ?: emptyList<Map<String, Any?>>()
        out["losers"] = src.optJSONArray("losers")?.toMapList()
            ?: src.optJSONArray("ths_weak")?.toMapList()
            ?: emptyList<Map<String, Any?>>()
        out["limit_up"] = src.optJSONArray("limit_up")?.toMapList()
            ?: src.optJSONArray("limit_up_pool")?.toMapList()
            ?: src.optJSONObject("limit_up_pool")?.optJSONArray("items")?.toMapList()
            ?: emptyList<Map<String, Any?>>()
        out["limit_down"] = src.optJSONArray("limit_down")?.toMapList()
            ?: src.optJSONArray("limit_down_pool")?.toMapList()
            ?: src.optJSONObject("limit_down_pool")?.optJSONArray("items")?.toMapList()
            ?: emptyList<Map<String, Any?>>()
        out["sectors"] = src.optJSONArray("sectors")?.toMapList()
            ?: src.optJSONArray("tx_sector_ranking")?.toMapList()
            ?: src.optJSONArray("themes")?.toMapList()
            ?: emptyList<Map<String, Any?>>()
        out["status"] = "ok"
        out
    }

    suspend fun themeReport(): JSONObject? = withContext(Dispatchers.IO) {
        getJson("${RemoteSources.base(RemoteSources.OWNER_A, RemoteSources.REPO_A)}/theme_report.json")
            ?: getJson("${RemoteSources.base(RemoteSources.OWNER_C, RemoteSources.REPO_C)}/theme_report.json")
    }

    suspend fun summary(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Any?>()
        val brief = getJson("$API/daily_brief.json")
        if (brief != null) {
            out["text"] = brief.optString("text").takeIf { it.isNotEmpty() }
            out["date"] = brief.optString("date")
            out["session"] = brief.optString("session")
            out["ts"] = brief.optString("ts")
        }
        val latest = getJson("$API/latest.json")
        latest?.let { l ->
            out["picks"] = l.optJSONArray("picks")?.toMapList() ?: emptyList<Map<String, Any?>>()
            out["review"] = l.opt("review")
        }
        if (out.isEmpty()) {
            out["status"] = "insufficient"
            out["reason"] = "daily_brief.json / latest.json 均不可达"
        } else out["status"] = "ok"
        out
    }

    private fun lenOf(v: Any?): Int = when (v) {
        is JSONArray -> v.length()
        is JSONObject -> v.length()
        else -> 0
    }

    private fun JSONArray.toItems(): List<PoolItem> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val code = listOf("code", "symbol", "s", "stock_code", "bk", "secid")
                .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotEmpty() } }
                ?: return@mapNotNull null
            PoolItem(
                code = code,
                name = listOf("name", "n", "stock_name", "board_name")
                    .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotEmpty() } },
                score = listOf("score", "composite", "strength", "total_score")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                price = listOf("price", "close", "p")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                pctChg = listOf("pctChg", "chg_pct", "pct", "chg", "ret_pct")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                extra = o.toMap(),
            )
        }

    private fun JSONArray.toMapList(): List<Map<String, Any?>> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.toMap() }

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
}
