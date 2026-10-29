package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端引擎产物解析器（qwerzxcva/stocks-ai-view）。
 *
 * 解析 CI 产出的 JSON：engine_v5 / cycle_pool_v2 / rps_rotation / strategy_card
 *     / market_phase / global_markets / ai_pick_v4 / decision。
 *
 * ⚠️ 定位：这些是**已发生的归档**（收盘后算一次）。当日分析请走 [EngineFeedLive]
 * （实时快照 + 本地 12 引擎装配），本类只用于「回看/对照」。
 * 取数必须经 [Db.archiveMany]，不得绕过库层直连云端。
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
        /** 口径说明：本地实时聚合时写明「1 日横截面，非历史周期序列」。 */
        val note: String = "",
        /**
         * 本地板块排名（云端归档没有这部分，端上现算才有）。
         * 为空表示该来源只给元信息（云端 cycle_pool_v2 就是这种）。
         */
        val rows: List<CycleRow> = emptyList(),
    )

    /** 板块周期行：当日强度 + 广度 + 量能占比 → 一句阶段判断。 */
    data class CycleRow(
        val name: String,
        val chg1d: Double?,
        val heatShare: Double?,   // 成交额占全市场比例（0~1）
        val nUp: Int,
        val nDown: Int,
        val nStocks: Int,
        val phase: String,
        /** 5 日累计涨幅（%）：本地日级快照攒够 5 天才给，否则 null（不是 0） */
        val ret5d: Double? = null,
        /** 连续上涨天数（含今日）：攒够 2 天才给 */
        val upStreak: Int = 0,
        /** 成员当根收盘在区间的平均位置（0~1）：判断「止跌结构」而不是只看涨跌幅。 */
        val closePos: Double? = null,
        /** 长下影且收阴的成员家数。 */
        val longShadowN: Int = 0,
        /** 止跌雏形 / 修复中 / 中性 / 单边下杀；缺 high-low 时为 null。 */
        val stopping: String? = null,
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
        /** 产出方：live=端上实时算（[EngineFeedLive]）/ cloud=云端归档回看（[loadAll]）。 */
        val source: String = "cloud",
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
        /** 产出方式：cloud-llm=云端 LLM 归档 / local-score=端上评分规则（不是 LLM，界面要标出来）。 */
        val basis: String = "cloud-llm",
        val note: String = "",
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

    /**
     * 拉取全部引擎产出。
     *
     * 原来这里是 8 个文件串行 openConnection，任何一个卡住都会把整轮拖到超时，
     * 失败也只是静默丢进 missing。现在统一走 CloudFeed：并发拉取、内存缓存、
     * 按文件自身 ts 标陈旧、失败退回旧缓存——单文件挂掉不再连累其它引擎。
     */
    suspend fun loadAll(): EngineFeeds = withContext(Dispatchers.IO) {
        val docs = Db.compareDocs(
            listOf(
                ArchiveDoc.ENGINE_V5,
                ArchiveDoc.CYCLE_POOL,
                ArchiveDoc.RPS_ROTATION,
                ArchiveDoc.STRATEGY_CARD,
                ArchiveDoc.MARKET_PHASE,
                ArchiveDoc.GLOBAL_MARKETS,
                ArchiveDoc.AI_PICK_V4,
                ArchiveDoc.DECISION,
            )
        )
        val results = mutableMapOf<String, JSONObject>()
        val missing = mutableListOf<String>()
        fun put(key: String, doc: ArchiveDoc) {
            val d = docs[doc]
            // 拆模块后 d.json 是跨模块 public 属性，先落成局部值再判空
            val json = d?.json
            if (json != null) results[key] = json else missing.add(doc.file + (d?.error?.let { "($it)" } ?: ""))
        }
        put("engine_v5", ArchiveDoc.ENGINE_V5)
        put("cycle_pool", ArchiveDoc.CYCLE_POOL)
        put("rps", ArchiveDoc.RPS_ROTATION)
        put("strategy", ArchiveDoc.STRATEGY_CARD)
        put("phase", ArchiveDoc.MARKET_PHASE)
        put("global", ArchiveDoc.GLOBAL_MARKETS)
        put("aipick", ArchiveDoc.AI_PICK_V4)
        put("decision", ArchiveDoc.DECISION)

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
