package com.skapp.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

/**
 * 盘中心跳：把「实时」从「点一下刷新」变成「一直在跑」。
 *
 * ## 两档节奏（为什么不是全都 30 秒）
 *
 * 计算不是瓶颈（单票 140 根 × 16 指标 ≈ 0.17ms，5000 只纯算不到 1s），**取数是**：
 * 每票一次 HTTP 拉日 K，5000 票 / 30 秒 = 167 req/s，必被东财/腾讯限流。
 *
 * 所以分两档：
 * | 档 | 周期 | 内容 | 请求量 |
 * |---|---|---|---|
 * | 心跳 | 30s | 全市场盘口一页页批量拉（clist 500/页，约 11 页）→ [LivePulse] 相位/涨跌停/资金 | ~11 req / 30s |
 * | 深算 | 5min | 候选池 ≤30 只拉日 K → [LocalEngine] 12 引擎 → 打分排序 | ~30 req / 5min |
 *
 * 心跳数据供市场状态、相位、资金榜用；深算结果供推股/引擎池用。两者互不阻塞：
 * 深算那一轮心跳会晚一点，但不会整轮停摆。
 */
object MarketHeartbeat {

    /** 一轮心跳的结果。`deep` 非空表示这轮顺带做了候选池深算。 */
    data class Tick(
        val at: Long,
        val pulse: LivePulse,
        val deep: List<LocalScreener.Screened>? = null,
        val deepAt: Long? = null,
        val error: String? = null,
    )

    private val _ticks = MutableStateFlow<Tick?>(null)

    /** 最近一轮心跳（界面可直接订阅）。 */
    val ticks: StateFlow<Tick?> = _ticks.asStateFlow()

    /**
     * 常驻循环：交易时段内按 [policy] 的节奏跑，收盘后转入低频探测（每 [HeartbeatPolicy.closedPollMs]
     * 醒一次看有没有开盘），直到协程被取消。
     */
    suspend fun run(
        policy: HeartbeatPolicy = HeartbeatPolicy(),
        deepCriteria: LocalScreener.Criteria = LocalScreener.Criteria(maxCandidates = 30),
        onTick: suspend (Tick) -> Unit = {},
    ) {
        var lastTick = 0L
        var lastDeep = 0L
        var deep: List<LocalScreener.Screened>? = null
        var deepAt: Long? = null

        while (currentCoroutineContext().isActive) {
            val open = MarketHours.isTrading() || MarketHours.isAuction()
            if (!open) {
                delay(policy.closedPollMs)
                continue
            }

            val now = System.currentTimeMillis()
            if (!policy.shouldTick(now, lastTick)) {
                delay(policy.nextDelayMs(now, lastTick))
                continue
            }

            // 1) 全股轻量心跳：一次批量盘口，不逐票请求
            val snap = LiveMarket.snapshot(force = true)
            if (snap.isEmpty()) {
                val miss = Tick(
                    at = now,
                    pulse = LivePulse.of(emptyList()),
                    error = "全市场实时行情不可达（东财 clist），本轮跳过，不用旧数据顶替",
                )
                _ticks.value = miss
                onTick(miss)
                delay(policy.nextDelayMs(System.currentTimeMillis(), now))
                continue
            }

            // 2) 到点了才做候选池深算（要逐只拉日 K，成本高）
            if (policy.shouldDeep(now, lastDeep)) {
                val res = runCatching {
                    LocalScreener.screen(deepCriteria, onProgress = {}, mode = "all")
                }.getOrDefault(emptyList())
                if (res.isNotEmpty()) {
                    deep = res
                    deepAt = System.currentTimeMillis()
                    EngineFeedLive.invalidate()   // 深算结果变了，引擎池缓存跟着失效
                }
                lastDeep = System.currentTimeMillis()
            }

            lastTick = now
            val tick = Tick(at = now, pulse = LivePulse.of(snap), deep = deep, deepAt = deepAt)
            _ticks.value = tick
            onTick(tick)

            delay(policy.nextDelayMs(System.currentTimeMillis(), lastTick))
        }
    }

    /** 手动推一轮（例如用户下拉刷新），不等节拍。 */
    suspend fun tickOnce(deep: Boolean = false): Tick? {
        val snap = LiveMarket.snapshot(force = true)
        if (snap.isEmpty()) return null
        val screened = if (deep) {
            runCatching { LocalScreener.screen(mode = "all") }.getOrDefault(emptyList())
        } else null
        val tick = Tick(
            at = System.currentTimeMillis(),
            pulse = LivePulse.of(snap),
            deep = screened?.takeIf { it.isNotEmpty() },
            deepAt = System.currentTimeMillis().takeIf { screened != null },
        )
        _ticks.value = tick
        return tick
    }
}

/**
 * 心跳节奏（纯计算，不碰时间也不碰网络，便于测）。
 *
 * 之所以把「多久一跳」单独抽出来：这类逻辑最容易被改成「每次循环都拉一遍」，
 * 而那样一改就是 30 秒十几次全市场请求，线上只会表现为「被封 IP」，测试里完全看不出来。
 */
data class HeartbeatPolicy(
    val tickMs: Long = 30_000L,
    val deepMs: Long = 5 * 60_000L,
    val closedPollMs: Long = 60_000L,
) {
    /** 距上次心跳够不够一个节拍（首轮 lastTick=0 立即触发）。 */
    fun shouldTick(now: Long, lastTick: Long): Boolean =
        lastTick <= 0L || now - lastTick >= tickMs

    /** 距上次深算够不够一个深算周期（首轮立即触发）。 */
    fun shouldDeep(now: Long, lastDeep: Long): Boolean =
        lastDeep <= 0L || now - lastDeep >= deepMs

    /** 下一跳该等多久：补齐到整节拍，避免「拉取耗时」累积成漂移。 */
    fun nextDelayMs(now: Long, lastTick: Long): Long {
        if (lastTick <= 0L) return 0L
        return (tickMs - (now - lastTick)).coerceIn(0L, tickMs)
    }
}
