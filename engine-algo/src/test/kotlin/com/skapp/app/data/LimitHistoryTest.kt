package com.skapp.app.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 云端涨停名单 → 本地快照序列。
 *
 * 核心要钉的是：云端只给「封住的涨停」，还原出来的快照**不能**顺手把封板率算成 100%
 * （那是「今天没炸板」和「我们不知道」的区别），但连板/首板/晋级率必须算得出来。
 */
class LimitHistoryTest {

    private val json = JSONObject(
        """
        {"days":[
          {"date":"2026-10-22","n":2,"codes":["600000","000001"]},
          {"date":"2026-10-23","n":2,"codes":["600000","300750"]}
        ],"coverage":{"first_date":"2026-10-22","last_date":"2026-10-23"}}
        """.trimIndent(),
    )

    @Test
    fun `还原成按日期升序的快照`() {
        val days = LimitHistory.parse(json)
        assertEquals(2, days.size)
        assertEquals("2026-10-22", days.first().date)
        assertEquals(setOf("600000", "000001"), days.first().limitUpCodes())
        assertTrue(days.all { it.final }, "云端名单是收盘回算，天然是终版")
    }

    @Test
    fun `云端名单能算出连板与晋级率`() {
        val days = LimitHistory.parse(json)
        val stats = LimitBoardCalc.compute(days)!!
        assertEquals(2, stats.limitUp)
        assertEquals(1, stats.firstBoard, "300750 昨日没涨停，是首板")
        assertEquals(1, stats.ge2, "600000 昨日也涨停，是连板")
        assertEquals(2, stats.height, "600000 连续两天涨停")
        assertEquals(0.5, stats.promo!!, 1e-9, "晋级率 = 1 / 昨日涨停 2 只")
    }

    @Test
    fun `没有摸板信息时封板率不可得而不是 100`() {
        val days = LimitHistory.parse(json)
        assertTrue(days.all { !it.hasTouchInfo }, "云端名单没有「摸过没封住」这一位")
        val stats = LimitBoardCalc.compute(days)!!
        assertNull(stats.seal, "缺摸板信息时封板率必须不可得")
    }

    @Test
    fun `没有 days 数组返回空列表`() {
        assertTrue(LimitHistory.parse(JSONObject("{}")).isEmpty())
        assertTrue(LimitHistory.parse(null).isEmpty())
    }

    @Test
    fun `窗口取出云端覆盖范围`() {
        val (first, last) = LimitHistory.window(json)
        assertEquals("2026-10-22", first)
        assertEquals("2026-10-23", last)
    }
}
