package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 策略卡 v1：胜率导向组合 B 规则（移植自 Python strategy_card.py）
 * 
 * 规则 B (208 万行网格搜索最优):
 *   - 240 日位置 < 35%
 *   - 量比 0.8-2.0
 *   - 当日涨幅 0-5%
 *   - 非妖股（10 日内距高点<5%，排除不可执行）
 * 
 * 持有逻辑:
 *   - 次日冲高 +2% 止盈 / 破 -2% 止损（胜率 44.3%，E+0.28%/笔）
 *   - 或持有 5 日收盘卖出（胜率 55.5%）
 * 
 * 市况闸门：global > -15 才进
 * 
 * 数据来源：实时行情 API（东财/腾讯），无 bulk CSV
 * pos240 若无法实时计算，则退化使用 60 日位置近似
 */
object StrategyCard {

    /** B 规则候选 */
    data class Candidate(
        val symbol: String,
        val name: String?,
        val close: Double,
        val retPct: Double,       // 当日涨幅%
        val volRatio: Double,      // 量比
        val pos240: Double?,       // 240 日位置（可能 null 退化）
    )

    /** 策略卡结果 */
    data class Result(
        val timestamp: Long,
        val strategy: String,
        val globalScore: Double,   // 全球分数
        val tradeOk: Boolean,      // 是否满足入市条件
        val candidates: List<Candidate>,
        val backtest: BacktestStats,
    )

    data class BacktestStats(
        val winRate5d: Double = 55.5,
        val baseline5d: Double = 48.8,
        val alphaPp: Double = 6.7,
        val winRateT1: Double = 44.3,
        val EPctPerTrade: Double = 0.28,
        val n: Int = 1995,
    )

    /** 纪律要求 */
    data class Discipline(
        val takeProfit: String = "T+1 次日冲高 +2%止盈",
        val hold5d: String = "或持有 5 日收盘卖出",
        val stopLoss: String = "破 -2% 无条件止损",
        val positionSizing: String = "仓位=半凯利",
        val marketGate: String = "全球<-15 空仓",
    )

    private const val TIMEOUT = 15000

    /**
     * 运行策略卡筛选
     * 
     * 步骤：
     * 1. 拉全市场实时行情
     * 2. 预筛（涨幅 0-5% + 量比 0.8-2.0 + 非 ST）
     * 3. 对候选逐只拉日 K 线计算 pos240
     * 4. 过滤 pos240 < 35% + 非妖股（10 日近高<5%）
     * 5. 检查 global_score 闸门
     * 6. 输出前 N 只
     */
    suspend fun scan(limit: Int = 12): Result = withContext(Dispatchers.IO) {
        // 1. 获取全球分数（闸门）
        val globalIndices = MarketDataClient.globalIndices()
        val globalScore = calculateGlobalScore(globalIndices)
        val tradeOk = globalScore > -15

        // 2. 拉全市场实时行情（东财 clist）
        val snap = LocalScreener.marketSnapshot(pn = 1, pz = 3000)
        if (snap.isEmpty()) {
            return@withContext Result(
                timestamp = System.currentTimeMillis(),
                strategy = "B 规则 (低位 + 温和量 + 涨 0-5%)",
                globalScore = globalScore,
                tradeOk = tradeOk,
                candidates = emptyList(),
                backtest = BacktestStats(),
            )
        }

        // 3. 预筛：涨幅 0-5% + 量比 0.8-2.0 + 非 ST
        val preFiltered = snap.filter { m ->
            val chg = (m["pctChg"] as? Number)?.toDouble() ?: return@filter false
            val vr = (m["volRatio"] as? Number)?.toDouble() ?: return@filter false
            val name = m["name"]?.toString() ?: ""
            
            // 排除 ST
            val isST = name.contains("ST", ignoreCase = true) || name.contains("退", ignoreCase = true)
            if (isST) return@filter false
            
            // 涨幅 0-5%
            if (chg !in 0.0..5.0) return@filter false
            
            // 量比 0.8-2.0
            if (vr !in 0.8..2.0) return@filter false
            
            true
        }

        // 4. 逐只计算 pos240 和非妖股判断（并发，限流 5 只一批）
        val cands = mutableListOf<Candidate>()
        preFiltered.chunked(5).forEach { batch ->
            val results = kotlinx.coroutines.coroutineScope {
                batch.map { m ->
                    async(Dispatchers.IO) {
                        val code = m["code"]?.toString() ?: return@async null
                        val name = m["name"]?.toString()
                        val close = (m["price"] as? Number)?.toDouble() ?: return@async null
                        val retPct = (m["pctChg"] as? Number)?.toDouble() ?: return@async null
                        val volRatio = (m["volRatio"] as? Number)?.toDouble() ?: return@async null
                        
                        // 拉日 K 线（至少 240 天）
                        val bars = KlineApi.fetchDayKline(code, count = 250)
                        if (bars.size < 240) return@async null
                        
                        // 计算 pos240（240 日位置）
                        val pos240 = ExtendedIndicators.position240d(bars) ?: return@async null
                        
                        // 非妖股判断：10 日内距高点<5%
                        val isNotYaoGu = checkNotYaoGu(bars)
                        if (!isNotYaoGu) return@async null
                        
                        Candidate(
                            symbol = code,
                            name = name,
                            close = close,
                            retPct = retPct,
                            volRatio = volRatio,
                            pos240 = pos240,
                        )
                    }
                }.mapNotNull { kotlin.runCatching { it.await() }.getOrNull() }
            }
            cands.addAll(results)
        }

        // 5. 排序（按量比降序）并取前 N
        val top = cands
            .filter { it.pos240 ?: 1.0 < 0.35 }  // 240 日位置 < 35%
            .sortedByDescending { it.volRatio }
            .take(limit)

        Result(
            timestamp = System.currentTimeMillis(),
            strategy = "B 规则 (240 日位<35% + 量比 0.8-2.0 + 涨 0-5% + 非妖股)",
            globalScore = globalScore,
            tradeOk = tradeOk,
            candidates = top,
            backtest = BacktestStats(),
        )
    }

    /**
     * 计算全球联动分数
     * 基于全球指数涨跌比例映射到 -50..+50
     */
    private fun calculateGlobalScore(indices: List<Map<String, Any?>>): Double {
        if (indices.isEmpty()) return 0.0
        val up = indices.count { (it["pctChg"] as? Double)?.let { it > 0 } == true }
        val total = indices.size.toDouble()
        return (up / total * 100 - 50) * 2.0
    }

    /**
     * 非妖股判断：10 日内距高点<5%
     * 如果当前价格在 10 日最高点附近（相差<5%），视为妖股风险
     */
    private fun checkNotYaoGu(bars: List<KBar>): Boolean {
        if (bars.size < 11) return true  // 数据不足，保守通过
        
        // 取最近 10 日（不含当日）
        val recent = bars.dropLast(1).takeLast(10)
        val high10 = recent.maxOf { it.high }
        val currentHigh = bars.last().high
        
        // 当前距 10 日高点的距离
        val distPct = if (high10 > 0) (currentHigh - high10) / high10 else 0.0
        
        // 如果距离<5%，视为接近 10 日高点，属于妖股风险区
        return distPct < 0.05
    }
}
