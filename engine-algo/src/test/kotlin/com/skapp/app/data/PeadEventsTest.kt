package com.skapp.app.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PEAD 事件表解析与窗口判断。
 *
 * 钉三件事：公告日截掉时间部分、缺字段给 null 不编造、窗口按自然日上限且越界不误判。
 */
class PeadEventsTest {

    private val json = JSONObject(
        """
        {"ts":"2026-10-28T20:31:55+08:00","events":[
          {"symbol":"sh600000","announce_date":"2026-08-28 00:00:00","eps":0.89,"sue":-0.89,
           "n_quarters":105,"car_5d":0.0131,"car_10d":0.007642,"car_20d":0.026201},
          {"symbol":"sz000001","announce_date":"2026-10-20 00:00:00","eps":null,"sue":1.5,"n_quarters":12}
        ],"events_window":{"cutoff":"2026-07-30","n_output":2},
         "warnings":["events 只输出最近 90 天公告的 2 条"]}
        """.trimIndent(),
    )

    private val cst = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    @Test
    fun `解析事件与窗口口径`() {
        val book = PeadEvents.parse(json)!!
        assertEquals(2, book.events.size)
        val ev = book.events["sh600000"]!!
        assertEquals("2026-08-28", ev.announceDate, "公告日要截掉时间部分")
        assertEquals(-0.89, ev.sue!!, 1e-9)
        assertEquals(105, ev.nQuarters)
        assertEquals(0.007642, ev.car10d!!, 1e-9)
        assertEquals("2026-07-30", book.cutoff)
        assertEquals(2, book.nOutput)
        assertEquals(1, book.notes.size, "云端告警要转发给界面解释覆盖率")
    }

    @Test
    fun `缺字段给 null 不编造`() {
        val book = PeadEvents.parse(json)!!
        val noEps = book.events["sz000001"]!!
        assertNull(noEps.eps)
        assertNull(noEps.car5d)
        assertNull(noEps.car10d)
    }

    @Test
    fun `没有 events 数组时返回 null`() {
        assertNull(PeadEvents.parse(JSONObject("{}")))
        assertNull(PeadEvents.parse(null))
    }

    @Test
    fun `窗口按自然日上限判断`() {
        val now = System.currentTimeMillis()
        val tenDaysAgo = cst.format(Date(now - 10L * 86_400_000L))
        val fortyDaysAgo = cst.format(Date(now - 40L * 86_400_000L))
        assertTrue(PeadEvents.inWindow(PeadEvent("x", tenDaysAgo), now)!!, "10 天前仍在窗口内")
        assertFalse(PeadEvents.inWindow(PeadEvent("x", fortyDaysAgo), now)!!, "40 天前窗口已过")
    }

    @Test
    fun `公告日无法解析时不猜`() {
        assertNull(PeadEvents.daysSince("not-a-date"))
        assertNull(PeadEvents.inWindow(PeadEvent("x", "")))
        assertTrue(PeadEvents.caveatOf(PeadEvent("x", "")).contains("无法解析"))
    }
}
