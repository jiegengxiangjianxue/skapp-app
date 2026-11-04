package com.skapp.app.data

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisSessionPolicyTest {
    private fun at(time: String) = OffsetDateTime.parse("2026-11-02T$time+08:00").toInstant().toEpochMilli()

    @Test fun `CST窗口边界含九点消息修订`() {
        assertEquals("realtime", AnalysisSessionPolicy.activeSession(at("08:59:59"), true))
        assertEquals("morning", AnalysisSessionPolicy.activeSession(at("09:00:00"), true))
        assertTrue(AnalysisSessionPolicy.window("morning", at("09:15:00"), true).updating)
        assertEquals("midday", AnalysisSessionPolicy.activeSession(at("11:30:00"), true))
        assertTrue(AnalysisSessionPolicy.window("morning", at("11:30:00"), true).frozen)
        assertTrue(AnalysisSessionPolicy.window("midday", at("15:00:00"), true).frozen)
        assertEquals("realtime", AnalysisSessionPolicy.activeSession(at("15:00:00"), true))
    }

    @Test fun `休市实时持续更新未知交易日不猜`() {
        assertTrue(AnalysisSessionPolicy.window("realtime", at("12:00:00"), false).updating)
        assertEquals(null, AnalysisSessionPolicy.activeSession(at("10:00:00")))
        assertFalse(AnalysisSessionPolicy.window("morning", at("10:00:00")).calendarKnown)
        assertFalse(AnalysisSessionPolicy.window("premarket", at("09:10:00"), true).updating)
    }

    @Test fun `周一盘前不丢周五bar只排当日`() {
        val now = at("09:10:00")
        assertTrue(AnalysisSessionPolicy.acceptsBar("2026-10-30", now, true))
        assertFalse(AnalysisSessionPolicy.acceptsBar("2026-11-02", now, true))
        assertTrue(AnalysisSessionPolicy.acceptsBar("2026-11-02", now, false))
        assertFalse(AnalysisSessionPolicy.acceptsBar("2026-11-03", now, false))
        assertFalse(AnalysisSessionPolicy.acceptsBar("unknown", now, false))
    }

    @Test fun `UTC日期按CST判断`() {
        val now = OffsetDateTime.parse("2026-11-01T17:00:00Z").toInstant().toEpochMilli()
        assertTrue(AnalysisSessionPolicy.acceptsBar("2026-11-01", now, true))
        assertFalse(AnalysisSessionPolicy.acceptsBar("2026-11-02", now, true))
    }
    @Test fun lateScanCannotRewriteFrozenWindow() {
        assertFalse(AnalysisSessionPolicy.mayPublish("morning", at("11:29:50"), at("11:30:20"), true))
        assertFalse(AnalysisSessionPolicy.mayPublish("midday", at("14:59:50"), at("15:00:20"), true))
        assertTrue(AnalysisSessionPolicy.mayPublish("morning", at("10:00:00"), at("10:00:20"), true))
    }

}
