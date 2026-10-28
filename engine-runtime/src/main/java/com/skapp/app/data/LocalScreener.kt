package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 本地实时选股引擎 —— 在设备上完成「实时收集数据 → 算法分析 → 出结果」。
 *
 * 用户核心诉求：推股/决策不能再用云端旧快照，要在手机上实时算。
 *
 * 数据链（全部实时，无云端快照）：
 *   1) 东财 clist 批量拉全市场实时行情（价格/涨跌幅/量比/市值/PE，一次 5000 只）
 *   2) 预筛（放量 + 涨幅区间 + 非 ST）→ 候选池
 *   3) 对候选池前 N 只逐只拉日 K（腾讯→东财），本地 12 引擎 + 29 因子
 *   4) 按 composite 排序输出
 *
 * 红线：只用真实拉到的数据；拉不到的标 insufficient，不编造。
 */
object LocalScreener {

    /** 主板代码前缀（推荐输出红线：600/601/603/605/000/001/002，排除 300/301/688/北交所） */
    private val MAIN_BOARD_PREFIXES = setOf("600", "601", "603", "605", "000", "001", "002")

    /** 筛选条件 */
    data class Criteria(
        val minChgPct: Double = 1.0,
        val maxChgPct: Double = 9.6,   // 推股不追涨停：主板涨停附近(≥9.6%)不可买入
        val minVolRatio: Double = 1.2,
        val minScore: Double = 55.0,
        val excludeST: Boolean = true,
        val maxCandidates: Int = 40,   // 逐只拉 K 线的上限（控制耗时/流量）
        val klineCount: Int = 120,
    )

    /** 一只股票的实时分析结果 */
    data class Screened(
        val code: String,
        val name: String?,
        val price: Double?,
        val pctChg: Double?,
        val volRatio: Double?,
        val turnover: Double?,
        val pe: Double?,
        val mv: Double?,
        val engine: LocalEngineResult?,
        val centralScore: Double? = engine?.centralScore ?: engine?.composite,
        val resonanceRatio: Double? = engine?.resonanceRatio,
        val centralSummary: String? = engine?.summary,
        val detailStatus: String,     // ok | insufficient
        val reason: String? = null,
        val buyable: Boolean? = null,
        /**
         * 退出纪律（[ExitPlan]）：清仓线/止盈线/今日形态/动作。
         *
         * 放在这里是因为**候选池的日 K 本来就在手上**（同一次 deep calc），
         * 加这一项零额外请求；也让「破位的票不进推荐」成为硬规则而不是界面提示。
         */
        val exitPlan: ExitPlan.Result? = null,
        val snapshotStatus: String = "live",
    )

    /**
     * 全市场实时快照（Map 形态，兼容既有调用方）。
     *
     * 实现在 [LiveMarket]：全市场只拉一次、多处共享同一时点。
     * 新代码请直接用 [LiveMarket.snapshot] 的强类型 [LiveMarket.Row]。
     */
    suspend fun marketSnapshot(pn: Int = 1, pz: Int = 100): List<Map<String, Any?>> =
        LiveMarket.snapshot().map { r ->
            mapOf<String, Any?>(
                "code" to r.code,
                "name" to r.name,
                "price" to r.price,
                "pctChg" to r.pctChg,
                "volRatio" to r.volRatio,
                "turnover" to r.volumeLots,   // 历史字段名：当年映射的是 f5 成交量（手）
                "volume" to r.amount,         // 历史字段名：当年映射的是 f6 成交额（元）
                "amplitude" to r.amplitude,
                "turnoverRate" to r.turnoverRate,
                "pe" to r.pe,
                "mv" to r.mv,
                "mainNet" to r.mainNet,
                // 异动榜首轮的动量兜底（东财 f22 涨速）；缺了它首轮只能全标 insufficient
                "speed" to r.speed,
            )
        }

    /**
     * 实时选股：市场快照 → 预筛 → 逐只本地引擎 → 排序。
     * mode: "score" 常规（评分达标池） | "all" 全分析池（共振/评分页用） | "limitup" 涨停扫描
     * dropLastBar: 剔除日 K 的最后一根（盘中最后一根是当日未完成 bar）。
     *   盘前口径必须置 true —— 盘前推股只能看「截至上一交易日收盘」的指标，
     *   否则会把盘中实时当收盘值，等于用未来数据推过去。
     */
    /**
     * @param rotation 候选池轮换档位：0=按预评分取前列；n>0 表示在预评分排序里整段后移 n 段，
     *        用于心跳多轮之间把深算名额摊到全市场（详见 [preselect]）。
     */
    suspend fun screen(c: Criteria = Criteria(), onProgress: (String) -> Unit = {},
                       mode: String = "score", dropLastBar: Boolean = false,
                       rotation: Int = 0): List<Screened> = coroutineScope {
        onProgress("正在实时拉取全市场行情…")
        val snap = LiveMarket.snapshot()
        if (snap.isEmpty()) return@coroutineScope emptyList()
        val limitup = mode == "limitup"
        onProgress("全市场快照 ${snap.size} 只，预筛中（${if (limitup) "涨停扫描" else "涨幅/量比/非ST/主板"}）…")

        // 预筛（含主板前缀红线过滤，排除 300/301/688/北交所）
        val pool = snap.filter { row ->
            val chg = row.pctChg ?: return@filter false
            val name = row.name.orEmpty()
            val st = c.excludeST && (name.contains("ST", ignoreCase = true) ||
                name.contains("退", ignoreCase = true))
            val main = row.code.take(3) in MAIN_BOARD_PREFIXES && !st
            when {
                limitup -> main && chg >= 9.5
                else -> main && chg >= c.minChgPct && chg < c.maxChgPct &&
                    // 跨模块属性没法智能转换，先落局部值
                    (row.volRatio?.let { it >= c.minVolRatio } != false)
            }
        }
        // 预筛排序：多维度秩和，不再只用「主力净流入」一票定生死。
        //   旧做法：按 mainNet 降序取前 N —— 候选池会被「大市值 + 已大额流入」的票锁死，
        //   量比很高但主力净流出（换手/洗盘）的票永远进不了深算，推股结果偏得很有规律。
        val cands = if (limitup) pool.sortedByDescending { it.pctChg ?: 0.0 }.take(50)
        else preselect(pool, c.maxCandidates, rotation)

        onProgress("预筛命中 ${cands.size} 只，逐只拉 K 线 + 本地引擎计算…")

        // 逐只拉 K 线 + 本地引擎（并发 4，避免打满）
        val out = ArrayList<Screened>()
        cands.chunked(4).forEach { batch ->
            val results = batch.map { m ->
                async(Dispatchers.IO) {
                    // 单只超时：一只票的 K 线接口卡住不该拖垮整轮全市场扫描
                    kotlinx.coroutines.withTimeoutOrNull(PER_STOCK_TIMEOUT_MS) {
                    val code = m.code
                    val barsRaw = DataCache.kline(code, "qfq", c.klineCount)
                    // 盘前口径：最后一根通常是当日未完成 bar（盘中）或昨日收盘（盘前），
                    // 统一剔除最后一根，保证「盘前推股」口径不随打开时间漂移。
                    val bars = if (dropLastBar && barsRaw.size > 1) barsRaw.dropLast(1) else barsRaw
                    val eng = if (bars.size >= 30) DataCache.engine(code, "qfq", bars) else null
                    // 退出纪律：破位（收盘跌破 20 日最低）的票一票否决，不再进推荐
                    val exit = if (bars.size >= ExitPlan.LOOKBACK + 1) {
                        runCatching { ExitPlan.compute(bars) }.getOrNull()
                    } else null
                    Screened(
                        code = code,
                        name = m.name,
                        price = m.price,
                        pctChg = m.pctChg,
                        volRatio = m.volRatio,
                        // 对外语义保持「成交额」：界面上的「成交」列读的是 amount
                        turnover = m.amount,
                        pe = m.pe,
                        mv = m.mv,
                        engine = eng,
                        detailStatus = if (eng != null) "ok" else "insufficient",
                        reason = when {
                            eng == null -> "K线不足，无法本地计算"
                            exit?.action == ExitPlan.ACT_CLEAR -> "已破位：" + exit.reasons.first()
                            exit?.action == ExitPlan.ACT_TRIM -> "高位回吐过半：" + (exit.reasons.firstOrNull() ?: "")
                            else -> null
                        },
                        exitPlan = exit,
                        buyable = (m.price?.let { it > 0.0 } == true) &&
                            (m.pctChg ?: Double.POSITIVE_INFINITY) < c.maxChgPct &&
                            // 破位票不可买：跌穿 20 日最低的票，「评分高」只是下跌惯性
                            exit?.action != ExitPlan.ACT_CLEAR,
                        // 快照新鲜度如实标出来：LiveMarket 拉不到新数据时会退回上一份缓存，
                        // 那这时结果就不是「实时」而是「旧值」，界面必须能区分（5 分钟内算实时）
                        snapshotStatus = if (m.ts > 0 && System.currentTimeMillis() - m.ts > 5 * 60_000L) "stale" else "live",
                    )
                    }
                }
            }.mapNotNull { runCatching { it.await() }.getOrNull() }
            out.addAll(results)
            onProgress("本地引擎已分析 ${out.size}/${cands.size} 只…")
        }

        when (mode) {
            // 涨停仅观察/归因，不进入普通推荐出口。
            "limitup" -> return@coroutineScope out.filter { (it.pctChg ?: 0.0) >= 9.5 }
                .sortedByDescending { it.pctChg ?: 0.0 }
            // 共振页返回全部分析结果（含未达标），由 UI 自行排序。
            "all" -> return@coroutineScope out.sortedByDescending { it.centralScore ?: (it.pctChg ?: 0.0) }
        }
        val scored = out.filter {
            it.detailStatus == "ok" && it.centralScore != null &&
                it.centralScore >= c.minScore && it.buyable == true &&
                (it.pctChg ?: Double.POSITIVE_INFINITY) < c.maxChgPct &&
                // 普通推荐不接受涨停扫描结果，即使上游误标了可买。
                (it.pctChg ?: Double.POSITIVE_INFINITY) < 9.5
        }.sortedByDescending { it.centralScore ?: 0.0 }
        if (scored.isNotEmpty()) return@coroutineScope scored
        // 评分不足时只展示可买且有可用本地引擎的实时结果，不把 insufficient 伪装成推荐。
        onProgress("评分达标 0 只，暂无可买推荐（数据不足或过滤涨停）")
        emptyList()
    }
    /** 单只股票的 K 线 + 引擎计算超时（毫秒）。 */
    private const val PER_STOCK_TIMEOUT_MS = 20_000L

    /**
     * 候选池预选：涨幅 / 量比 / 成交额 / 主力净流入 四维**秩和**，各占权重。
     *
     * 只用秩（percentile）不用原值，是因为四个量纲没法直接比：量比 3 倍与主力净流入 3 亿
     * 谁更值得深算，只有「各自在本池里排第几」可以相加。权重上量比最高——
     * 放量是本地引擎最能确认的先行信号，涨幅已经被预筛区间框住了。
     */
    internal fun preselect(
        pool: List<LiveMarket.Row>,
        limit: Int,
        rotation: Int = 0,
    ): List<LiveMarket.Row> {
        if (pool.size <= limit) return pool
        fun pct(get: (LiveMarket.Row) -> Double?): DoubleArray {
            val idx = pool.indices.filter { get(pool[it]) != null }
                .sortedByDescending { get(pool[it])!! }
            val out = DoubleArray(pool.size) { 0.0 }
            if (idx.size <= 1) {
                idx.forEach { out[it] = 1.0 }
                return out
            }
            idx.forEachIndexed { rank, i -> out[i] = 1.0 - rank.toDouble() / (idx.size - 1) }
            return out
        }
        val vol = pct { it.volRatio }
        val chg = pct { it.pctChg }
        val amt = pct { it.amount }
        val flow = pct { it.mainNet }
        val scored = pool.mapIndexed { i, row ->
            row to (0.35 * vol[i] + 0.25 * chg[i] + 0.20 * amt[i] + 0.20 * flow[i])
        }
        val ordered = scored.sortedByDescending { it.second }.map { it.first }
        if (rotation <= 0) return ordered.take(limit)

        // 轮换：每轮只深算 30 只，若永远取预评分前列，其余 5000 只**永远不会被深算**。
        // 按档位把名额整体后移（环形），N 轮之内全市场都能轮到一次；
        // 档位用整数而不是随机，保证同一档结果可复现、可测。
        val start = (rotation.toLong() * limit % ordered.size).toInt()
        val rotated = ordered.drop(start) + ordered.take(start)
        return rotated.take(limit)
    }

    /** 全市场铺开需要多少轮（预选上限 × 轮数 ≥ 池子大小）。供心跳记录「覆盖进度」。 */
    internal fun rotationSpan(poolSize: Int, limit: Int): Int =
        if (limit <= 0 || poolSize <= limit) 1 else ((poolSize + limit - 1) / limit)
}
