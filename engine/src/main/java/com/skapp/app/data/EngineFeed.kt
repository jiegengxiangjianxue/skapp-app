package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端引擎产物客户端（qwerzxcva/stocks-ai-view）
 *
 * 拉取 CI 产出的 JSON：engine_v5 / cycle_pool_v2 / rps_rotation / strategy_card
 *     / market_phase / global_markets / ai_pick_v4 / decision
 * 公开仓，可匿名访问，无 token 需求。
 */
object EngineFeed {
    private const val OWNER = "qwerzxcva"
    private const val REPO = "stocks-ai-view"
    private const val BRANCH = "main"
    private const val BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/data"
    private const val TIMEOUT = 8000

    // ── 数据类 ──────────────────────────────────────────────

    data class EngineV5Top(
        val rank: Int,
        val code: String,
        val date: String,
        val price: Double?,
        val score: Double,
        val resonance: Double,
        val bull: Int,
        val bear: Int,
        val neutral: Int,
        val votes: List<Vote>,
    )
    data class Vote(val indicator: String, val vote: Int, val strength: Double, val why: String)

    data class RpsTop(
        val rank: Int,
        val symbol: String,
        val strongScore: Double,
        val rps20: Double?,
        val rps60: Double?,
        val rps120: Double?,
        val rps250: Double?,
    )

    data class StrategyCandidate(
        val symbol: String,
        val close: Double,
        val retPct: Double,
        val vratio: Double,
        val pos240: Double,
    )

    data class DecisionCard(
        val code: String,
        val name: String,
        val price: Double?,
        val chgPct: Double?,
        val composite: Double,
        val dims: Map<String, Double>,
        val action: String,
        val confidence: Double,
    )

    data class MarketPhaseDaily(
        val date: String,
        val firstBoard: Int,
        val ge2: Int,
        val height: Int,
        val promo: Double,
        val seal: Double,
    )

    data class CycleMeta(
        val index: String,
        val indexName: String,
        val nDays: Int,
        val spanStart: String,
        val spanEnd: String,
    )

    data class GlobalMarketsMeta(
        val ts: String,
        val engine: String,
        val source: String,
        val nIndices: Int,
        val nOk: Int,
        val sentiment: Double?,
    )

    data class EngineFeeds(
        val ts: Long,
        val engineV5: EngineV5Payload?,
        val cyclePool: CycleMeta?,
        val rpsRotation: RpsPayload?,
        val strategyCard: StrategyCard?,
        val marketPhase: MarketPhase?,
        val globalMarkets: GlobalMarkets?,
        val aiPick: AiPick?,
        val decision: Decision?,
        val missing: List<String>,
    )

    data class EngineV5Payload(
        val ts: String,
        val top: List<EngineV5Top>,
        val staleTop: List<EngineV5Top>,
    )

    data class RpsPayload(
        val ts: String,
        val date: String,
        val top: List<RpsTop>,
        val note: String,
    )

    data class StrategyCard(
        val ts: String,
        val strategy: String,
        val tradeOk: Boolean,
        val candidates: List<StrategyCandidate>,
        val note: String,
    )

    data class MarketPhase(
        val ts: String,
        val daily: List<MarketPhaseDaily>,
        val labels: Map<String, String>,
    )

    data class GlobalMarkets(
        val ts: String,
        val nIndices: Int,
        val nOk: Int,
        val sentiment: Double?,
    )

    data class AiPick(
        val ts: String,
        val nScanned: Int,
        val nCandidates: Int,
    )

    data class Decision(
        val ts: String,
        val marketRegime: String,
        val positionAdvice: PositionAdvice,
        val cards: List<DecisionCard>,
        val avoid: List<String>,
    )

    data class PositionAdvice(
        val level: Double,
        val label: String,
        val cap: Double,
        val reason: String,
    )

    // ── 拉取 ────────────────────────────────────────────────

    suspend fun loadAll(): EngineFeeds = withContext(Dispatchers.IO) {
        val files = listOf(
            "engine_v5.json" to "engine_v5",
            "cycle_pool_v2.json" to "cycle_pool",
            "rps_rotation.json" to "rps",
            "strategy_card.json" to "strategy",
            "market_phase.json" to "phase",
            "global_markets.json" to "global",
            "ai_pick_v4.json" to "aipick",
            "decision.json" to "decision",
        )
        val results = mutableMapOf<String, JSONObject>()
        val missing = mutableListOf<String>()
        files.forEach { (file, key) ->
            try {
                val url = "$BASE/$file"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "skapp-android/1.0")
                if (conn.responseCode == 200) {
                    val s = conn.inputStream.bufferedReader().readText()
                    results[key] = JSONObject(s)
                } else {
                    missing.add(file)
                }
                conn.disconnect()
            } catch (_: Exception) {
                missing.add(file)
            }
        }
        EngineFeeds(
            ts = System.currentTimeMillis(),
            engineV5 = parseEngineV5(results["engine_v5"]),
            cyclePool = parseCycleMeta(results["cycle_pool"]),
            rpsRotation = parseRps(results["rps"]),
            strategyCard = parseStrategy(results["strategy"]),
            marketPhase = parseMarketPhase(results["phase"]),
            globalMarkets = parseGlobal(results["global"]),
            aiPick = parseAiPick(results["aipick"]),
            decision = parseDecision(results["decision"]),
            missing = missing,
        )
    }

    // ── 解析器 ──────────────────────────────────────────────

    private fun parseEngineV5(j: JSONObject?): EngineV5Payload? {
        if (j == null) return null
        return try {
        val top = j.optJSONArray("top")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                val votes = o.optJSONArray("votes")?.let { va ->
                    (0 until va.length()).mapNotNull { vi -> va.optJSONObject(vi)?.let { vo ->
                        Vote(
                            vo.optString("indicator", ""),
                            vo.optInt("vote"),
                            vo.optDouble("strength"),
                            vo.optString("why", ""),
                        )
                    } }
                } ?: emptyList()
                EngineV5Top(
                    rank = i + 1,
                    code = o.optString("code", ""),
                    date = o.optString("date", ""),
                    price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() },
                    score = o.optDouble("score"),
                    resonance = o.optDouble("resonance"),
                    bull = o.optInt("bull"),
                    bear = o.optInt("bear"),
                    neutral = o.optInt("neutral"),
                    votes = votes,
                )
            } } ?: emptyList()
        } ?: emptyList()
        val staleTop = j.optJSONArray("stale_top")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                EngineV5Top(
                    rank = i + 1,
                    code = o.optString("code", ""),
                    date = o.optString("date", ""),
                    price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() },
                    score = o.optDouble("score"),
                    resonance = o.optDouble("resonance"),
                    bull = o.optInt("bull"),
                    bear = o.optInt("bear"),
                    neutral = o.optInt("neutral"),
                    votes = emptyList(),
                )
            } } ?: emptyList()
        } ?: emptyList()
        EngineV5Payload(
            ts = j.optString("ts"),
            top = top,
            staleTop = staleTop,
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCycleMeta(j: JSONObject?): CycleMeta? {
        if (j == null) return null
        return try {
        val meta = j.optJSONObject("meta") ?: return null
        CycleMeta(
            index = meta.optString("index", ""),
            indexName = meta.optString("index_name", ""),
            nDays = meta.optInt("n_days", 0),
            spanStart = meta.optJSONArray("span")?.optString(0) ?: "",
            spanEnd = meta.optJSONArray("span")?.optString(1) ?: "",
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRps(j: JSONObject?): RpsPayload? {
        if (j == null) return null
        return try {
        val top = j.optJSONArray("top")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                RpsTop(
                    rank = i + 1,
                    symbol = o.optString("symbol", ""),
                    strongScore = o.optDouble("strong_score"),
                    rps20 = optNullableDouble(o, "rps_20"),
                    rps60 = optNullableDouble(o, "rps_60"),
                    rps120 = optNullableDouble(o, "rps_120"),
                    rps250 = optNullableDouble(o, "rps_250"),
                )
            } } ?: emptyList()
        } ?: emptyList()
        RpsPayload(
            ts = j.optString("ts"),
            date = j.optString("date"),
            top = top,
            note = j.optString("note", ""),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStrategy(j: JSONObject?): StrategyCard? {
        if (j == null) return null
        return try {
        val gate = j.optJSONObject("gate")
        val candidates = j.optJSONArray("candidates")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                StrategyCandidate(
                    symbol = o.optString("symbol", ""),
                    close = o.optDouble("close"),
                    retPct = o.optDouble("ret_pct"),
                    vratio = o.optDouble("vratio"),
                    pos240 = o.optDouble("pos240"),
                )
            } } ?: emptyList()
        } ?: emptyList()
        StrategyCard(
            ts = j.optString("ts"),
            strategy = j.optString("strategy", ""),
            tradeOk = gate?.optBoolean("trade_ok", false) ?: false,
            candidates = candidates,
            note = j.optString("note", ""),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMarketPhase(j: JSONObject?): MarketPhase? {
        if (j == null) return null
        return try {
        val labels = j.optJSONObject("labels")?.let { lbl ->
            mapOf(
                "ice" to lbl.optString("ice"),
                "ignite" to lbl.optString("ignite"),
                "rally" to lbl.optString("rally"),
                "climax" to lbl.optString("climax"),
                "ebb" to lbl.optString("ebb"),
                "repair" to lbl.optString("repair"),
            )
        } ?: emptyMap()
        val daily = j.optJSONArray("daily")?.let { arr ->
            (0 until minOf(arr.length(), 30)).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                MarketPhaseDaily(
                    date = o.optString("date", ""),
                    firstBoard = o.optInt("first_board"),
                    ge2 = o.optInt("ge2"),
                    height = o.optInt("height"),
                    promo = o.optDouble("promo"),
                    seal = o.optDouble("seal"),
                )
            } } ?: emptyList()
        } ?: emptyList()
        MarketPhase(
            ts = j.optString("ts"),
            daily = daily,
            labels = labels,
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGlobal(j: JSONObject?): GlobalMarkets? {
        if (j == null) return null
        return try {
        val meta = j.optJSONObject("meta")
        GlobalMarkets(
            ts = j.optString("ts"),
            nIndices = j.optInt("n_indices", 0),
            nOk = j.optInt("n_ok", 0),
            sentiment = meta?.optDouble("sentiment"),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAiPick(j: JSONObject?): AiPick? {
        if (j == null) return null
        return try {
        AiPick(
            ts = j.optString("ts"),
            nScanned = j.optInt("n_scanned", 0),
            nCandidates = j.optInt("n_candidates", 0),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDecision(j: JSONObject?): Decision? {
        if (j == null) return null
        return try {
        val advice = j.optJSONObject("position_advice")
        val cards = j.optJSONArray("cards")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
                val dims = o.optJSONObject("dims")?.let { dm ->
                    dm.keys().asSequence().associate { k -> k to dm.optDouble(k, 0.0) }
                } ?: emptyMap()
                DecisionCard(
                    code = o.optString("code", ""),
                    name = o.optString("name", ""),
                    price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() },
                    chgPct = o.optDouble("chg_pct", Double.NaN).takeIf { !it.isNaN() },
                    composite = o.optDouble("composite"),
                    dims = dims,
                    action = o.optString("action", ""),
                    confidence = o.optDouble("confidence"),
                )
            } } ?: emptyList()
        } ?: emptyList()
        val avoid = j.optJSONArray("avoid")?.let { arr ->
            (0 until arr.length()).map { i -> arr.optString(i, "") }.filter { it.isNotEmpty() }
        } ?: emptyList()
        Decision(
            ts = j.optString("ts"),
            marketRegime = j.optString("market_regime", ""),
            positionAdvice = PositionAdvice(
                level = advice?.optDouble("level", 0.0) ?: 0.0,
                label = advice?.optString("label", "") ?: "",
                cap = advice?.optDouble("cap", 0.0) ?: 0.0,
                reason = advice?.optString("reason", "") ?: "",
            ),
            cards = cards,
            avoid = avoid,
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun optNullableDouble(o: JSONObject, key: String): Double? =
        o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
}
