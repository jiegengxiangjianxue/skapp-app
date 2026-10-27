package com.skapp.app.data.health

import java.util.Locale

/**
 * 进程健康判定的**纯逻辑**——不含任何 Android 依赖，放在 :engine 是为了能在 JVM 上直接单测。
 *
 * ## 为什么要在 vc19 补这层守卫
 *
 * [com.skapp.app.ui.CrashGuard] 挂的是 `Thread.setDefaultUncaughtExceptionHandler`，
 * 它只能抓到「Java 层抛出且没人接」的异常。线上「打开就闪退」里有一大类根本不走这条路：
 *
 *   1. **ANR** —— 主线程被卡住，系统直接弹框 / 杀进程，没有异常对象可抓；
 *   2. **低内存被 LMK 回收** —— 进程被 SIGKILL，同样不抛异常；
 *   3. **native 崩溃（SIGSEGV 等）** —— Java handler 完全不知情。
 *
 * 这三种的共同表现是「App 直接没了，日志里什么都没有」，而这恰好就是用户拿不到栈时
 * 最可能的真相。所以守卫换了个不依赖异常的抓手：
 *
 *   - **主线程心跳**：主线程每 500ms 自增计数；专用线程盯着计数，超时未跳即判定主线程停顿，
 *     立刻把**当时的主线程栈**写盘。写在停顿**进行中**，不等进程被杀——这点很关键，
 *     ANR 被杀后什么都来不及写。
 *   - **内存水位**：定期采样堆占用 / native 占用 / 系统低内存标记，在 OOM 或 LMK 动手前留痕。
 *
 * ## 为什么判定阈值放在这里
 *
 * 阈值和去重规则是这套东西唯一容易出错的地方（太紧误报、太松丢现场、去重写错会刷爆文件），
 * 所以全部收敛成纯函数，由 `ProcessHealthCoreTest` 在 CI 里断言，而不是散落在 Android 代码里
 * 靠肉眼保证。
 */
object ProcessHealthCore {

    /** 主线程心跳间隔。系统 ANR 门槛是 5s，心跳取 500ms 才有足够分辨率看出「卡了多久」。 */
    const val BEAT_INTERVAL_MS = 500L

    /**
     * 判定主线程停顿（≈ANR 前兆）的阈值。
     *
     * 取 5s = Android 输入事件 ANR 门槛：
     *   - 明显低于它会把正常的长帧（图表重绘、首屏 18 个 JSON 解析 ≈82ms 的几十倍抖动）
     *     误报成 ANR，用户会被一堆假记录带偏；
     *   - 明显高于它又来不及在系统杀进程前落盘。
     */
    const val STALL_THRESHOLD_MS = 5_000L

    /** 同一段停顿的复报间隔：卡死不恢复时留下「5s → 15s → 25s」的演进轨迹，但不刷爆文件数。 */
    const val STALL_REPEAT_MS = 10_000L

    /** 堆占用比例告警线。 */
    const val MEM_WARN_RATIO = 0.80

    /** 堆占用比例危险线（再往上就是 OOM / LMK 的区间）。 */
    const val MEM_CRITICAL_RATIO = 0.92

    /** 内存水位分级。 */
    enum class MemLevel { OK, WARN, CRITICAL }

    /**
     * 一次停顿判定的结果。
     *
     * @param record 本次是否需要落盘
     * @param episode 这段停顿累计记录的次数（心跳恢复后归零，重新从 1 开始）
     * @param blockedMs 已停滞时长
     */
    data class StallDecision(val record: Boolean, val episode: Int, val blockedMs: Long)

    /** 堆占用比例；`maxBytes` 非法（0 或负）时返回 0，避免除零把水位判成 CRITICAL。 */
    fun heapRatio(usedBytes: Long, maxBytes: Long): Double =
        if (maxBytes <= 0L) 0.0 else usedBytes.toDouble() / maxBytes.toDouble()

    /** 心跳是否已逾期。[nowMs] / [lastBeatAtMs] 必须是同一个单调时钟（Android 上是 elapsedRealtime）。 */
    fun isStalled(nowMs: Long, lastBeatAtMs: Long, thresholdMs: Long = STALL_THRESHOLD_MS): Boolean =
        nowMs - lastBeatAtMs >= thresholdMs

    /**
     * 停顿去重：同一段停顿只在首次跨过阈值时记第 1 次，之后每 [repeatMs] 补记一次。
     *
     * [alreadyRecorded] 是**当前这段停顿**此前已落盘的次数（心跳恢复后调用方必须归零）。
     * 用「已记次数」而不是时间戳做键，是为了让卡死时长的增长本身可读：
     * 文件里的 episode 1/2/3 对应 5s / 15s / 25s。
     */
    fun decideStall(
        blockedMs: Long,
        alreadyRecorded: Int,
        thresholdMs: Long = STALL_THRESHOLD_MS,
        repeatMs: Long = STALL_REPEAT_MS,
    ): StallDecision {
        if (blockedMs < thresholdMs || repeatMs <= 0L) {
            return StallDecision(false, alreadyRecorded, blockedMs)
        }
        val expected = 1 + ((blockedMs - thresholdMs) / repeatMs).toInt()
        return StallDecision(expected > alreadyRecorded, expected, blockedMs)
    }

    /**
     * 内存水位分级。
     *
     * 系统已经报 `lowMemory` 时不再看比例：此时 LMK 随时动手，哪怕堆只用了三成也必须记，
     * 否则「进程被静默回收」会连一条记录都没有。
     */
    fun classifyMemory(
        usedBytes: Long,
        maxBytes: Long,
        systemLowMemory: Boolean,
        warnRatio: Double = MEM_WARN_RATIO,
        criticalRatio: Double = MEM_CRITICAL_RATIO,
    ): MemLevel {
        if (systemLowMemory) return MemLevel.CRITICAL
        val ratio = heapRatio(usedBytes, maxBytes)
        return when {
            ratio >= criticalRatio -> MemLevel.CRITICAL
            ratio >= warnRatio -> MemLevel.WARN
            else -> MemLevel.OK
        }
    }

    /** 栈只保留前 [max] 帧：写入发生在进程随时可能被杀的当口，越短越写得完。 */
    fun trimFrames(frames: List<String>, max: Int): List<String> =
        if (max <= 0) emptyList() else frames.take(max)

    /**
     * 主线程停顿报告。
     *
     * 字段名刻意用 `key: value` 单行式，用户把文件内容贴过来时不需要额外解释；
     * 不写设备标识、不写业务数据，只有时间、时长与栈。
     */
    fun formatStallReport(
        stamp: String,
        decision: StallDecision,
        beatIntervalMs: Long = BEAT_INTERVAL_MS,
        frames: List<String> = emptyList(),
        maxFrames: Int = 40,
    ): String = buildString {
        append(stamp).append('\n')
        append("kind: main-thread-stall\n")
        append("episode: ").append(decision.episode).append('\n')
        append("blocked_ms: ").append(decision.blockedMs).append('\n')
        append("beat_interval_ms: ").append(beatIntervalMs).append('\n')
        append("thread: main\n")
        trimFrames(frames, maxFrames).forEach { append(it).append('\n') }
    }

    /** 内存水位报告。 */
    fun formatMemoryReport(
        stamp: String,
        level: MemLevel,
        usedBytes: Long,
        maxBytes: Long,
        nativeBytes: Long,
        systemLowMemory: Boolean,
        availBytes: Long,
        thresholdBytes: Long,
    ): String = buildString {
        append(stamp).append('\n')
        append("kind: memory-pressure\n")
        append("level: ").append(level.name).append('\n')
        append("heap_used_mb: ").append(mb(usedBytes)).append('\n')
        append("heap_max_mb: ").append(mb(maxBytes)).append('\n')
        append("heap_ratio: ").append(String.format(Locale.US, "%.3f", heapRatio(usedBytes, maxBytes))).append('\n')
        append("native_used_mb: ").append(mb(nativeBytes)).append('\n')
        append("system_low_memory: ").append(systemLowMemory).append('\n')
        append("sys_avail_mb: ").append(mb(availBytes)).append('\n')
        append("sys_threshold_mb: ").append(mb(thresholdBytes)).append('\n')
    }

    /** 统一用 Locale.US 格式化，避免不同系统语言下的小数点导致测试与解析漂移。 */
    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1048576.0)
}
