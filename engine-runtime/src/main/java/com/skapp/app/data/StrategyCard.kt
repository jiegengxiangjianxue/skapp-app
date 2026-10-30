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

    /** 单只股票日 K 拉取超时 */
    private const val PER_STOCK_TIMEOUT_MS = 20_000L

    /** 预筛后最多深算多少只（每只 1 次日 K 请求） */
    private const val MAX_SCAN = 60

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
    /**
     * @param globalIndices 已拉到的外围指数；由上层传入可省一次请求，也保证与本轮
     *        market_phase 用的是**同一时点**的外围数据。传 null 才自己拉。
     */
    suspend fun scan(
        limit: Int = 12,
        globalIndices: List<Map<String, Any?>>? = null,
        maxScan: Int = MAX_SCAN,
    ): Result =
        withContext(Dispatchers.IO) {
        // 1. 获取全球分数（闸门）
        val indices = globalIndices ?: MarketDataClient.globalIndices()
        val globalScore = calculateGlobalScore(indices)
        val tradeOk = globalScore > -15

        // 2. 全市场实时行情（与选股/相位共用同一份快照，不重复拉）
        val snap = LiveMarket.snapshot()
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
            val chg = m.pctChg ?: return@filter false
            val vr = m.volRatio ?: return@filter false
            val name = m.name.orEmpty()
            
            // 排除 ST
            val isST = NameRules.isSTOrDelisting(name)
            if (isST) return@filter false
            
            // 涨幅 0-5%
            if (chg !in 0.0..5.0) return@filter false
            
            // 量比 0.8-2.0
            if (vr !in 0.8..2.0) return@filter false
            
            true
        }

        // 4. 预筛结果必须先截断：0-5% 涨幅 + 0.8-2.0 量比的票常有几百只，
        //    逐只拉 250 天日 K 就是几百次请求（策略卡还被引擎池每 45 秒调一次）。
        //    按「低位程度 + 温和放量」秩和取前 maxScan 只，方向和策略本身一致。
        val ranked = preFiltered.map { m ->
            val chg = m.pctChg ?: 0.0
            val vr = m.volRatio ?: 0.0
            // 涨得越少越低位（权重 0.5）；量比在 0.8-2.0 内越大越好（权重 0.5）
            val lowPos = ((5.0 - chg) / 5.0).coerceIn(0.0, 1.0)
            m to (0.5 * lowPos + 0.5 * (vr / 2.0).coerceIn(0.0, 1.0))
        }.sortedByDescending { it.second }.take(maxScan).map { it.first }

        // 5. 逐只计算 pos240 和非妖股判断（并发，限流 5 只一批，每只带超时）
        val cands = mutableListOf<Candidate>()
        ranked.chunked(5).forEach { batch ->
            val results = kotlinx.coroutines.coroutineScope {
                batch.map { m ->
                    async(Dispatchers.IO) {
                        val code = m.code
                        val name = m.name
                        val close = m.price ?: return@async null
                        val retPct = m.pctChg ?: return@async null
                        val volRatio = m.volRatio ?: return@async null
                        
                        // 日 K 走 DataCache：同一只票在选股/详情/策略卡之间共享，
                        // 盘中有 60 秒新鲜度，不会每次扫描都重打接口
                        val bars = kotlinx.coroutines.withTimeoutOrNull(PER_STOCK_TIMEOUT_MS) {
                            DataCache.kline(code, "qfq", 250)
                        } ?: return@async null
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
    internal fun calculateGlobalScore(indices: List<Map<String, Any?>>): Double {
        if (indices.isEmpty()) return 0.0
        // 必须按 Number 取再 toDouble：行情 map 里的 pctChg 可能是 Int/Float/BigDecimal，
        // 直接 `as? Double` 会静默变成 false，把所有上涨的指数算成下跌，
        // 于是「全球 > -15 才进」这道闸门长期关不了一半以上盘面（真出现过）。
        val up = indices.count { (it["pctChg"] as? Number)?.toDouble()?.let { p -> p > 0 } == true }
        val total = indices.size.toDouble()
        return (up / total * 100 - 50) * 2.0
    }

    /**
     * 非妖股判断：10 日内距高点<5%
     * 如果当前价格在 10 日最高点附近（相差<5%），视为妖股风险
     */
    internal fun checkNotYaoGu(bars: List<KBar>): Boolean {
        if (bars.size < 12) return true     // 数据不足，保守通过
        val last = bars.last()
        val base = bars[bars.size - 11].close
        if (base <= 0.0) return true
        // ① 近 10 日累计涨幅 > 30% 就是妖股走势，不碰
        val ret10 = (last.close - base) / base
        if (ret10 > 0.30) return false
        // ② 当前价贴着 10 日高点（上方不到 2% 空间）说明在追高，不碰
        val recent = bars.dropLast(1).takeLast(10)
        val high10 = recent.maxOf { it.high }
        if (high10 <= 0.0) return true
        val roomPct = (high10 - last.close) / high10
        return roomPct > 0.02
    }
}
