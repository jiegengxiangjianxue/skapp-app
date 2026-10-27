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
                // 腾讯字段：9/10 买一价/量，19/20 卖一价/量
                out[key] = Quote(
                    code = key,
                    name = name,
                    price = price,
                    pctChg = pct,
                    ts = ts,
                    bid1 = f.getOrNull(9)?.toDoubleOrNull(),
                    bid1Vol = f.getOrNull(10)?.toDoubleOrNull(),
                    ask1 = f.getOrNull(19)?.toDoubleOrNull(),
                    ask1Vol = f.getOrNull(20)?.toDoubleOrNull(),
                )
            }
        }
        out
    }

    /**
     * 结构化推股（盘前/午盘）云端入口——**过渡兼容垫片**。
     *
     * 拆分时这条链路的云端解析（parseStructuredPick）已随 CloudFeed 一起下掉，改由端上
     * `LocalPicks` 产出结构化推股；但界面侧切换在这条提交之后，故先留一个空实现顶着，
     * 避免中间提交「引用了不存在的符号」。界面切换完成后（B 提交）即删除。
     */
    @Deprecated("结构化推股已改为端上 LocalPicks 产物，此入口仅过渡兼容")
    suspend fun fetchStructuredPicks(): Map<String, StructuredPickArtifact> = emptyMap()

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
            val rows = ArrayList<Map<String, Any?>>()
            root?.optJSONArray("items")?.let { rows.addAll(it.toPlainMapList()) }
            root?.optJSONArray("news")?.let { rows.addAll(it.toPlainMapList()) }
            root?.optJSONArray("impacts")?.let { rows.addAll(it.toPlainMapList()) }
            rows.distinctBy { "${it["id"] ?: it["news_id"] ?: ""}|${it["title"] ?: it["digest"] ?: ""}|${it["ts"] ?: it["time"] ?: ""}" }
                .takeIf { it.isNotEmpty() }
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

    suspend fun fetchCloseReview(): CloseReview = withContext(Dispatchers.IO) {
        val root = fetchJson(RemoteSources.closeReview())
            ?: fetchJson(RemoteSources.closeReview(RemoteSources.OWNER_C, RemoteSources.REPO_C))
        parseCloseReview(root)
    }

    suspend fun fetchWinrateMatrix(): WinrateMatrix = withContext(Dispatchers.IO) {
        val root = fetchJson(RemoteSources.winrateMatrix())
            ?: fetchJson(RemoteSources.winrateMatrix(RemoteSources.OWNER_C, RemoteSources.REPO_C))
        parseWinrateMatrix(root)
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
                market = o.optJSONObject("market")?.toPlainMap() ?: emptyMap(),
                phase = o.opt("phase"),
                hotNews = o.optJSONArray("hot_news")?.toPlainMapList() ?: emptyList(),
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
        api?.optJSONObject("hot_news")?.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONObject("extra")?.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONObject("digest")?.optJSONObject("categories")?.let { categories ->
            categories.keys().forEach { category ->
                categories.optJSONArray(category)?.toPlainMapList()?.forEach { rawItems.add(it + mapOf("cat" to category)) }
            }
        }
        api?.optJSONObject("themes")?.optJSONArray("themes")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONArray("hot_reports")?.let { rawItems.addAll(it.toPlainMapList()) }
        extra?.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
        extra?.optJSONArray("news")?.let { rawItems.addAll(it.toPlainMapList()) }
        impact?.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
        impact?.optJSONArray("news")?.let { rawItems.addAll(it.toPlainMapList()) }
        impact?.optJSONArray("impacts")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONArray("impacts")?.let { rawItems.addAll(it.toPlainMapList()) }
        api?.optJSONObject("impact")?.let { nested ->
            nested.optJSONArray("items")?.let { rawItems.addAll(it.toPlainMapList()) }
            nested.optJSONArray("impacts")?.let { rawItems.addAll(it.toPlainMapList()) }
        }

        fun canonical(item: Map<String, Any?>): Map<String, Any?> {
            val title = (item["title"] ?: item["newsTitle"] ?: item["digest"] ?: item["content"])?.toString()?.trim().orEmpty()
            val time = (item["time"] ?: item["updated"] ?: item["ctime"] ?: item["ts"])?.toString()
            val stocks = (item["stocks"] as? List<*>) ?: (item["affected_stocks"] as? List<*>)
                ?: (item["codes"] as? List<*>) ?: emptyList<Any?>()
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
                "boards" to (item["boards"] ?: item["sectors"] ?: item["affected_sectors"] ?: emptyList<Any?>()),
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
        val prioritized = rawItems.sortedByDescending { item ->
            listOf("stocks", "affected_stocks", "codes").any { key ->
                when (val value = item[key]) {
                    is List<*> -> value.isNotEmpty()
                    is String -> value.isNotBlank()
                    else -> false
                }
            }
        }
        val items = prioritized.map(::canonical)
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
            digest = root?.optJSONObject("digest")?.toPlainMap() ?: emptyMap(),
            items = items,
            themes = root?.optJSONObject("themes")?.optJSONArray("themes")?.toPlainMapList() ?: emptyList(),
            impacts = (impact?.optJSONArray("impacts")
                ?: api?.optJSONArray("impacts")
                ?: api?.optJSONObject("impact")?.optJSONArray("impacts"))?.toPlainMapList() ?: emptyList(),
            transmission = root?.optJSONObject("transmission")?.optJSONArray("items")?.toPlainMapList() ?: emptyList(),
        )
    }

    /** 板块: A 仓优先, 退 C 仓 */
    suspend fun fetchSectors(): SectorsSnapshot = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiSectors())
            ?: fetchJson(RemoteSources.apiSectors(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: return@withContext SectorsSnapshot()
        val poolRows = o.optJSONObject("sector_pool")?.let { pool ->
            listOf("tier1", "tier2", "tier3").flatMap { key -> pool.optJSONArray(key)?.toPlainMapList() ?: emptyList() }
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
            bm.keys().forEach { k -> bm.optJSONObject(k)?.let { members[k] = it.toPlainMap() } }
        }
        SectorsSnapshot(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            status = o.optString("status").takeIf { it.isNotEmpty() },
            degradedReason = o.optString("degraded_reason").takeIf { it.isNotEmpty() && it != "null" },
            freshBoards = o.optJSONObject("freshness")?.optInt("fresh_boards") ?: 0,
            staleBoards = o.optJSONObject("freshness")?.optInt("stale_boards") ?: 0,
            industries = boards("industries"),
            concepts = boards("concepts"),
            boardMembers = members,
        )
    }

    /** 单股 API 财务兜底：分片 bundle 未覆盖该股时，直接读 api/stocks/{code}.json。 */
    suspend fun fetchStockFinancial(code: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiStock(RemoteSources.OWNER_A, RemoteSources.REPO_A, code))
            ?: fetchJson(RemoteSources.apiStock(RemoteSources.OWNER_C, RemoteSources.REPO_C, code))
            ?: return@withContext null
        val direct = o.optJSONObject("financial_latest")?.toPlainMap()
        val arr = o.optJSONArray("financial")?.optJSONObject(0)?.toPlainMap()
        (direct ?: arr)?.takeIf { it.isNotEmpty() }
    }

    /** 数据源台账：A 仓优先，退 C 仓；不可达时返回空快照，由 UI 显示暂无数据。 */
    suspend fun fetchDataSources(): DataSourcesSnapshot = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiSources())
            ?: fetchJson(RemoteSources.apiSources(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: return@withContext DataSourcesSnapshot()
        val items = o.optJSONArray("items")?.toPlainMapList()?.map { row ->
            DataSourceStatus(
                id = row["id"]?.toString() ?: "",
                name = row["name"]?.toString(),
                group = row["group"]?.toString(),
                status = row["status"]?.toString() ?: "missing",
                hasFetcher = row["has_fetcher"] == true || row["has_fetcher"]?.toString() == "true",
                note = row["note"]?.toString(),
            )
        } ?: emptyList()
        fun ids(key: String): List<String> = o.optJSONArray(key)?.toStringList() ?: emptyList()
        DataSourcesSnapshot(
            ts = o.optString("ts").takeIf { it.isNotBlank() },
            total = o.optIntOrNull("total") ?: items.size,
            usable = o.optIntOrNull("usable") ?: 0,
            withFetcher = o.optIntOrNull("with_fetcher") ?: 0,
            live = o.optIntOrNull("live") ?: 0,
            gap = o.optIntOrNull("usable_without_fetcher") ?: 0,
            blocked = o.optIntOrNull("blocked_or_unauth") ?: 0,
            groups = o.optJSONObject("groups")?.toPlainMap()
                ?.mapValues { (_, v) -> (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap() }
                ?: emptyMap(),
            mergedIds = ids("merged_ids"),
            gapIds = ids("gap_ids"),
            blockedIds = ids("blocked_ids"),
            items = items,
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
                financialLatest = o.optJSONArray("financial")?.optJSONObject(0)?.toPlainMap() ?: emptyMap(),
            ),
        )
    }

    // ---------- 解析 ----------

    /** 代码归一化：实现已下沉到 core 的 [Code]，这里保留同名入口，调用方不用改。 */
    fun normalize(raw: String): String? = Code.normalize(raw)

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
                why = c.optJSONObject("why")?.toPlainMap() ?: emptyMap(),
                dims = c.optJSONObject("dims")?.toPlainMap() ?: emptyMap(),
                detail = c.optJSONObject("detail")?.let { parseBundle(it) },
            )
        }
        return Decision(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            engine = o.optString("engine").takeIf { it.isNotEmpty() },
            marketRegime = o.optString("market_regime").takeIf { it.isNotEmpty() },
            regimeDetail = o.optString("regime_detail").takeIf { it.isNotEmpty() },
            positionAdvice = o.optJSONObject("position_advice")?.toPlainMap() ?: emptyMap(),
            cards = cards,
            avoid = o.optJSONArray("avoid")?.toAnyList() ?: emptyList(),
            evidence = o.optJSONObject("evidence")?.toPlainMap() ?: emptyMap(),
            sources = o.optJSONObject("sources")?.toPlainMap() ?: emptyMap(),
        )
    }

    fun parsePicks(o: JSONObject?): TodayPicks? {
        if (o == null) return null
        val picks = o.optJSONObject("picks") ?: o
        fun parseArr(name: String): List<PickSignal> {
            val arr = picks.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> parsePick(arr.optJSONObject(i)) }
        }
        return TodayPicks(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            date = o.optString("date").takeIf { it.isNotEmpty() },
            session = o.optString("session").takeIf { it.isNotEmpty() },
            market = o.optJSONObject("market")?.toPlainMap() ?: emptyMap(),
            signals = parseArr("signals"),
            values = parseArr("values"),
            limitUp = parseArr("limit_up"),
            sources = o.optJSONObject("sources")?.toPlainMap() ?: emptyMap(),
        )
    }

    private fun parsePick(e: JSONObject?): PickSignal? {
        if (e == null) return null
        val code = e.optString("code").takeIf { it.isNotEmpty() } ?: return null
        val image = listOf("img", "image", "imageUrl", "image_url", "cover", "images")
            .asSequence().flatMap { key ->
                when (val v = e.opt(key)) {
                    is JSONArray -> (0 until v.length()).asSequence().mapNotNull { v.optString(it).takeIf { s -> s.isNotBlank() } }
                    else -> sequenceOf(e.optString(key))
                }
            }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        return PickSignal(
            code = code,
            name = e.optString("name").takeIf { it.isNotEmpty() },
            imageUrl = image,
            price = e.optDoubleOrNull("price") ?: e.optDoubleOrNull("live_price"),
            pctChg = e.pctChg(),
            score = e.optDoubleOrNull("score") ?: e.optDoubleOrNull("central_score"),
            buyable = when {
                e.has("buyable") && !e.isNull("buyable") -> e.optBoolean("buyable")
                e.has("can_buy") && !e.isNull("can_buy") -> e.optBoolean("can_buy")
                else -> null
            },
            snapshotStatus = e.optString("status").takeIf { it.isNotBlank() },
            engine = e.optString("engine").takeIf { it.isNotEmpty() },
            resonance = e.optDoubleOrNull("resonance") ?: e.optDoubleOrNull("resonance_ratio"),
            reason = listOf("reason", "buyability_reason", "why_reason").firstNotNullOfOrNull { key ->
                e.optString(key).takeIf { it.isNotBlank() }
            },
            calculationTime = listOf("calculation_time", "calculated_at", "live_price_ts", "ts", "generated_at")
                .firstNotNullOfOrNull { key -> e.optString(key).takeIf { it.isNotBlank() } },
            why = e.optJSONArray("why")?.toStringList() ?: emptyList(),
            detail = e.optJSONObject("detail")?.let { parseBundle(it) },
        )
    }

    private fun parseCloseReview(root: JSONObject?): CloseReview {
        if (root == null) return CloseReview()
        val stages = listOf("premarket", "midday", "morning", "afternoon", "tradable_plans", "premarket_review", "midday_review")
            .mapNotNull { key -> root.optJSONObject(key)?.let { key to it.toPlainMap() } }.toMap()
        return CloseReview(
            generatedAt = root.optString("generated_at").takeIf { it.isNotBlank() },
            date = root.optString("date").takeIf { it.isNotBlank() },
            status = root.optString("status").takeIf { it.isNotBlank() } ?: "insufficient",
            reason = root.optString("reason").takeIf { it.isNotBlank() }
                ?: root.optJSONObject("market_window")?.optString("reason")?.takeIf { it.isNotBlank() },
            stages = stages,
        )
    }

    private fun parseWinrateMatrix(root: JSONObject?): WinrateMatrix {
        if (root == null) return WinrateMatrix()
        val horizons = root.optJSONObject("horizons")?.toPlainMap()?.mapValues { (_, value) ->
            @Suppress("UNCHECKED_CAST")
            value as? Map<String, Any?> ?: emptyMap()
        } ?: emptyMap()
        return WinrateMatrix(
            ts = root.optString("ts").takeIf { it.isNotBlank() },
            sourceTs = root.optString("source_ts").takeIf { it.isNotBlank() },
            source = root.optString("source").takeIf { it.isNotBlank() },
            horizons = horizons,
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
            marketPhase = o.optJSONObject("market_phase")?.toPlainMap() ?: emptyMap(),
        )
    }

    private fun parseBundle(o: JSONObject): BundleDetail = BundleDetail(
        status = o.optString("status").takeIf { it.isNotEmpty() } ?: "insufficient",
        reason = o.optString("reason").takeIf { it.isNotEmpty() },
        ts = o.optString("ts").takeIf { it.isNotEmpty() },
        quote = o.optJSONObject("quote")?.toDoubleMap() ?: emptyMap(),
        technical = o.optJSONObject("technical")?.toDoubleMap() ?: emptyMap(),
        financialLatest = o.optJSONObject("financial_latest")?.toPlainMap() ?: emptyMap(),
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

    private fun JSONObject.toDoubleMap(): Map<String, Double?> {
        val out = LinkedHashMap<String, Double?>()
        keys().forEach { k -> out[k] = optDoubleOrNull(k) }
        return out
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }

    private fun JSONArray.toAnyList(): List<Any?> = toPlainList()

}
