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

    // raw.githubusercontent.com 在部分网络不稳，改用 Contents API + token（支持私有仓）
    private const val API = "https://api.github.com/repos/qwerzxcva/stocks-ai-view/contents/data"
    private var token: String? = null
    fun setToken(t: String?) { token = t?.takeIf { it.isNotBlank() } }

    private fun getJson(url: String): JSONObject? {
        val u = if (url.contains("raw.githubusercontent.com")) {
            url.replace("https://raw.githubusercontent.com/", "$API/")
                .replaceFirst("/main/", "/contents/")
                .replaceFirst("/master/", "/contents/")
        } else if (url.startsWith(API)) {
            url
        } else {
            url
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (u.startsWith(API)) setRequestProperty("Accept", "application/vnd.github.raw")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
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
        out["indices"] = src.optJSONArray("indices")?.toMapList()
            ?: src.optJSONObject("indices")?.toMap() ?: emptyMap<String, Any>()
        out["market"] = src.optJSONObject("market")?.toMap() ?: emptyMap<String, Any>()
        out["phase"] = src.opt("phase")
        out["gainers"] = src.optJSONArray("gainers")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["losers"] = src.optJSONArray("losers")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["limit_up"] = src.optJSONArray("limit_up")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["sectors"] = src.optJSONArray("sectors")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["status"] = "ok"
        out
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
