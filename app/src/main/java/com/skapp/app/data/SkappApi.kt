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

    /** 全市场代码表（app 只从 skapp-data 取） */
    suspend fun fetchSymbols(): List<SymbolEntry> = withContext(Dispatchers.IO) {
        val url = RemoteSources.appFile(
            RemoteSources.OWNER_SYMBOLS, RemoteSources.REPO_SYMBOLS, "search/symbols.json")
        val arr = fetchText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("s").takeIf { it.isNotEmpty() }
                ?: o.optString("symbol").takeIf { it.isNotEmpty() }
            if (code.isNullOrEmpty()) null
            else SymbolEntry(code, o.optString("n").takeIf { it.isNotEmpty() }
                ?: o.optString("name").takeIf { it.isNotEmpty() })
        }
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
        val url = RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "stock_names.json")
        val o = fetchJson(url) ?: fetchJson(
            RemoteSources.appFile(RemoteSources.OWNER_C, RemoteSources.REPO_C, "stock_names.json"))
            ?: return@withContext emptyMap()
        val map = LinkedHashMap<String, String>()
        o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { map[k] = it } }
        map
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

    /** 消息面: A 仓优先, 退 C 仓 */
    suspend fun fetchNews(): NewsSnapshot = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiNews())
            ?: fetchJson(RemoteSources.apiNews(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: return@withContext NewsSnapshot()
        val items = ArrayList<Map<String, Any?>>()
        o.optJSONObject("hot_news")?.optJSONArray("items")?.let { items.addAll(it.toMapList()) }
        o.optJSONObject("extra")?.optJSONArray("items")?.let { items.addAll(it.toMapList()) }
        NewsSnapshot(
            ts = o.optString("ts").takeIf { it.isNotEmpty() },
            digest = o.optJSONObject("digest")?.toMap() ?: emptyMap(),
            items = items,
            themes = o.optJSONObject("themes")?.optJSONArray("themes")?.toMapList() ?: emptyList(),
            impacts = o.optJSONObject("impact")?.optJSONArray("impacts")?.toMapList() ?: emptyList(),
            transmission = o.optJSONObject("transmission")?.optJSONArray("items")?.toMapList() ?: emptyList(),
        )
    }

    /** 板块: A 仓优先, 退 C 仓 */
    suspend fun fetchSectors(): SectorsSnapshot = withContext(Dispatchers.IO) {
        val o = fetchJson(RemoteSources.apiSectors())
            ?: fetchJson(RemoteSources.apiSectors(RemoteSources.OWNER_C, RemoteSources.REPO_C))
            ?: return@withContext SectorsSnapshot()
        fun boards(name: String): List<Board> =
            o.optJSONArray(name)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val b = arr.optJSONObject(i) ?: return@mapNotNull null
                    val bk = b.optString("bk").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Board(
                        bk = bk,
                        name = b.optString("name").takeIf { it.isNotEmpty() },
                        totalMktcap = b.optDoubleOrNull("total_mktcap"),
                        nStocks = b.optIntOrNull("n_stocks"),
                    )
                }
            } ?: emptyList()
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
                    price = e.optDoubleOrNull("price"),
                    pctChg = e.pctChg(),
                    score = e.optDoubleOrNull("score"),
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
