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
        val goodD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("good_stock_pool.json")) }
        val decisionD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("decision.json")) }
        val aiD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("ai_pick_v4.json")) }
        val impactD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("news_impact.json")) }
        val newsApiD = async(Dispatchers.IO) { getJson(RemoteSources.apiNews()) }
        val hotD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("hot_news.json")) }
        val sectorD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("sector_pool.json")) }
        val sectorsApiD = async(Dispatchers.IO) { getJson(RemoteSources.apiSectors()) }
        val cycleD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("cycle_pool_v2.json")) }
        val lowD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("lowpool.json")) }
        val banD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("ban_pick.json")) }
        val limitD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("limitup_attribution.json")) }
        val auctionD = async(Dispatchers.IO) { getJson(RemoteSources.poolFile("auction_engine.json")) }
        val trendD = async(Dispatchers.IO) { getJson(RemoteSources.trendStrength()) }
        val enrichD = async(Dispatchers.IO) { getJson(RemoteSources.enrich()) }
        val debateD = async(Dispatchers.IO) { getJson(RemoteSources.bullBearDebate()) }
        val latestD = async(Dispatchers.IO) { getJson(RemoteSources.latest()) }

        val out = ArrayList<Pool>()
        fun missing(id: String, name: String, file: String) =
            out.add(Pool(id, name, status = "insufficient", reason = "$file 不可达"))

        val goodItems = goodD.await()?.let { o ->
            val eligibleArr = o.optJSONArray("eligible")
            val eligibleObj = o.optJSONObject("eligible")
            when {
                eligibleArr != null -> eligibleArr.toItems("good_stock_pool.eligible")
                eligibleObj != null -> {
                    val result = ArrayList<PoolItem>()
                    val keys = eligibleObj.keys()
                    while (keys.hasNext()) eligibleObj.optJSONArray(keys.next())?.let {
                        result.addAll(it.toItems("good_stock_pool.eligible"))
                    }
                    result
                }
                else -> emptyList()
            }
        }.orEmpty()
        val goodRoot = goodD.await()
        val aiRoot = aiD.await()
        val aiItems = aiRoot?.optJSONArray("picks")?.toItems("ai_pick_v4.picks").orEmpty()
        val recommendationItems = mergeStockItems(goodItems + aiItems)
        if (goodRoot != null || aiRoot != null) {
            out.add(Pool("recommend", "推荐池", "eligible 与 AI 推荐合并", recommendationItems, mapOf(
                "eligible" to goodItems.size, "ai" to aiItems.size, "推荐" to recommendationItems.size),
                goodRoot?.optString("asof")?.takeIf { it.isNotEmpty() }
                    ?: aiRoot?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: aiRoot?.optString("date")?.takeIf { it.isNotEmpty() }))
        } else {
            missing("recommend", "推荐池", "good_stock_pool.json / ai_pick_v4.json")
        }

        decisionD.await()?.let { o ->
            val avoid = uniqueStockItems(o.optJSONArray("avoid")?.toItems("decision.avoid").orEmpty())
            out.add(Pool("risk", "风险池", "决策卡 · 回避清单", avoid, mapOf(
                "回避" to avoid.size, "证据" to (o.optJSONObject("evidence")?.optString("sufficient") ?: "暂无")),
                o.optString("ts").takeIf { it.isNotEmpty() }))
        } ?: missing("risk", "风险池", "decision.json")

        val impactRoot = impactD.await()
        val newsApiRoot = newsApiD.await()
        val hotItems = listOfNotNull(impactRoot, newsApiRoot).flatMap { it.toNewsStockItems() }
            .distinctBy { it.code }
        if (hotItems.isNotEmpty()) {
            out.add(Pool("hot", "热榜", "新闻影响个股", hotItems, mapOf(
                "新闻" to (impactRoot?.optInt("n", impactRoot.optInt("n_input")) ?: newsApiRoot?.optInt("n")),
                "股票" to hotItems.size),
                impactRoot?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: newsApiRoot?.optString("ts")?.takeIf { it.isNotEmpty() }))
        } else if (hotD.await() != null) {
            out.add(Pool("hot", "热榜", "新闻影响个股", emptyList(), mapOf("新闻" to 0, "股票" to 0),
                status = "insufficient", reason = "news_impact.json 不可达或没有对应股票"))
        } else {
            missing("hot", "热榜", "news_impact.json")
        }

        val sectorFile = sectorD.await()
        val sectorRoot = sectorsApiD.await()
        val latestSnapshot = latestD.await()
        val sectorItems = buildList {
            listOfNotNull(sectorRoot?.optJSONObject("sector_pool"), sectorFile).forEach { pool ->
                listOf("tier1", "tier2", "tier3", "tier_down").forEach { tier ->
                    pool.optJSONArray(tier)?.let { addAll(it.toItems()) }
                }
            }
        }
        val sectorMeta = sectorRoot?.optJSONObject("sector_pool") ?: sectorFile
        val industryItems = sectorRoot?.let { root ->
            listOf("industries", "concepts").flatMap { key -> root.optJSONArray(key)?.toItems() ?: emptyList() }
        }.orEmpty()
        val moneyflowItems = latestSnapshot?.optJSONObject("sector_moneyflow")
            ?.optJSONArray("data")?.toItems().orEmpty()
        val sectors = mergeSectorItems(sectorItems + industryItems + moneyflowItems)
        if (sectors.isNotEmpty()) {
            out.add(Pool("sector", "板块池", "板块梯队、行业与概念", sectors, mapOf(
                "板块总数" to sectors.size,
                "T1" to sectorMeta?.opt("tier1_count")?.takeUnless { it == JSONObject.NULL },
                "T2" to sectorMeta?.opt("tier2_count")?.takeUnless { it == JSONObject.NULL },
                "T3" to sectorMeta?.opt("tier3_count")?.takeUnless { it == JSONObject.NULL }),
                sectorMeta?.optJSONObject("meta")?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: sectorRoot?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: sectorMeta?.optString("date")?.takeIf { it.isNotEmpty() }))
        } else missing("sector", "板块池", "sector_pool.json / api/sectors.json")

        cycleD.await()?.let { o ->
            val stocks = o.optJSONArray("stocks")?.toItems("cycle_pool_v2.stocks").orEmpty()
            val stocksMeta = o.optJSONObject("stocks_meta")
            val sufficient = stocks.isNotEmpty() && stocksMeta?.optString("status") != "insufficient_data"
            out.add(Pool("cycle", "周期池", "周期轮动", stocks, mapOf(
                "候选" to stocks.size,
                "stocks_meta" to (stocksMeta?.toMap() ?: emptyMap<String, Any?>()),
                "regimes" to (o.optJSONObject("regimes")?.toMap() ?: emptyMap<String, Any?>())),
                o.optJSONObject("meta")?.optString("ts").takeIf { !it.isNullOrEmpty() },
                status = if (sufficient) "ok" else "insufficient",
                reason = if (sufficient) null else stocksMeta?.optString("reason")?.takeIf { it.isNotEmpty() }
                    ?: "stocks 为空，周期池数据不足"))
        } ?: missing("cycle", "周期池", "cycle_pool_v2.json")

        lowD.await()?.let { o ->
            val strong = o.optJSONArray("strong")?.toItems() ?: emptyList()
            val watch = o.optJSONArray("watch_list")?.toItems() ?: emptyList()
            out.add(Pool("low", "低位池", "低位启动 · 强势/观察", strong + watch, mapOf(
                "强势" to o.optInt("strong_buy"), "观察" to o.optInt("watch"), "过滤" to o.optInt("total_filtered")),
                o.optString("ts").takeIf { it.isNotEmpty() }))
        } ?: missing("low", "低位池", "lowpool.json")

        val banRoot = banD.await()
        val banItems = banRoot?.optJSONArray("picks")?.toItems("ban_pick.picks").orEmpty()
        val limitAttribution = limitD.await()
        val latestLimit = latestSnapshot?.let { root ->
            when (val raw = root.opt("limit_up_pool")) {
                is JSONArray -> raw.toItems()
                is JSONObject -> (raw.optJSONArray("items") ?: raw.optJSONArray("data"))?.toItems().orEmpty()
                else -> emptyList()
            }
        }.orEmpty()
        val limitItems = mergeLimitupItems(
            banItems + (limitAttribution?.optJSONArray("items")?.toItems("limitup_attribution.items").orEmpty()),
            latestLimit,
        )
        if (limitItems.isNotEmpty()) {
            out.add(Pool("limitup", "涨停池", "打板判断、涨停归因与最新名单", limitItems, mapOf(
                "涨停" to limitItems.size,
                "打板判断" to banItems.size,
                "归因维度" to (limitAttribution?.optJSONArray("dims")?.length() ?: 0)),
                banRoot?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: limitAttribution?.optString("ts")?.takeIf { it.isNotEmpty() }
                    ?: limitAttribution?.optString("date")?.takeIf { it.isNotEmpty() }
                    ?: latestSnapshot?.optString("ts")?.takeIf { it.isNotEmpty() }))
        } else if (banRoot == null && limitAttribution == null && latestSnapshot == null) {
            missing("limitup", "涨停池", "ban_pick.json / limitup_attribution.json / latest.json")
        } else {
            out.add(Pool("limitup", "涨停池", "打板判断、涨停归因与最新名单", status = "insufficient",
                reason = "上游没有可用涨停条目"))
        }

        auctionD.await()?.let { o ->
            val stocks = o.optJSONArray("stocks")?.toItems() ?: emptyList()
            out.add(Pool("auction", "竞价抢筹", "竞价与日内资金结构", stocks, mapOf(
                "分析" to o.optInt("n_analyzed"), "强竞价" to o.optInt("n_auction_strong")),
                o.optString("ts").takeIf { it.isNotEmpty() } ?: o.optString("date").takeIf { it.isNotEmpty() }))
        } ?: missing("auction", "竞价抢筹", "auction_engine.json")

        trendD.await()?.let { o ->
            val rtsi = o.optJSONObject("rtsi")
            val stocks = rtsi?.optJSONArray("stocks")?.toItems("trend_strength.rtsi.stocks").orEmpty()
            out.add(Pool("trend", "趋势池", "RTSI 趋势强度", stocks,
                mapOf("样本" to (rtsi?.optInt("n") ?: stocks.size), "窗口" to (rtsi?.optInt("window") ?: "暂无")),
                o.optString("ts").takeIf { it.isNotEmpty() }))
        } ?: missing("trend", "趋势池", "trend_strength.json")

        enrichD.await()?.let { o ->
            fun addEnrichPool(id: String, name: String, key: String, subtitle: String) {
                val rows = o.optJSONArray(key)?.toItems("enrich.$key").orEmpty()
                if (rows.isNotEmpty()) out.add(Pool(id, name, subtitle, rows, mapOf("条目" to rows.size), o.optString("ts").takeIf { it.isNotEmpty() }))
            }
            addEnrichPool("lhb", "龙虎榜", "lhb", "龙虎榜明细")
            addEnrichPool("blocktrade", "大宗交易", "blocktrade", "大宗交易明细")
        }
        debateD.await()?.let { o ->
            val rows = o.optJSONArray("debates")?.toItems("bull_bear_debate.debates").orEmpty()
            out.add(Pool("debate", "多空辩论", "看涨、看跌与风控裁决", rows,
                mapOf("辩论" to rows.size, "否决" to o.optInt("n_vetoed")), o.optString("ts").takeIf { it.isNotEmpty() }))
        } ?: missing("debate", "多空辩论", "bull_bear_debate.json")

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
        getJson(RemoteSources.apiSectors())?.let { sectors ->
                val ranked = sectors.optJSONObject("sector_pool")?.let { pool ->
                    listOf("tier1", "tier2", "tier3").flatMap { key ->
                        (pool.optJSONArray(key)?.toMapList() ?: emptyList()).map { row ->
                            row + mapOf(
                                "name" to (row["sector"] ?: row["name"]),
                                "chg_pct" to (row["avg_chg_1d"] ?: row["chg_1d"] ?: row["score"]),
                                "chg5d" to row["chg_5d"],
                                "heat" to (row["score"] ?: row["avg_chg_1d"]),
                                "leader" to row["leader"],
                            )
                        }
                    }
                } ?: emptyList()
                val fallback = sectors.optJSONArray("industries")?.toMapList() ?: emptyList()
            if (ranked.isNotEmpty()) out["sectors"] = ranked else if ((out["sectors"] as? List<*>)?.isNullOrEmpty() != false) out["sectors"] = fallback
            out["sector_ts"] = sectors.optString("ts").takeIf { it.isNotEmpty() }
        }
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
            val text = brief.optString("text").takeIf { it.isNotEmpty() }
            out["text"] = text
            out["date"] = brief.optString("date")
            out["session"] = brief.optString("session")
            out["ts"] = brief.optString("ts")
            brief.optJSONObject("sections")?.let { sections ->
                out["sections"] = sections.toMap()
                sections.optString("premarket").takeIf { it.isNotBlank() }?.let { out["premarket"] = it }
                sections.optString("picks").takeIf { it.isNotBlank() }?.let { out["picks_text"] = it }
                sections.optString("winrate").takeIf { it.isNotBlank() }?.let { out["winrate"] = it }
                sections.optString("hot").takeIf { it.isNotBlank() }?.let { out["hot"] = it }
            }
            if (!out.containsKey("premarket") && text != null) {
                val picksStart = text.indexOf("## 二、今日推股")
                val premarket = if (picksStart > 0) text.substring(0, picksStart) else text
                out["premarket"] = premarket
                if (picksStart >= 0) {
                    val nextSection = text.indexOf("## 三、", picksStart)
                    out["picks_text"] = text.substring(picksStart, if (nextSection > picksStart) nextSection else text.length)
                }
            }
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

    private fun JSONArray.toItems(source: String = "unknown"): List<PoolItem> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val rawCode = listOf("code", "symbol", "s", "stock_code", "bk", "secid", "sector_code")
                .firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }
                ?: listOf("sector_name", "display_name", "board_name", "sector", "name", "title")
                    .firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }
                ?: return@mapNotNull null
            val code = canonicalCode(rawCode) ?: rawCode
            val names = listOf("sector_name", "display_name", "board_name", "sector", "name", "stock_name", "n", "title")
                .mapNotNull { k -> (o.opt(k) as? String)?.trim()?.takeIf {
                    it.isNotEmpty() && it != "null" && !it.startsWith("item_")
                } }
            val name = names.firstOrNull { value -> value.any { it in '\u3400'..'\u9fff' } }
                ?: names.firstOrNull { !it.matches(Regex("[a-z][a-z0-9_]*")) }
            PoolItem(
                code = code,
                name = name,
                score = listOf("score", "heat", "composite", "strength", "total_score")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                price = listOf("price", "close", "p")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                pctChg = listOf("pctChg", "chg_pct", "pct", "chg", "ret_pct", "avg_chg_1d", "chg_1d")
                    .firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() },
                extra = o.toMap() + ("source" to source),
            )
        }

    private fun mergeStockItems(items: List<PoolItem>): List<PoolItem> = uniqueStockItems(items)

    private fun uniqueStockItems(items: List<PoolItem>): List<PoolItem> {
        val merged = LinkedHashMap<String, PoolItem>()
        items.forEach { item ->
            val key = canonicalCode(item.code) ?: return@forEach
            val existing = merged[key]
            merged[key] = if (existing == null) item.copy(code = key) else existing.copy(
                name = existing.name ?: item.name,
                score = existing.score ?: item.score,
                price = existing.price ?: item.price,
                pctChg = existing.pctChg ?: item.pctChg,
                extra = item.extra + existing.extra.filterValues { it != null && it != JSONObject.NULL } +
                    mapOf(
                        "source" to (existing.extra["source"] ?: item.extra["source"]),
                        "sources" to listOfNotNull(existing.extra["source"], item.extra["source"]).distinct(),
                    ),
            )
        }
        return merged.values.toList()
    }

    private fun JSONObject.toNewsStockItems(): List<PoolItem> {
        val merged = LinkedHashMap<String, PoolItem>()
        fun addStock(raw: Any?, title: String?, source: String) {
            val obj = raw as? JSONObject
            val rawCode = when (raw) {
                is JSONObject -> listOf("code", "symbol", "stock_code", "s").firstNotNullOfOrNull {
                    obj?.optString(it)?.takeIf(String::isNotBlank)
                }
                else -> raw?.toString()
            } ?: return
            val code = canonicalCode(rawCode) ?: return
            val stockName = obj?.let { stock ->
                listOf("name", "stock_name", "display_name").firstNotNullOfOrNull { key ->
                    stock.optString(key).takeIf(String::isNotBlank)
                }
            }
            val extra = (obj?.toMap() ?: emptyMap()) + mapOf(
                "source" to source,
                "title" to title,
            )
            val item = PoolItem(code, stockName, extra = extra)
            val old = merged[code]
            merged[code] = if (old == null) item else old.copy(
                name = old.name ?: item.name,
                extra = item.extra + old.extra.filterValues { it != null && it != JSONObject.NULL },
            )
        }
        fun collect(obj: JSONObject, source: String, inheritedTitle: String? = null) {
            val title = obj.optString("title").takeIf(String::isNotBlank) ?: inheritedTitle
            listOf("stocks", "codes", "affected_stocks").forEach { key ->
                when (val value = obj.opt(key)) {
                    is JSONArray -> (0 until value.length()).forEach { addStock(value.opt(it), title, "$source.$key") }
                    is JSONObject -> value.keys().forEach { keyName ->
                    val child = value.opt(keyName)
                    if (child is JSONObject) addStock(child, title, "$source.$key") else addStock(keyName, title, "$source.$key")
                }
                    is String -> addStock(value, title, "$source.$key")
                }
            }
            listOf("items", "categories", "themes", "impacts", "hot_reports").forEach { key ->
                when (val value = obj.opt(key)) {
                    is JSONArray -> (0 until value.length()).forEach { index ->
                        value.optJSONObject(index)?.let { collect(it, "$source.$key[$index]", title) }
                    }
                    is JSONObject -> {
                        collect(value, "$source.$key", title)
                        value.keys().forEach { childKey ->
                            when (val child = value.opt(childKey)) {
                                is JSONObject -> collect(child, "$source.$key.$childKey", title)
                                is JSONArray -> (0 until child.length()).forEach { index ->
                                    child.optJSONObject(index)?.let { collect(it, "$source.$key.$childKey[$index]", title) }
                                }
                            }
                        }
                    }
                }
            }
        }
        collect(this, "news_impact")
        return merged.values.toList()
    }

    private fun mergeSectorItems(items: List<PoolItem>): List<PoolItem> {
        val merged = ArrayList<PoolItem>()
        val byCode = HashMap<String, Int>()
        val byName = HashMap<String, Int>()
        items.forEach { item ->
            val name = item.name?.takeIf { value -> value.any { it in '\u3400'..'\u9fff' } }
                ?: return@forEach
            val codeKey = item.code.lowercase(java.util.Locale.ROOT)
            val nameKey = name.filterNot { it.isWhitespace() }.lowercase(java.util.Locale.ROOT)
            val existing = byName[nameKey] ?: byCode[codeKey]
            if (existing == null) {
                byName[nameKey] = merged.size
                byCode[codeKey] = merged.size
                merged += item
            } else {
                val first = merged[existing]
                merged[existing] = first.copy(
                    score = first.score ?: item.score,
                    price = first.price ?: item.price,
                    pctChg = first.pctChg ?: item.pctChg,
                    extra = item.extra + first.extra.filterValues { it != null && it != JSONObject.NULL },
                )
                byName[nameKey] = existing
                byCode[codeKey] = existing
            }
        }
        return merged
    }

    private fun mergeLimitupItems(attribution: List<PoolItem>, latest: List<PoolItem>): List<PoolItem> =
        uniqueStockItems(attribution + latest)


    private fun canonicalCode(raw: String): String? {
        val value = raw.trim().lowercase(java.util.Locale.ROOT)
        return value.takeIf { it.matches(Regex("(?:(?:sh|sz|bj)|[01]\\.)?\\d{6}")) }
            ?.let(SkappApi::normalize)
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
