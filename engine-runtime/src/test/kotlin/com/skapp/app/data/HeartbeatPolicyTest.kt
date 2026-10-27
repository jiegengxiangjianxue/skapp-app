package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 心跳节奏的验证。
 *
 * 这段逻辑最容易被「顺手简化」成每轮循环都拉一次全市场：测试里没有任何报错，
 * 线上表现只是「行情接口开始 403」。所以节拍、深算间隔、补齐漂移这三件事必须钉住。
 */
class HeartbeatPolicyTest {

    private val policy = HeartbeatPolicy(tickMs = 30_000L, deepMs = 300_000L, closedPollMs = 60_000L)

    @Test
    fun `首轮要立即触发而不是先等一个节拍`() {
        assertTrue(policy.shouldTick(now = 1_000L, lastTick = 0L))
        assertTrue(policy.shouldDeep(now = 1_000L, lastDeep = 0L))
        assertEquals(0L, policy.nextDelayMs(now = 1_000L, lastTick = 0L))
    }

    @Test
    fun `不满一个节拍不重复拉`() {
        assertFalse(policy.shouldTick(now = 20_000L, lastTick = 1_000L))
        assertTrue(policy.shouldTick(now = 32_000L, lastTick = 1_000L))
    }

    @Test
    fun `深算要五分鐘一轮而不是跟着心跳走`() {
        // 心跳 10 次 = 5 分钟：只有第 6 分钟那次才该深算
        var deeps = 0
        var lastDeep = 1_000L
        for (i in 1..11) {
            val now = 1_000L + i * 30_000L
            if (policy.shouldDeep(now, lastDeep)) {
                deeps++
                lastDeep = now
            }
        }
        assertEquals(1, deeps, "5 分钟内只该有一次深算，实际 $deeps 次")
    }

    @Test
    fun `下一跳要补齐到整节拍不累积漂移`() {
        // 拉取花了 8 秒：还剩 22 秒才到下一个整节拍，而不是又等满 30 秒
        assertEquals(22_000L, policy.nextDelayMs(now = 9_000L, lastTick = 1_000L))
        // 拉取超时超过一个节拍：立刻补一跳，且不超过一个节拍
        assertEquals(0L, policy.nextDelayMs(now = 40_000L, lastTick = 1_000L))
        // 差 1 微秒到节拍：补 1 微秒，不重新等满
        assertEquals(1L, policy.nextDelayMs(now = 30_999L, lastTick = 1_000L))
    }

    @Test
    fun `收盘探测间隔要远大于心跳间隔`() {
        assertTrue(policy.closedPollMs >= policy.tickMs)
    }
}
