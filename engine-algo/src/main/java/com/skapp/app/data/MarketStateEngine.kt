package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 市场状态引擎 — 移植自站点 engine_fusion.py
 * 
 * 五个独立引擎 → 单一状态分 E(-100..+100) + 一致性度量:
 *   E = 0.30*market_phase + 0.25*cycle_v2 + 0.20*global_momentum + 0.15*win_loop + 0.10*kelly_signal
 * 一致性 = 五引擎同号比例 (高一致→可信, 低一致→观望)
 * 
 * 两条路径：
 *
 * `computeLive()`（默认）——全部走盘中实时数据：
 *   market_phase ← LivePulse（东财 clist 实时盘口算涨跌家数/涨跌停/中位涨幅）
 *   global       ← MarketDataClient.globalIndices()（实时外围指数）
 *   cycle_v2     ← 本地实时不可得（要板块历史周期），如实列进 missingInputs
 *   win_loop/kelly ← 暂缺（app 无 picks.duckdb）
 *
 * `compute()`（遗留，云端快照）——读云端 api/market + cycle_pool_v2，收盘后才有新值。
 * 仅作历史/对照用，分析链路默认走 `computeLive()`。
 */
data class MarketState(
    val ts: String,
    val E: Double,                  // 综合分数 (-100..100)
    val state: String,              // "进攻" / "防守" / "中性观望" / "进攻(分歧)" / "防守(分歧)"
    val agreement: Double,          // 5个部件一致性 0..1
    val parts: Map<String, Double>, // {market_phase, cycle_v2, global, win_loop, kelly}
    val weights: Map<String, Double>,
    val missingInputs: List<String>,
)

object MarketStateEngine {

    private const val TIMEOUT = 15000

    private fun fetchMarket(): JSONObject? =
        Http.getText(RemoteSources.apiMarket(), timeoutMs = TIMEOUT, ua = "Mozilla/5.0 (Android) skapp")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fetchCyclePool(): JSONObject? {
        // 直接从 GitHub API 拉周期池 (cycles/cycle_pool_v2.json)；取不到就让 E=0（中立）
        val url = "${RemoteSources.base("jiegengxiangjianxue", "skapp-data")}/cycle_pool_v2.json"
        return Http.getText(
            url,
            timeoutMs = TIMEOUT,
            ua = "Mozilla/5.0 (Android) skapp",
            accept = "application/vnd.github.v3.raw",
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /**
     * 市场阶段得分: 基于涨跌统计计算
     * 参考 engine_fusion.py phase_score，但用涨跌股数量替代文件读取
     */
    private fun phaseScore(market: JSONObject?, missing: MutableList<String>): Double {
        if (market == null) {
            missing.add("market_phase.json (API不可达)")
            return 0.0
        }
        // 从 market JSON 中提取涨跌统计
        val gainers = market.optJSONObject("market")?.optInt("gainers", 0)
            ?: market.optInt("gainers", 0)
        val losers = market.optJSONObject("market")?.optInt("losers", 0)
            ?: market.optInt("losers", 0)
        val limit_up = market.optInt("limit_up", 0)
        
        val total = gainers + losers
        if (total == 0) return 0.0
        
        // 涨跌比映射到得分: -80..+80
        // 参考站点: phase "ice":-80, "ebb":-50, "repair":-20, "ignite":40, "rally":70, "climax":20
        val riseRatio = (gainers - losers).toDouble() / total
        return when {
            riseRatio < -0.6 -> -80.0
            riseRatio < -0.3 -> -50.0
            riseRatio < 0.0  -> -20.0
            riseRatio < 0.3  -> 40.0
            riseRatio < 0.6  -> 70.0
            else -> 20.0
        }
    }

    /**
     * 周期得分: 从周期池 meta.index 字段获取
     * 参考 engine_fusion.py cycle_score: (p_up - 50) * 2 + m * 20
     */
    private fun cycleScore(cycle: JSONObject?, missing: MutableList<String>): Double {
        if (cycle == null) {
            missing.add("cycle_pool_v2.json (API不可达)")
            return 0.0
        }
        val meta = cycle.optJSONObject("meta")
        if (meta == null) {
            missing.add("cycle_pool_v2.json.meta 缺失")
            return 0.0
        }
        val index = meta.optString("index", "").lowercase()
        return when (index) {
            "ice" -> -80.0
            "ebb" -> -50.0
            "repair" -> -20.0
            "ignite" -> 40.0
            "rally" -> 70.0
            "climax" -> 20.0
            else -> 0.0
        }
    }

    /**
     * 全球联动得分: 从全球指数涨跌统计计算
     */
    private fun globalScore(indices: List<Map<String, Any?>>, missing: MutableList<String>): Double {
        if (indices.isEmpty()) {
            missing.add("global_indices.json (API不可达)")
            return 0.0
        }
        // 按 Number 取再 toDouble：接口里可能是 Int/Float，`as? Double` 会静默变 false，
        // 于是一个「外围普涨」的晚上被算成 -100（全跌），把 E 直接拽到防守区。
        val up = indices.count { (it["pctChg"] as? Number)?.toDouble()?.let { p -> p > 0 } == true }
        val total = indices.size
        if (total == 0) return 0.0
        // 全球涨跌比例映射到 -50..+50
        return (up.toDouble() / total * 100 - 50) * 2.0
    }

    /**
     * 板块周期分（cycle_v2）：成交额加权的「上涨板块占比」+「加权涨幅」。
     *
     * - 广度项：(加权上涨占比 − 0.5) × 80 → ±40
     * - 涨幅项：加权板块涨幅 × 6，夹 ±30
     *
     * 按成交额加权而不是板块等权：钱在哪，才是当下市场的真实主张。
     * 板块数据拿不到就返回 null（该输入本轮不参与权重归一），不填 0 假装中性。
     */
    internal fun cycleScore(sectors: LocalSectors.Result?, missing: MutableList<String>): Double? {
        if (sectors == null || sectors.rows.isEmpty()) {
            missing.add("cycle_v2 (板块强弱聚合不可得：需全市场快照 + 成分映射表)")
            return null
        }
        val totalHeat = sectors.rows.sumOf { it.heat }
        if (totalHeat <= 0.0) {
            missing.add("cycle_v2 (板块成交额为空，无法加权)")
            return null
        }
        var wUp = 0.0
        var wChg = 0.0
        for (r in sectors.rows) {
            val upRatio = if (r.n > 0) r.up.toDouble() / r.n else 0.5
            wUp += r.heat * upRatio
            wChg += r.heat * (r.chg1d ?: 0.0)
        }
        wUp /= totalHeat
        wChg /= totalHeat
        return ((wUp - 0.5) * 80.0 + (wChg * 6.0).coerceIn(-30.0, 30.0)).coerceIn(-100.0, 100.0)
    }

    /**
     * 胜负循环得分: app 无 picks.duckdb，返回 0
     */
    private fun winLoopScore(missing: MutableList<String>): Double {
        missing.add("picks.duckdb (win_loop)")
        return 0.0
    }

    /**
     * 凯利公式得分: app 无 picks.duckdb，返回 0
     */
    private fun kellyScore(missing: MutableList<String>): Double {
        missing.add("picks.duckdb (kelly)")
        return 0.0
    }

    /**
     * 实时市场状态（默认路径）：只用盘中真实数据，缺的部件如实列出。
     *
     * 与 [compute] 的差别：
     *   1. market_phase 由 [LivePulse] 现算，不再读云端 api/market；
     *   2. cycle_v2 本地实时拿不到，标 missing 而不是塞 0 当"真实中性"；
     *   3. 权重按**可用部件**归一到 1，避免缺件时 E 被系统性稀释（旧实现缺 cycle 时
     *      等于把 0.25 的权重永久抹掉，E 天然偏保守且无法解释）。
     */
    suspend fun computeLive(
        pulseIn: LivePulse? = null,
        sectorsIn: LocalSectors.Result? = null,
    ): MarketState = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        // 心跳已经算过脉搏时直接复用，避免同一轮里再拉一次全市场
        val pulse = pulseIn ?: LivePulse.of(LiveMarket.snapshot())
        if (pulse.total == 0) missing.add("live_market (全市场实时行情不可达)")

        val indices = MarketDataClient.globalIndices()
        val globalPart = globalScore(indices, missing)

        // cycle_v2：以前直接写 0.0 并声明「不可得」，等于把 25% 权重凭空扔掉、
        // 还让界面显示一个「板块 0 分」的假数字。板块横截面端上算得出来（成交额加权
        // 上涨占比 + 加权涨幅），接进来即可；只有历史周期序列才是真缺。
        val sectors = sectorsIn ?: runCatching { LocalSectors.compute() }.getOrNull()
        val cyclePart = cycleScore(sectors, missing)
        missing.add("cycle_v2.history (板块历史周期序列，端上无多日快照)")
        missing.add("picks.duckdb (win_loop)")
        missing.add("picks.duckdb (kelly)")

        val parts = mapOf(
            "market_phase" to pulse.phaseScore,
            "global" to globalPart,
            "cycle_v2" to (cyclePart ?: 0.0),
            "win_loop" to 0.0,
            "kelly" to 0.0,
        )
        val baseWeights = mapOf(
            "market_phase" to 0.30,
            "cycle_v2" to 0.25,
            "global" to 0.20,
            "win_loop" to 0.15,
            "kelly" to 0.10,
        )
        val available = buildList {
            add("market_phase")
            add("global")
            if (cyclePart != null) add("cycle_v2")
        }
        val totalWeight = available.sumOf { baseWeights[it] ?: 0.0 }
        val weights = baseWeights.mapValues { (k, v) -> if (k in available) v / totalWeight else 0.0 }

        val E = parts.entries.sumOf { (k, v) -> v * (weights[k] ?: 0.0) }.let { it * 100 }.toInt() / 100.0

        val signs = available.map { k -> parts[k] ?: 0.0 }.map { v ->
            when {
                v > 5 -> 1
                v < -5 -> -1
                else -> 0
            }
        }
        // 中性部件**必须计入分母**：旧写法先把 0 剔掉再算比例，
        // 于是「1 个看多 + 1 个走平」也能拿到 100% 一致，直接进「进攻」。
        // 这和 LocalEngine.resonanceOf（分母含中性引擎）也是同一口径。
        val agree = if (signs.isEmpty()) 0.0
        else maxOf(signs.count { it == 1 }, signs.count { it == -1 }).toDouble() / signs.size

        val state = stateOf(E, agree)
        MarketState(
            ts = CstTime.iso(),
            E = E,
            state = state,
            agreement = agree,
            parts = parts,
            weights = weights,
            missingInputs = missing.distinct(),
        )
    }

    private fun stateOf(E: Double, agree: Double): String = when {
        E > 30 && agree >= 0.6 -> "进攻"
        E < -30 && agree >= 0.6 -> "防守"
        E > 30 -> "进攻(分歧)"
        E < -30 -> "防守(分歧)"
        else -> "中性观望"
    }

    /**
     * 遗留路径（云端快照）：E = phase*0.30 + cycle*0.25 + global*0.20 + winloop*0.15 + kelly*0.10
     * 读的是云端收盘产物，盘中打开可能拿到昨天的结论 —— 分析链路请用 [computeLive]。
     */
    suspend fun compute(): MarketState = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        
        val market = fetchMarket()
        val cycle = fetchCyclePool()
        val indices = MarketDataClient.globalIndices()
        
        val phase = phaseScore(market, missing)
        val cyc = cycleScore(cycle, missing)
        val g = globalScore(indices, missing)
        val w = winLoopScore(missing)
        val k = kellyScore(missing)
        
        val parts = mapOf(
            "market_phase" to phase,
            "cycle_v2" to cyc,
            "global" to g,
            "win_loop" to w,
            "kelly" to k
        )
        val weights = mapOf(
            "market_phase" to 0.30,
            "cycle_v2" to 0.25,
            "global" to 0.20,
            "win_loop" to 0.15,
            "kelly" to 0.10
        )
        
        // E = Σ(score * weight)
        val E = parts.map { (k, v) -> v * (weights[k] ?: 0.0) }.sum().let { it * 100 }.toInt() / 100.0
        
        // 一致性: 五引擎同号比例
        val signs = parts.values.map { v ->
            when {
                v > 5 -> 1
                v < -5 -> -1
                else -> 0
            }
        }
        val nz = signs.filter { it != 0 }
        val agree = if (nz.isNotEmpty()) {
            maxOf(nz.count { it == 1 }, nz.count { it == -1 }).toDouble() / nz.size
        } else 0.0
        
        // 状态机
        val state = when {
            E > 30 && agree >= 0.6 -> "进攻"
            E < -30 && agree >= 0.6 -> "防守"
            E > 30 -> "进攻(分歧)"
            E < -30 -> "防守(分歧)"
            else -> "中性观望"
        }
        
        // 时间戳
        val ts = CstTime.iso()
        
        MarketState(
            ts = ts,
            E = E,
            state = state,
            agreement = agree,
            parts = parts,
            weights = weights,
            missingInputs = missing.distinct(),
        )
    }
}
