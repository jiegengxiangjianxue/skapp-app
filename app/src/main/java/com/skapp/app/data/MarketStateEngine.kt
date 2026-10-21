package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 市场状态引擎 — 移植自站点 engine_fusion.py
 * 
 * 五个独立引擎 → 单一状态分 E(-100..+100) + 一致性度量:
 *   E = 0.30*market_phase + 0.25*cycle_v2 + 0.20*global_momentum + 0.15*win_loop + 0.10*kelly_signal
 * 一致性 = 五引擎同号比例 (高一致→可信, 低一致→观望)
 * 
 * 数据源映射:
 *   market_phase ← RemoteSources.apiMarket() 的 market.gainers/losers 计数
 *   cycle_v2     ← SiteBoardClient.pools() 周期池 meta.index 字段
 *   global       ← MarketDataClient.globalIndices() 涨跌统计
 *   win_loop/kelly ← 暂缺 (app 无 picks.duckdb)
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

    private fun fetchMarket(): JSONObject? {
        val url = RemoteSources.apiMarket()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                runCatching { JSONObject(it.readText()) }.getOrNull()
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchCyclePool(): JSONObject? {
        // 直接从 GitHub API 拉周期池 (cycles/cycle_pool_v2.json)
        val url = "${RemoteSources.base("jiegengxiangjianxue", "skapp-data")}/cycle_pool_v2.json"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
                setRequestProperty("Accept", "application/vnd.github.v3.raw")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                // 如果文件不存在或不可访问，返回 null（让 E=0，中立）
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                runCatching { JSONObject(it.readText()) }.getOrNull()
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
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
        val up = indices.count { (it["pctChg"] as? Double)?.let { it > 0 } == true }
        val total = indices.size
        if (total == 0) return 0.0
        // 全球涨跌比例映射到 -50..+50
        return (up.toDouble() / total * 100 - 50) * 2.0
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
     * 计算市场状态: E = phase*0.30 + cycle*0.25 + global*0.20 + winloop*0.15 + kelly*0.10
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
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .format(System.currentTimeMillis())
        
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
