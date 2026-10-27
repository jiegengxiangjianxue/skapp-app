package com.skapp.app.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        /** 同一份快照算出的板块强弱；市场状态的 cycle_v2 与周期池共用它，不重复聚合 */
        val sectors: LocalSectors.Result? = null,
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
    ) = coroutineScope {
        var lastTick = 0L
        var lastDeep = 0L
        var deep: List<LocalScreener.Screened>? = null
        var deepAt: Long? = null
        var lastPulse: LivePulse? = null
        var lastSectors: LocalSectors.Result? = null
        var deepJob: Job? = null

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

            lastTick = now
            val pulse = LivePulse.of(snap)
            lastPulse = pulse
            val sectors = runCatching { LocalSectors.compute(snapshotIn = snap) }.getOrNull()
            lastSectors = sectors

            // 先发这一轮心跳：盘口是刚拉的，界面该立刻看到
            val tick = Tick(
                at = now, pulse = pulse, deep = deep, deepAt = deepAt, sectors = sectors,
            )
            _ticks.value = tick
            onTick(tick)

            // 2) 到点了才做候选池深算（要逐只拉日 K，成本高）
            //
            // 深算放进子协程，**不阻塞心跳**：一次深算 30 只 × 日 K 可能要十几秒，
            // 串在这里会让「30 秒心跳」变成「深算周期的 5 分钟心跳」，界面时点直接冻住。
            // 同一时刻只允许一个深算在跑，避免慢网时任务堆叠。
            if (policy.shouldDeep(now, lastDeep) && deepJob?.isActive != true) {
                lastDeep = now
                deepJob = launch {
                    val res = runCatching {
                        LocalScreener.screen(deepCriteria, onProgress = {}, mode = "all")
                    }.getOrDefault(emptyList())
                    if (res.isNotEmpty()) {
                        deep = res
                        deepAt = System.currentTimeMillis()
                        EngineFeedLive.invalidate()   // 深算结果变了，引擎池缓存跟着失效
                        // 深算落地后补发一轮：让界面立刻看到新的候选池，而不是等下一个 30 秒
                        lastPulse?.let { p ->
                            val t = Tick(
                                at = System.currentTimeMillis(),
                                pulse = p,
                                deep = deep,
                                deepAt = deepAt,
                                sectors = lastSectors,
                            )
                            _ticks.value = t
                            onTick(t)
                        }
                    }
                }
            }

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
        val deepRows = screened?.takeIf { it.isNotEmpty() }
        val tick = Tick(
            at = System.currentTimeMillis(),
            pulse = LivePulse.of(snap),
            deep = deepRows,
            // 只有真拿到结果才算「深算时点」；否则会出现「有空结果但有新时点」的自相矛盾状态
            deepAt = System.currentTimeMillis().takeIf { deepRows != null },
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
