package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 全市场快照新鲜度：阈值边界与无时间戳口径。
 *
 * 只测纯判定函数（[FreshnessPolicy.snapshotStatus]），不依赖网络与 K 线夹具。
 */
class SnapshotStatusTest {

    private val ttl = FreshnessPolicy.SNAPSHOT_STALE_MS
    private val now = 1_760_000_000_000L

    @Test
    fun withinThresholdIsLive() {
        assertEquals("live", FreshnessPolicy.snapshotStatus(now, now))
        assertEquals("live", FreshnessPolicy.snapshotStatus(now - ttl + 1, now))
        assertEquals("live", FreshnessPolicy.snapshotStatus(now - ttl, now))
    }

    @Test
    fun beyondThresholdIsStale() {
        assertEquals("stale", FreshnessPolicy.snapshotStatus(now - ttl - 1, now))
        assertEquals("stale", FreshnessPolicy.snapshotStatus(now - ttl * 3, now))
    }

    @Test
    fun missingTimestampIsNotStale() {
        // ts <= 0：拿不到时间戳，沿用旧行为（不误报 stale）
        assertEquals("live", FreshnessPolicy.snapshotStatus(0, now))
        assertEquals("live", FreshnessPolicy.snapshotStatus(-1, now))
    }

    @Test
    fun thresholdStaysFiveMinutes() {
        assertEquals(5 * 60_000L, ttl, "阈值改了口径要同步改这里与界面文案")
    }
}
