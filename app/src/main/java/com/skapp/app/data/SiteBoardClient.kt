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
 *
 * 对应网页版 tab：
 *   实时总结 → daily_brief / latest / market_now / realtime_minute
 *   多池选股 → good_stock_pool / sector_pool / cycle_pool_v2 / lowpool /
 *             composite_pool_v3_eom / limitup_attribution / moneyflow
 *   看板    → latest（指数/涨跌统计/分布/涨跌幅榜/情绪仪表盘/涨停池）
 *   消息    → hot_news / news_digest / hot_themes / news_impact
 */
object SiteBoardClient {

    private const val RAW = "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data"

    private fun getJson(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
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

    /** 一个池 */
    data class Pool(
        val id: String,
        val name: String,
        val subtitle: String? = null,
        val items: List<PoolItem> = emptyList(),
        val kpis: Map<String, Any?> = emptyMap(),
        val ts: String? = null,
        val status: String = "ok",     // ok | insufficient
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

    /** 多池选股 */
    suspend fun pools(): List<Pool> = coroutineScope {
        val goodD = async(Dispatchers.IO) { getJson("$RAW/good_stock_pool.json") }
        val sectorD = async(Dispatchers.IO) { getJson("$RAW/sector_pool.json") }
        val cycleD = async(Dispatchers.IO) { getJson("$RAW/cycle_pool_v2.json") }
        val lowD = async(Dispatchers.IO) { getJson("$RAW/lowpool.json") }
        val compD = async(Dispatchers.IO) { getJson("$RAW/composite_pool_v3_eom.json") }
        val limitD = async(Dispatchers.IO) { getJson("$RAW/limitup_attribution.json") }
        val moneyD = async(Dispatchers.IO) { getJson("$RAW/moneyflow.json") }

        val out = ArrayList<Pool>()

        // 推荐池（good_stock_pool: eligible/watch）
        goodD.await()?.let { o ->
            val items = o.optJSONArray("eligible")?.toItems()
                ?: o.optJSONObject("eligible")?.let { e ->
                    e.keys().mapNotNull { k -> e.optJSONArray(k)?.toItems() }.flatten()
                } ?: emptyList()
            out += Pool(
                id = "good",
                name = "推荐池",
                subtitle = "好股票池 · eligible",
                items = items.take(50),
                kpis = mapOf(
                    "eligible" to (o.opt("eligible")?.let { lenOf(it) }),
                    "watch" to (o.opt("watch")?.let { lenOf(it) }),
                    "reject" to (o.opt("reject")?.let { lenOf(it) }),
                ),
                ts = o.optString("asof").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("good", "推荐池", status = "insufficient", reason = "good_stock_pool.json 不可达")

        // 板块池（sector_pool: tier1/tier2/tier3）
        sectorD.await()?.let { o ->
            val t1 = o.optJSONArray("tier1")?.toItems() ?: emptyList()
            val t2 = o.optJSONArray("tier2")?.toItems() ?: emptyList()
            out += Pool(
                id = "sector",
                name = "板块池",
                subtitle = "T1 ${o.optInt("tier1_count")} · T2 ${o.optInt("tier2_count")} · T3 ${o.optInt("tier3_count")}",
                items = (t1 + t2).take(50),
                kpis = mapOf(
                    "total_sectors" to o.optInt("total_sectors"),
                    "tier1" to o.optInt("tier1_count"),
                ),
                ts = o.optString("date").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("sector", "板块池", status = "insufficient", reason = "sector_pool.json 不可达")

        // 周期池（cycle_pool_v2: stocks）
        cycleD.await()?.let { o ->
            val stocks = o.optJSONArray("stocks")?.toItems() ?: emptyList()
            out += Pool(
                id = "cycle",
                name = "周期池",
                subtitle = "周期轮动 · " + (o.optJSONObject("meta")?.optString("index") ?: ""),
                items = stocks.take(50),
                kpis = mapOf("n" to stocks.size),
                ts = o.optJSONObject("meta")?.optString("ts"),
            )
        } ?: out += Pool("cycle", "周期池", status = "insufficient", reason = "cycle_pool_v2.json 不可达")

        // 低位池（lowpool: strong/watch_list）
        lowD.await()?.let { o ->
            val strong = o.optJSONArray("strong")?.toItems() ?: emptyList()
            val watch = o.optJSONArray("watch_list")?.toItems() ?: emptyList()
            out += Pool(
                id = "low",
                name = "低位池",
                subtitle = "strong_buy ${o.optInt("strong_buy")} · watch ${o.optInt("watch")}",
                items = (strong + watch).take(50),
                kpis = mapOf(
                    "strong_buy" to o.optInt("strong_buy"),
                    "watch" to o.optInt("watch"),
                    "total_filtered" to o.optInt("total_filtered"),
                ),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("low", "低位池", status = "insufficient", reason = "lowpool.json 不可达")

        // 综合池（composite_pool_v3_eom: picks）
        compD.await()?.let { o ->
            out += Pool(
                id = "composite",
                name = "综合池",
                subtitle = o.optString("algo").takeIf { it.isNotEmpty() },
                items = o.optJSONArray("picks")?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("composite", "综合池", status = "insufficient", reason = "composite_pool_v3_eom.json 不可达")

        // 涨停归因（limitup_attribution）
        limitD.await()?.let { o ->
            val arr = o.optJSONArray("items") ?: o.optJSONArray("stocks") ?: o.optJSONArray("attribution")
            out += Pool(
                id = "limitup",
                name = "涨停归因",
                subtitle = "价格带 · 封板时段 · 连板 · 行业",
                items = arr?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("limitup", "涨停归因", status = "insufficient", reason = "limitup_attribution.json 不可达")

        // 资金流（moneyflow）
        moneyD.await()?.let { o ->
            val arr = o.optJSONArray("top") ?: o.optJSONArray("stocks") ?: o.optJSONArray("items")
            out += Pool(
                id = "moneyflow",
                name = "资金流",
                subtitle = "主力净额榜",
                items = arr?.toItems() ?: emptyList(),
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
            )
        } ?: out += Pool("moneyflow", "资金流", status = "insufficient", reason = "moneyflow.json 不可达")

        out
    }

    /** 看板：指数 / 涨跌统计 / 涨跌幅榜 / 情绪 / 涨停池 */
    suspend fun board(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Any?>()
        val latest = getJson("$RAW/latest.json")
        val mn = getJson("$RAW/market_now.json")
        val src = latest ?: mn
        if (src == null) {
            out["status"] = "insufficient"
            out["reason"] = "latest.json / market_now.json 均不可达"
            return@withContext out
        }
        out["ts"] = src.optString("ts").takeIf { it.isNotEmpty() } ?: src.optString("asof")
        out["indices"] = src.optJSONArray("indices")?.toMapList()
            ?: src.optJSONObject("indices")?.let { it.toMap() } ?: emptyMap<String, Any>()
        out["market"] = src.optJSONObject("market")?.toMap() ?: emptyMap<String, Any>()
        out["phase"] = src.opt("phase")
        out["gainers"] = src.optJSONArray("gainers")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["losers"] = src.optJSONArray("losers")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["limit_up"] = src.optJSONArray("limit_up")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["sectors"] = src.optJSONArray("sectors")?.toMapList() ?: emptyList<Map<String, Any?>>()
        out["status"] = "ok"
        out
    }

    /** 实时总结（daily_brief 文本 + 最新推股验证） */
    suspend fun summary(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Any?>()
        val brief = getJson("$RAW/daily_brief.json")
        if (brief != null) {
            out["text"] = brief.optString("text").takeIf { it.isNotEmpty() }
            out["date"] = brief.optString("date")
            out["session"] = brief.optString("session")
            out["ts"] = brief.optString("ts")
        }
        val latest = getJson("$RAW/latest.json")
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

    // ── 小工具 ────────────────────────────────────────────

    private fun lenOf(v: Any?): Int = when (v) {
        is JSONArray -> v.length()
        is JSONObject -> v.length()
        else -> 0
    }

    /** 数组 → PoolItem，字段名兼容多种上游形状 */
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
