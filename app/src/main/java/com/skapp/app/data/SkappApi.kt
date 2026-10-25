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
 * 远端数据客户端：只读 raw.githubusercontent.com 静态 JSON + 腾讯实时行情。
 * 全部 org.json 手工解析（无序列化依赖，和线上 CI 产物字段对齐）。
 */
object SkappApi {

    private const val TIMEOUT = 15000
    private const val API_ROOT = "https://api.github.com/repos"
    private var token: String? = null
    fun setToken(t: String?) { token = t?.takeIf { it.isNotBlank() } }

    private fun fetchText(url: String, charset: String = "utf-8"): String? {
        // 公开仓数据：优先 raw.githubusercontent.com（不限流），失败才回退 Contents API
        // 原因：api.github.com 匿名 60 req/h 限流，而 raw 不限流
        val rawUrl = url
        val apiUrl = if (url.contains("raw.githubusercontent.com")) {
            url.replace("https://raw.githubusercontent.com/", API_ROOT)
                .replaceFirst("/main/", "/contents/")
                .replaceFirst("/master/", "/contents/")
        } else null
        
        // 先尝试 raw（不限流）
        fetchFromUrl(rawUrl, charset)?.let { return it }
        
        // raw 失败才走 API（有 token 时带 token）
        if (apiUrl != null) {
            return fetchFromUrl(apiUrl, charset, isApi = true)
        }
        return null
    }
    
    private fun fetchFromUrl(u: String, charset: String, isApi: Boolean = false): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000  // 20s，给弱网留时间
                readTimeout = 20000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "skapp-android")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (isApi) setRequestProperty("Accept", "application/vnd.github.raw")
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

    fun fetchJson(url: String): JSONObject? =
        fetchText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 腾讯行情：批量取实时报价（GBK）。返回 code(prefixed) -> Quote */
    suspend fun fetchQuotes(codes: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext emptyMap()
        val out = LinkedHashMap<String, Quote>()
        codes.chunked(20).forEach { chunk ->
            val q = chunk.joinToString(",")
            val txt = fetchText("${RemoteSources.QUOTE_QT}$q", "gbk") ?: return@forEach
            txt.lineSequence().forEach { line ->
                val m = Regex("v_([a-z]{2}\\d{5,6})=\"([^\"]*)\"").find(line) ?: return@forEach
                val key = m.groupValues[1]
                val f = m.groupValues[2].split("~")
                if (f.size < 40) return@forEach
                val name = f.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ""
                val price = f.getOrNull(3)?.toDoubleOrNull()
                val pct = f.getOrNull(32)?.toDoubleOrNull()
                val ts = f.getOrNull(30)?.takeIf { it.isNotBlank() }
                out[key] = Quote(key, name, price, pct, ts)
            }
        }
        out
    }

    /** 代码表失败时仍允许按 A 股代码直接查询，避免把“代码表不可达”误报为无匹配。 */
    fun directSymbol(raw: String): SymbolEntry? {
        val code = normalize(raw) ?: return null
        val prefix = code.take(3)
        if (prefix !in setOf("600", "601", "603", "605", "000", "001", "002", "300", "301", "688", "689")) return null
        return SymbolEntry(code, null)
    }

    /** 全市场代码表，兼容 symbols.json 裸数组与包装，并合并 stock_names.json。 */
    suspend fun fetchSymbols(): List<SymbolEntry> = withContext(Dispatchers.IO) {
        val symbols = fetchSymbolsRaw()
        val names = fetchStockNameEntries()
        SymbolIndex(symbols + names).entries
    }

    private suspend fun fetchSymbolsRaw(): List<SymbolEntry> {
        val txt = fetchText(RemoteSources.symbols()) ?: return emptyList()
        // symbols.json 支持裸数组与 {symbols:[...]} / {data:[...]} 包装。
        val root = runCatching { JSONObject(txt) }.getOrNull()
        val arr = runCatching { JSONArray(txt) }.getOrNull()
            ?: root?.optJSONArray("symbols")
            ?: root?.optJSONArray("data")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = listOf("s", "symbol", "code", "stock_code")
                .firstNotNullOfOrNull { key -> o.optString(key).trim().takeIf { it.isNotEmpty() } }
            val name = listOf("n", "name", "stock_name", "display_name")
                .firstNotNullOfOrNull { key -> o.optString(key).trim().takeIf { it.isNotEmpty() } }
            code?.let { SymbolEntry(it, name) }
        }
    }

    private fun parseStockNames(root: JSONObject): List<SymbolEntry> {
        val out = ArrayList<SymbolEntry>()
        root.keys().forEach { rawCode ->
            val value = root.opt(rawCode)
            when (value) {
                is JSONObject -> {
                    val code = value.optString("code").takeIf { it.isNotBlank() } ?: rawCode
                    val name = listOf("name", "n", "stock_name", "display_name")
                        .firstNotNullOfOrNull { key -> value.optString(key).trim().takeIf { it.isNotEmpty() } }
                    out += SymbolEntry(code, name)
                }
                else -> value?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { out += SymbolEntry(rawCode, it) }
            }
        }
        return out
    }

    /** 合并中文 symbols 与 stock_names 对象，SymbolIndex 负责规范化、去重和稳定名称优先级。 */
    suspend fun fetchSymbolIndex(): SymbolIndex = withContext(Dispatchers.IO) {
        SymbolIndex(fetchSymbolsRaw() + fetchStockNameEntries())
    }

    private suspend fun fetchStockNameEntries(): List<SymbolEntry> {
        val roots = listOf(
            RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "stock_names.json"),
            RemoteSources.appFile(RemoteSources.OWNER_C, RemoteSources.REPO_C, "stock_names.json"),
        )
        return roots.asSequence().mapNotNull { fetchJson(it) }
            .map(::parseStockNames).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /** 新闻逐条分析持久化索引，v2 优先，旧文件作真实 fallback。 */
    suspend fun fetchNewsImpact(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val roots = listOf(
            fetchJson(RemoteSources.newsImpactV2()),
            fetchJson(RemoteSources.newsImpact()),
            fetchJson(RemoteSources.newsImpactV2(RemoteSources.OWNER_C, RemoteSources.REPO_C)),
            fetchJson(RemoteSources.newsImpact(RemoteSources.OWNER_C, RemoteSources.REPO_C)),
        )
        roots.firstNotNullOfOrNull { root ->
            val arr = root?.optJSONArray("items") ?: root?.optJSONArray("news")
            arr?.toMapList()?.takeIf { it.isNotEmpty() }
        } ?: emptyList()
    }

    /** app 主文件：A 仓优先，失败退 C 仓 */
    suspend fun fetchDecision(): Decision = withContext(Dispatchers.IO) {
        parseDecision(fetchJson(RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "decision.json")))
            ?: parseDecision(fetchJson(RemoteSources.appFile(RemoteSources.OWNER_C, RemoteSources.REPO_C, "decision.json")))
            ?: Decision()
    }

    suspend fun fetchTodayPicks(): TodayPicks = withContext(Dispatchers.IO) {
        parsePicks(fetchJson(RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "today_picks.json")))
            ?: parsePicks(fetchJson(RemoteSources.appFile(RemoteSources.OWNER_C, RemoteSources.REPO_C, "today_picks.json")))
            ?: TodayPicks()
    }

    suspend fun fetchStockNames(): Map<String, String> = withContext(Dispatchers.IO) {
        fetchStockNameEntries().mapNotNull { entry ->
            val code = normalize(entry.code) ?: return@mapNotNull null
            entry.name?.let { code to it }
        }.toMap()
    }

    /** 个股详情：优先 A 仓 API，退 C 仓镜像；再补充分片 bundle */
    suspend fun fetchStockDetail(code: String): StockDetail? = withContext(Dispatchers.IO) {
        val normalized = normalize(code) ?: return@withContext null
        val fromApi = listOf(
            RemoteSources.OWNER_A to RemoteSources.REPO_A,
            RemoteSources.OWNER_C to RemoteSources.REPO_C,
        ).firstNotNullOfOrNull { (o, r) ->
            fetchJson(RemoteSources.apiStock(o, r, normalized))?.let { parseStockDetail(it, normalized) }
        }
        val bundle = fetchBundle(normalized)
        if (fromApi == null && bundle == null) return@withContext null
        val base = fromApi ?: StockDetail(code = normalized)
        val qa = fetchQa(normalized)
        base.copy(
            name = base.name ?: bundle?.name,
            detail = base.detail ?: bundle?.detail,
            aiQa = qa ?: base.aiQa,
        )
    }

    suspend fun fetchQa(code: String): AiQa? = withContext(Dispatchers.IO) {
        val normalized = normalize(code) ?: return@withContext null
        listOf(RemoteSources.OWNER_A to RemoteSources.REPO_A,
            RemoteSources.OWNER_C to RemoteSources.REPO_C).firstNotNullOfOrNull { (o, r) ->
            fetchJson(RemoteSources.apiQa(o, r, normalized))?.let { parseQa(it) }
        }
    }

    suspend fun fetchMarket(): MarketSnapshot = withContext(Dispatchers.IO) {
        fetchJson(RemoteSources.apiMarket())?.let { o ->
            MarketSnapshot(
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
                market = o.optJSONObject("market")?.toMap() ?: emptyMap(),
                phase = o.opt("phase"),
                hotNews = o.optJSONArray("hot_news")?.toMapList() ?: emptyList(),
            )
        } ?: MarketSnapshot()
    }

    /** 消息面：实时 7x24、hot_news/news_extra 与 news_impact v2 统一合并。 */
    suspend fun fetchNews(): NewsSnapshot = withContext(Dispatchers.IO) {
        val api = fetchJson(RemoteSources.apiNews())
            ?: fetchJson(RemoteSources.apiNews(RemoteSources.OWNER_C, RemoteSources.REPO_C))
        val extra = fetchJson(RemoteSources.newsExtra())
            ?: fetchJson(RemoteSources.newsExtra(RemoteSources.OWNER_C, RemoteSources.REPO_C))
        val impact = fetchJson(RemoteSources.newsImpactV2())
            ?: fetchJson(RemoteSources.newsImpact())
            ?: fetchJson(RemoteSources.newsImpactV2(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: fetchJson(RemoteSources.newsImpact(RemoteSources.OWNER_C, RemoteSources.REPO_C))
        val rawItems = ArrayList<Map<String, Any?>>()
        api?.optJSONObject("hot_news")?.optJSONArray("items")?.let { rawItems.addAll(it.toMapList()) }
        api?.optJSONObject("extra")?.optJSONArray("items")?.let { rawItems.addAll(it.toMapList()) }
        api?.optJSONArray("items")?.let { rawItems.addAll(it.toMapList()) }
        extra?.optJSONArray("items")?.let { rawItems.addAll(it.toMapList()) }
        extra?.optJSONArray("news")?.let { rawItems.addAll(it.toMapList()) }
        impact?.optJSONArray("items")?.let { rawItems.addAll(it.toMapList()) }
        impact?.optJSONArray("news")?.let { rawItems.addAll(it.toMapList()) }

        fun canonical(item: Map<String, Any?>): Map<String, Any?> {
            val title = (item["title"] ?: item["newsTitle"] ?: item["digest"] ?: item["content"])?.toString()?.trim().orEmpty()
            val time = (item["time"] ?: item["updated"] ?: item["ctime"] ?: item["ts"])?.toString()
            val stocks = (item["stocks"] as? List<*>) ?: (item["codes"] as? List<*>) ?: emptyList<Any?>()
            val themes = when (val raw = item["themes"] ?: item["theme"] ?: item["affected_sectors"] ?: item["sectors"]) {
                is List<*> -> raw
                null -> emptyList<Any?>()
                else -> listOf(raw)
            }
            val image = listOf("image", "img", "cover", "image_url", "images").asSequence().flatMap { key ->
                when (val value = item[key]) {
                    is String -> sequenceOf(value)
                    is List<*> -> value.asSequence().mapNotNull { it?.toString() }
                    else -> emptySequence()
                }
            }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            return item + mapOf(
                "id" to (item["id"] ?: item["news_id"] ?: ""),
                "title" to title,
                "source" to (item["source"] ?: item["source_name"] ?: item["src"] ?: ""),
                "time" to time,
                "themes" to themes,
                "verdict" to (item["verdict"] ?: "insufficient"),
                "direction" to (item["direction"] ?: item["sentiment"] ?: "insufficient"),
                "positive_reason" to (item["positive_reason"] ?: item["reason_positive"] ?: item["positive"] ?: "insufficient"),
                "negative_reason" to (item["negative_reason"] ?: item["reason_negative"] ?: item["negative"] ?: "insufficient"),
                "reason" to (item["reason"] ?: item["analysis"] ?: "insufficient"),
                "stocks" to stocks,
                "codes" to (item["codes"] ?: stocks),
                "image" to image,
                "url" to (item["url"] ?: item["source_url"] ?: ""),
                "status" to (item["status"] ?: "insufficient"),
            )
        }
        val items = rawItems.map(::canonical)
            .filter { (it["title"] as? String)?.isNotBlank() == true }
            .distinctBy { item ->
                val id = item["id"]?.toString().orEmpty()
                if (id.isNotBlank()) id else "${item["title"]}|${item["time"] ?: ""}"
            }.take(50)
        val root = api ?: extra ?: impact
        NewsSnapshot(
            schemaVersion = (root?.opt("schema_version") ?: root?.opt("schemaVersion"))?.toString(),
            date = root?.optString("date").takeIf { !it.isNullOrEmpty() },
            n = root?.optIntOrNull("n") ?: items.size,
            ts = root?.optString("ts").takeIf { !it.isNullOrEmpty() },
            digest = root?.optJSONObject("digest")?.toMap() ?: emptyMap(),
            items = items,
            themes = root?.optJSONObject("themes")?.optJSONArray("themes")?.toMapList() ?: emptyList(),
            impacts = impact?.optJSONArray("impacts")?.toMapList() ?: emptyList(),
            transmission = root?.optJSONObject("transmission")?.optJSONArray("items")?.toMapList() ?: emptyList(),
        )
    }

    /** 板块: A 仓优先, 退 C 仓 */
    suspend fun fetchSectors(): SectorsSnapshot = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiSectors())
            ?: fetchJson(RemoteSources.apiSectors(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: return@withContext SectorsSnapshot()
        val poolRows = o.optJSONObject("sector_pool")?.let { pool ->
            listOf("tier1", "tier2", "tier3").flatMap { key -> pool.optJSONArray(key)?.toMapList() ?: emptyList() }
        }?.mapNotNull { row ->
            val name = (row["sector"] ?: row["name"])?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name to row
        }?.toMap().orEmpty()
        fun boards(name: String): List<Board> =
            o.optJSONArray(name)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val b = arr.optJSONObject(i) ?: return@mapNotNull null
                    val bk = b.optString("bk").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val boardName = b.optString("name").takeIf { it.isNotEmpty() }
                    val pool = boardName?.let { poolRows[it] }
                    Board(
                        bk = bk,
                        name = boardName,
                        totalMktcap = b.optDoubleOrNull("total_mktcap"),
                        nStocks = b.optIntOrNull("n_stocks"),
                        heat = pool?.get("score")?.toString()?.toDoubleOrNull()
                            ?: b.optDoubleOrNull("score") ?: b.optDoubleOrNull("heat"),
                        chg1d = pool?.get("avg_chg_1d")?.toString()?.toDoubleOrNull()
                            ?: b.optDoubleOrNull("chg_1d") ?: b.optDoubleOrNull("changePct") ?: b.optDoubleOrNull("avg_chg_1d"),
                        chg5d = pool?.get("chg_5d")?.toString()?.toDoubleOrNull() ?: b.optDoubleOrNull("chg_5d"),
                    )
                }
            }?.sortedByDescending { it.heat ?: Double.NEGATIVE_INFINITY } ?: emptyList()
        val members = mutableMapOf<String, Map<String, Any?>>()
        o.optJSONObject("board_members")?.let { bm ->
            bm.keys().forEach { k -> bm.optJSONObject(k)?.let { members[k] = it.toMap() } }
        }
        SectorsSnapshot(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            industries = boards("industries"),
            concepts = boards("concepts"),
            boardMembers = members,
        )
    }

    private suspend fun fetchBundle(code: String): StockDetail? = withContext(Dispatchers.IO) {
        val url = RemoteSources.shardBundle(code) ?: return@withContext null
        val o = fetchJson(url) ?: return@withContext null
        StockDetail(
            code = o.optString("code").takeIf { it.isNotEmpty() } ?: code,
            name = o.optString("name").takeIf { it.isNotEmpty() },
            detail = BundleDetail(
                status = "ok",
                ts = o.optString("ts").takeIf { it.isNotEmpty() },
                quote = o.optJSONObject("quote")?.toDoubleMap() ?: emptyMap(),
                technical = o.optJSONObject("technical")?.toDoubleMap() ?: emptyMap(),
                financialLatest = o.optJSONArray("financial")?.optJSONObject(0)?.toMap() ?: emptyMap(),
            ),
        )
    }

    // ---------- 解析 ----------

    /** 代码归一化：A 股 6 位；港股 hk+5 位（app 自选里两种都有） */
    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.startsWith("hk")) {
            val d = s.filter { it.isDigit() }.padStart(5, '0')
            return if (d == "00000") null else "hk$d"
        }
        val digits = s.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val b = digits.padStart(6, '0')
        return if (b.length == 6) b else null
    }

    /** 腾讯接口用的带前缀代码 */
    fun prefixed(code: String): String {
        val n = normalize(code) ?: return code
        if (n.startsWith("hk")) return n
        return when (n.take(2)) {
            "60", "68", "90", "11", "13" -> "sh$n"
            "43", "83", "87", "88", "92" -> "bj$n"
            else -> "sz$n"
        }
    }

    fun parseDecision(o: JSONObject?): Decision? {
        if (o == null) return null
        val cardsArr = o.optJSONArray("cards")
        val cards = (0 until (cardsArr?.length() ?: 0)).mapNotNull { i ->
            val c = cardsArr?.optJSONObject(i) ?: return@mapNotNull null
            val code = c.optString("code").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DecisionCard(
                code = code,
                name = c.optString("name").takeIf { it.isNotEmpty() },
                price = c.optDoubleOrNull("price"),
                pctChg = c.pctChg(),
                composite = c.optDoubleOrNull("composite"),
                reasons = c.optJSONArray("reasons")?.toStringList() ?: emptyList(),
                action = c.optString("action").takeIf { it.isNotEmpty() },
                confidence = c.optDoubleOrNull("confidence"),
                entry = c.optDoubleOrNull("entry"),
                stop = c.optDoubleOrNull("stop"),
                target = c.optDoubleOrNull("target"),
                holdingDays = c.optIntOrNull("holding_days"),
                risk = c.optString("risk").takeIf { it.isNotEmpty() },
                why = c.optJSONObject("why")?.toMap() ?: emptyMap(),
                dims = c.optJSONObject("dims")?.toMap() ?: emptyMap(),
                detail = c.optJSONObject("detail")?.let { parseBundle(it) },
            )
        }
        return Decision(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            engine = o.optString("engine").takeIf { it.isNotEmpty() },
            marketRegime = o.optString("market_regime").takeIf { it.isNotEmpty() },
            regimeDetail = o.optString("regime_detail").takeIf { it.isNotEmpty() },
            positionAdvice = o.optJSONObject("position_advice")?.toMap() ?: emptyMap(),
            cards = cards,
            avoid = o.optJSONArray("avoid")?.toAnyList() ?: emptyList(),
            evidence = o.optJSONObject("evidence")?.toMap() ?: emptyMap(),
            sources = o.optJSONObject("sources")?.toMap() ?: emptyMap(),
        )
    }

    fun parsePicks(o: JSONObject?): TodayPicks? {
        if (o == null) return null
        val picks = o.optJSONObject("picks")
        fun parseArr(name: String): List<PickSignal> {
            val arr = picks?.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = e.optString("code").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PickSignal(
                    code = code,
                    name = e.optString("name").takeIf { it.isNotEmpty() },
                    imageUrl = listOf("img", "image", "imageUrl", "image_url", "cover", "images")
                        .asSequence().flatMap { key ->
                            when (val v = e.opt(key)) {
                                is JSONArray -> (0 until v.length()).asSequence().mapNotNull { v.optString(it).takeIf { s -> s.isNotBlank() } }
                                else -> sequenceOf(e.optString(key))
                            }
                        }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") },
                    price = e.optDoubleOrNull("price"),
                    pctChg = e.pctChg(),
                    score = e.optDoubleOrNull("score"),
                    buyable = when {
                        e.has("buyable") && !e.isNull("buyable") -> e.optBoolean("buyable")
                        e.has("can_buy") && !e.isNull("can_buy") -> e.optBoolean("can_buy")
                        else -> null
                    },
                    snapshotStatus = e.optString("status").takeIf { it.isNotBlank() },
                    engine = e.optString("engine").takeIf { it.isNotEmpty() },
                    why = e.optJSONArray("why")?.toStringList() ?: emptyList(),
                    detail = e.optJSONObject("detail")?.let { parseBundle(it) },
                )
            }
        }
        return TodayPicks(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            date = o.optString("date").takeIf { it.isNotEmpty() },
            session = o.optString("session").takeIf { it.isNotEmpty() },
            market = o.optJSONObject("market")?.toMap() ?: emptyMap(),
            signals = parseArr("signals"),
            values = parseArr("values"),
            limitUp = parseArr("limit_up"),
            sources = o.optJSONObject("sources")?.toMap() ?: emptyMap(),
        )
    }

    private fun parseStockDetail(o: JSONObject, fallbackCode: String): StockDetail {
        val scores = mutableMapOf<String, Double?>()
        o.optJSONObject("engine_scores")?.let { es ->
            es.keys().forEach { k -> scores[k] = es.optDoubleOrNull(k) }
        }
        return StockDetail(
            code = o.optString("code").takeIf { it.isNotEmpty() } ?: fallbackCode,
            name = o.optString("name").takeIf { it.isNotEmpty() },
            engineScores = scores,
            boards = o.optJSONArray("boards")?.toStringList() ?: emptyList(),
            detail = o.optJSONObject("detail")?.let { parseBundle(it) },
            marketPhase = o.optJSONObject("market_phase")?.toMap() ?: emptyMap(),
        )
    }

    private fun parseBundle(o: JSONObject): BundleDetail = BundleDetail(
        status = o.optString("status").takeIf { it.isNotEmpty() } ?: "insufficient",
        reason = o.optString("reason").takeIf { it.isNotEmpty() },
        ts = o.optString("ts").takeIf { it.isNotEmpty() },
        quote = o.optJSONObject("quote")?.toDoubleMap() ?: emptyMap(),
        technical = o.optJSONObject("technical")?.toDoubleMap() ?: emptyMap(),
        financialLatest = o.optJSONObject("financial_latest")?.toMap() ?: emptyMap(),
    )

    private fun parseQa(o: JSONObject): AiQa = AiQa(
        status = o.optString("status").takeIf { it.isNotEmpty() } ?: "insufficient",
        reason = o.optString("reason").takeIf { it.isNotEmpty() },
        why = o.optString("why").takeIf { it.isNotEmpty() },
        risk = o.optString("risk").takeIf { it.isNotEmpty() },
        focus = o.optString("focus").takeIf { it.isNotEmpty() },
        model = o.optString("model").takeIf { it.isNotEmpty() },
        ts = o.optString("ts").takeIf { it.isNotEmpty() },
    )

    /** app 读 pctChg（驼峰），线上 ship chg_pct —— 两者都试 */
    private fun JSONObject.pctChg(): Double? =
        optDoubleOrNull("pctChg") ?: optDoubleOrNull("chg_pct")
            ?: optDoubleOrNull("pct") ?: optDoubleOrNull("ret_pct")

    // ---------- org.json 小工具 ----------

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key).takeIf { it != Int.MIN_VALUE } else null

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        keys().forEach { k -> out[k] = valueOf(this, k) }
        return out
    }

    private fun JSONObject.toDoubleMap(): Map<String, Double?> {
        val out = LinkedHashMap<String, Double?>()
        keys().forEach { k -> out[k] = optDoubleOrNull(k) }
        return out
    }

    private fun JSONArray.toMapList(): List<Map<String, Any?>> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.toMap() }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }

    private fun JSONArray.toAnyList(): List<Any?> =
        (0 until length()).map { i -> valueOf(this, i) }

    private fun valueOf(o: JSONObject, key: String): Any? = when (val v = o.opt(key)) {
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { i -> valueOf(v, i) }
        else -> v
    }

    private fun valueOf(a: JSONArray, i: Int): Any? = when (val v = a.opt(i)) {
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { j -> valueOf(v, j) }
        else -> v
    }
}
