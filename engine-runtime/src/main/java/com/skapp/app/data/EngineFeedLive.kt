package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当日引擎池的**本地装配**（替代云端 engine_v5 / market_phase / global / decision 归档）。
 *
 * ## 为什么要有这一层
 *
 * [EngineFeed] 解析的是云端 CI 收盘后算好的产物，盘中打开看到的是几小时前甚至昨天的结论。
 * 用户要求「本地实时获取实时分析」，所以这里用设备上已经有的东西拼出同构结果：
 *
 * ```
 * LiveMarket 全市场盘口 ──┬─→ LivePulse         → market_phase / decision.positionAdvice
 *                         └─→ LocalScreener     → engine_v5.top / decision.cards
 * MarketDataClient.globalIndices（实时外围）      → global_markets
 * ```
 *
 * 输出与云端 payload **同构**（同一批 data class），界面无需改动即可切换；
 * 来源用 `EngineFeeds.source` 区分 `live` / `cloud`，界面能如实标出来。
 *
 * ## 拿不到的不编
 *
 * `cycle_pool`（板块周期池）、`rps_rotation`（全市场 250 日相对强度）、`strategy_card`
 * （需全市场 240 日重算）、`ai_pick`（LLM 产物）、`market_phase` 的连板高度/晋级率/封板率
 * —— 这些实时接口给不了，一律进 `missing` 并写明原因，**不用 0 或旧值冒充**。
 */
object EngineFeedLive {

    /** 引擎池缓存：盘中 45s（一次装配要拉几十只日K，不能每次进页面都重算）。 */
    private const val CACHE_TTL_MS = 45_000L
    private var cachedAt = 0L
    private var cached: EngineFeed.EngineFeeds? = null

    suspend fun load(
        force: Boolean = false,
        criteria: LocalScreener.Criteria = LocalScreener.Criteria(maxCandidates = 30),
        onProgress: (String) -> Unit = {},
    ): EngineFeed.EngineFeeds = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) cached?.let { if (now - cachedAt < CACHE_TTL_MS) return@withContext it }

        onProgress("实时拉取全市场行情…")
        val snap = LiveMarket.snapshot(force)
        if (snap.isEmpty()) {
            // 宁缺毋假：拉不到就明确说拉不到，不拿云端旧结论顶替
            return@withContext emptyLive("全市场实时行情不可达（东财 clist）")
        }
        val pulse = LivePulse.of(snap)

        onProgress("本地 12 引擎重算候选池…")
        val screened = LocalScreener.screen(criteria, onProgress, mode = "all")

        val indices = runCatching { MarketDataClient.globalIndices() }.getOrDefault(emptyList())
        val feeds = assemble(pulse, screened, indices)

        cached = feeds
        cachedAt = System.currentTimeMillis()
        feeds
    }

    /** 清缓存（用户手动刷新 / 心跳深算落库后）。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    /**
     * 纯装配：把「实时快照 + 本地筛股结果 + 外围指数」拼成与云端同构的 EngineFeeds。
     *
     * 抽成纯函数是为了能测——这段逻辑一旦把方向搞反（比如把跌停算成强势、把不可得字段
     * 填成 0），界面上是看不出来的。
     */
    fun assemble(
        pulse: LivePulse,
        screened: List<LocalScreener.Screened>,
        globalIndices: List<Map<String, Any?>>,
        ts: Long = System.currentTimeMillis(),
        limit: Int = 30,
    ): EngineFeed.EngineFeeds {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

        val scored = screened.filter { (it.centralScore ?: 0.0) > 0.0 }
            .sortedByDescending { it.centralScore ?: 0.0 }
            .take(limit)

        val top = scored.mapIndexed { i, s ->
            val eng = s.engine
            EngineFeed.EngineV5Top(
                rank = i + 1,
                code = s.code,
                date = date,
                price = s.price,
                score = s.centralScore ?: 0.0,
                resonance = s.resonanceRatio ?: 0.0,
                bull = eng?.bullCount ?: 0,
                bear = eng?.bearCount ?: 0,
                neutral = eng?.neutralCount ?: 0,
                votes = eng?.engines.orEmpty().map { e ->
                    EngineFeed.Vote(
                        indicator = e.engine,
                        vote = voteOf(e.signal),
                        strength = ((e.score ?: 50.0) / 100.0).coerceIn(0.0, 1.0),
                        why = e.reasons.firstOrNull() ?: e.signal.orEmpty(),
                    )
                },
            )
        }

        val phase = EngineFeed.MarketPhase(
            ts = iso,
            daily = listOf(
                EngineFeed.MarketPhaseDaily(
                    date = date,
                    firstBoard = -1,      // 需历史涨停序列，实时不可得
                    ge2 = -1,             // 同上
                    height = -1,          // 连板高度不可得
                    promo = -1.0,         // 晋级率不可得
                    seal = -1.0,          // 封板率不可得
                ),
            ),
            labels = mapOf(
                "口径" to "本地实时（东财 clist 全市场盘口现算）",
                "涨跌家数" to "${pulse.up}涨 / ${pulse.down}跌 / ${pulse.flat}平",
                "涨停家数" to "${pulse.limitUp}",
                "跌停家数" to "${pulse.limitDown}",
                "中位涨幅" to (pulse.medianPct?.let { String.format(Locale.CHINA, "%.2f%%", it) } ?: "不可得"),
                "宽度" to (pulse.breadth?.let { String.format(Locale.CHINA, "%.0f%%", it * 100) } ?: "不可得"),
                "相位" to "${pulse.label}（${String.format(Locale.CHINA, "%.0f", pulse.phaseScore)}）",
                "不可得" to "连板高度/晋级率/封板率需历史涨停序列",
            ),
        )

        val nOk = globalIndices.count { (it["pctChg"] as? Number) != null }
        val up = globalIndices.count { (it["pctChg"] as? Number)?.toDouble()?.let { p -> p > 0 } == true }
        val sentiment = if (globalIndices.isEmpty()) null
        else (up.toDouble() / globalIndices.size * 100 - 50) * 2.0
        val global = EngineFeed.GlobalMarkets(
            ts = iso,
            nIndices = globalIndices.size,
            nOk = nOk,
            sentiment = sentiment,
        )

        val decision = EngineFeed.Decision(
            ts = iso,
            marketRegime = regimeOf(pulse.label),
            positionAdvice = EngineFeed.PositionAdvice(
                level = ((pulse.phaseScore + 100) / 2 / 100).coerceIn(0.0, 1.0),
                label = pulse.label,
                cap = (0.8 * ((pulse.phaseScore + 100) / 2 / 100)).coerceIn(0.0, 0.8),
                reason = "实时相位 ${pulse.label}：${pulse.up} 涨 / ${pulse.down} 跌，" +
                    "涨停 ${pulse.limitUp} / 跌停 ${pulse.limitDown}",
            ),
            cards = scored.take(10).map { s ->
                EngineFeed.DecisionCard(
                    code = s.code,
                    name = s.name ?: s.code,
                    price = s.price,
                    chgPct = s.pctChg,
                    composite = s.centralScore ?: 0.0,
                    dims = s.engine?.engines.orEmpty()
                        .mapNotNull { e -> e.score?.let { e.engine to it } }
                        .toMap(),
                    action = actionOf(s.centralScore ?: 0.0, s.buyable == true),
                    confidence = (s.resonanceRatio ?: 0.0).coerceIn(0.0, 1.0),
                )
            },
            avoid = screened.filter { it.buyable != true }
                .map { it.code }
                .distinct()
                .take(10),
        )

        return EngineFeed.EngineFeeds(
            ts = ts,
            engineV5 = EngineFeed.EngineV5Payload(ts = iso, top = top, staleTop = emptyList()),
            cyclePool = null,
            rpsRotation = null,
            strategyCard = null,
            marketPhase = phase,
            globalMarkets = global,
            aiPick = null,
            decision = decision,
            missing = missingOf(),
            source = "live",
        )
    }

    private fun missingOf(): List<String> = listOf(
        "cycle_pool (板块周期池需历史序列，本地实时不可得)",
        "rps_rotation (全市场 250 日相对强度需 5000 只日K)",
        "strategy_card (需全市场 240 日重算)",
        "ai_pick (LLM 产物，非实时可算)",
        "market_phase.height/promo/seal (需历史涨停序列)",
    )

    private fun emptyLive(reason: String): EngineFeed.EngineFeeds = EngineFeed.EngineFeeds(
        ts = System.currentTimeMillis(),
        engineV5 = null,
        cyclePool = null,
        rpsRotation = null,
        strategyCard = null,
        marketPhase = null,
        globalMarkets = null,
        aiPick = null,
        decision = null,
        missing = listOf(reason),
        source = "live",
    )

    private fun voteOf(signal: String?): Int = when (signal?.lowercase(Locale.CHINA)) {
        "bullish", "bull" -> 1
        "bearish", "bear" -> -1
        else -> 0
    }

    private fun regimeOf(label: String): String = when (label) {
        "强势", "偏强" -> "offense"
        "偏弱", "弱势" -> "defense"
        else -> "neutral"
    }

    private fun actionOf(score: Double, buyable: Boolean): String = when {
        !buyable -> "avoid"
        score >= 65 -> "buy"
        score >= 55 -> "watch"
        else -> "hold"
    }
}
