package com.skapp.app.data.health

import com.skapp.app.data.health.ProcessHealthCore.MemLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫判定逻辑的回归测试（vc19）。
 *
 * 这套东西的价值全在「阈值与去重规则是否正确」，写错了比没有更糟：
 *   - 阈值太紧 → 正常长帧被记成 ANR，用户带一堆假现场来排查；
 *   - 去重写错 → 一次卡死刷出几十个文件，或者反过来只记第一条、丢掉「卡了多久」的演进；
 *   - 内存分级漏掉低内存标记 → 静默回收连一条记录都不剩。
 *
 * 全是确定性断言，不依赖设备性能，所以和 PerfProbeTest（只打印不断言）分开。
 */
class ProcessHealthCoreTest {

    // ── 主线程心跳 / 停顿判定 ──────────────────────────────────────────

    @Test
    fun `stall threshold is inclusive at 5s`() {
        assertFalse(ProcessHealthCore.isStalled(nowMs = 4_999L, lastBeatAtMs = 0L))
        assertFalse(ProcessHealthCore.isStalled(nowMs = 4_500L, lastBeatAtMs = 1_000L))
        assertTrue(ProcessHealthCore.isStalled(nowMs = 5_000L, lastBeatAtMs = 0L))
        assertTrue(ProcessHealthCore.isStalled(nowMs = 9_000L, lastBeatAtMs = 1_000L))
    }

    @Test
    fun `below threshold never records`() {
        val d = ProcessHealthCore.decideStall(blockedMs = 4_999L, alreadyRecorded = 0)
        assertFalse(d.record)
        assertEquals(0, d.episode)
        assertEquals(4_999L, d.blockedMs)
    }

    @Test
    fun `first crossing records episode 1`() {
        val d = ProcessHealthCore.decideStall(blockedMs = 5_000L, alreadyRecorded = 0)
        assertTrue(d.record)
        assertEquals(1, d.episode)
    }

    @Test
    fun `same episode is not recorded twice`() {
        // 已经记过第 1 次，卡到 7s 仍在同一个复报窗口内 → 不再落盘
        val d = ProcessHealthCore.decideStall(blockedMs = 7_000L, alreadyRecorded = 1)
        assertFalse(d.record)
        assertEquals(1, d.episode)
    }

    @Test
    fun `stall progression records every repeat interval`() {
        // 阈值 5s、复报 10s：5s -> 15s -> 25s 各记一次
        assertTrue(ProcessHealthCore.decideStall(5_000L, 0).record)
        val second = ProcessHealthCore.decideStall(15_000L, 1)
        assertTrue(second.record)
        assertEquals(2, second.episode)
        val third = ProcessHealthCore.decideStall(25_000L, 2)
        assertTrue(third.record)
        assertEquals(3, third.episode)
        // 卡到 30s 但第三次已经记过，不许重复刷
        assertFalse(ProcessHealthCore.decideStall(30_000L, 3).record)
    }

    @Test
    fun `zero repeat interval degrades to single record`() {
        // 复报间隔配成 0 时不能除零，也不能无限刷：退化成「只记首次」
        val d = ProcessHealthCore.decideStall(blockedMs = 60_000L, alreadyRecorded = 0, repeatMs = 0L)
        assertFalse(d.record)
    }

    // ── 内存水位 ──────────────────────────────────────────────────────

    @Test
    fun `heap ratio guards against invalid max`() {
        assertEquals(0.0, ProcessHealthCore.heapRatio(100L, 0L), 0.0001)
        assertEquals(0.0, ProcessHealthCore.heapRatio(100L, -1L), 0.0001)
        assertEquals(0.5, ProcessHealthCore.heapRatio(128L, 256L), 0.0001)
    }

    @Test
    fun `memory levels follow warn and critical ratios`() {
        assertEquals(MemLevel.OK, ProcessHealthCore.classifyMemory(usedBytes = 100L, maxBytes = 256L, systemLowMemory = false))
        assertEquals(MemLevel.WARN, ProcessHealthCore.classifyMemory(usedBytes = 210L, maxBytes = 256L, systemLowMemory = false))
        assertEquals(MemLevel.CRITICAL, ProcessHealthCore.classifyMemory(usedBytes = 250L, maxBytes = 256L, systemLowMemory = false))
    }

    @Test
    fun `system low memory overrides ratio`() {
        // 系统已报低内存：哪怕堆只用了一成，也必须记，否则静默回收零现场
        assertEquals(MemLevel.CRITICAL, ProcessHealthCore.classifyMemory(usedBytes = 10L, maxBytes = 256L, systemLowMemory = true))
    }

    @Test
    fun `invalid max heap is ok not critical`() {
        assertEquals(MemLevel.OK, ProcessHealthCore.classifyMemory(usedBytes = 999L, maxBytes = 0L, systemLowMemory = false))
    }

    // ── 报告格式 ──────────────────────────────────────────────────────

    @Test
    fun `frames are capped`() {
        val frames = (0 until 100).map { "at frame.$it" }
        assertEquals(40, ProcessHealthCore.trimFrames(frames, 40).size)
        assertEquals(0, ProcessHealthCore.trimFrames(frames, 0).size)
    }

    @Test
    fun `stall report carries episode blocked duration and capped stack`() {
        val frames = (0 until 100).map { "at frame.$it" }
        val body = ProcessHealthCore.formatStallReport(
            stamp = "20261027_120000_123",
            decision = ProcessHealthCore.StallDecision(record = true, episode = 2, blockedMs = 15_000L),
            frames = frames,
        )
        assertTrue(body.startsWith("20261027_120000_123\n"))
        assertTrue(body.contains("kind: main-thread-stall"))
        assertTrue(body.contains("episode: 2"))
        assertTrue(body.contains("blocked_ms: 15000"))
        assertTrue(body.contains("thread: main"))
        assertTrue(body.contains("at frame.0"))
        assertFalse(body.contains("at frame.40"), "栈超过 40 帧必须截断")
        assertEquals(6 + 40, body.trimEnd('\n').lines().size)
    }

    @Test
    fun `memory report has stable decimal point`() {
        val body = ProcessHealthCore.formatMemoryReport(
            stamp = "20261027_120000_456",
            level = MemLevel.WARN,
            usedBytes = 210L * 1024 * 1024,
            maxBytes = 256L * 1024 * 1024,
            nativeBytes = 40L * 1024 * 1024,
            systemLowMemory = false,
            availBytes = 900L * 1024 * 1024,
            thresholdBytes = 200L * 1024 * 1024,
        )
        assertTrue(body.contains("kind: memory-pressure"))
        assertTrue(body.contains("level: WARN"))
        assertTrue(body.contains("heap_used_mb: 210.0"))
        assertTrue(body.contains("heap_max_mb: 256.0"))
        assertTrue(body.contains("heap_ratio: 0.820"))
        assertTrue(body.contains("native_used_mb: 40.0"))
        assertTrue(body.contains("system_low_memory: false"))
        assertTrue(body.contains("sys_avail_mb: 900.0"))
        assertTrue(body.contains("sys_threshold_mb: 200.0"))
    }
}
