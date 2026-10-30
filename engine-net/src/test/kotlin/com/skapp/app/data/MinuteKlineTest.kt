package com.skapp.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinuteKlineTest {

    private fun near(expected: Double, actual: Double) = assertTrue(
        abs(expected - actual) < 1e-9, "expected=$expected actual=$actual",
    )

    @Test
    fun parseSinaJsonp() {
        val txt = "/*<script>location.href='//sina.com';</script>*/\n" +
            "var _=([{\"day\":\"2026-10-30 11:10:00\",\"open\":\"1370.790\",\"high\":\"1372.000\"," +
            "\"low\":\"1370.100\",\"close\":\"1371.510\",\"volume\":\"11230\"}," +
            "{\"day\":\"2026-10-30 11:15:00\",\"open\":\"1371.510\",\"high\":\"1371.900\",\"low\":\"1370.500\"," +
            "\"close\":\"1370.900\",\"volume\":\"9510\"}]);"
        val bars = MinuteKline.parseSina(txt)
        assertEquals(2, bars.size)
        assertEquals("2026-10-30 11:10:00", bars[0].date)
        near(1370.79, bars[0].open)
        near(1372.0, bars[0].high)
        near(1371.51, bars[0].close)
        near(11230.0, bars[0].vol)
    }

    /** 2026-10-30 容器内实测的新浪 scale=1 响应：带 amount 字段，解析器按既有字段取值。 */
    @Test
    fun minutePeriodWiredToSinaScaleOne() {
        assertEquals(1, MinuteKline.Period.M1.scale)
        assertEquals("m1", MinuteKline.Period.M1.tx)
        val txt = "var _=([{\"day\":\"2026-10-30 14:57:00\",\"open\":\"1391.400\",\"high\":\"1392.000\"," +
            "\"low\":\"1388.000\",\"close\":\"1389.150\",\"volume\":\"42900\"," +
            "\"amount\":\"59668531.6563\"}]);"
        val bars = MinuteKline.parseSina(txt)
        assertEquals(1, bars.size)
        assertEquals("2026-10-30 14:57:00", bars[0].date)
        near(1389.15, bars[0].close)
        near(42900.0, bars[0].vol)
    }

    @Test
    fun parseTencentMkline() {
        val txt = "{\"code\":0,\"data\":{\"sh600519\":{\"m5\":[" +
            "[\"202610301110\",\"1370.79\",\"1371.51\",\"1372.00\",\"1370.10\",\"11230\"]," +
            "[\"202610301115\",\"1371.51\",\"1370.90\",\"1371.90\",\"1370.50\",\"9510\"]]}}}"
        val bars = MinuteKline.parseTencent(txt, "sh600519", "m5")
        assertEquals(2, bars.size)
        near(1370.79, bars[0].open)
        near(1371.51, bars[0].close)
        near(1372.0, bars[0].high)
        near(1370.1, bars[0].low)
    }

    @Test
    fun badInputYieldsEmpty() {
        assertTrue(MinuteKline.parseSina(null).isEmpty())
        assertTrue(MinuteKline.parseSina("<html>301</html>").isEmpty())
        assertTrue(MinuteKline.parseSina("var _=([{\"day\":\"\",\"close\":\"0\"}]);").isEmpty())
        assertTrue(MinuteKline.parseTencent("", "sh600519", "m5").isEmpty())
        assertTrue(MinuteKline.parseTencent("{\"code\":0,\"data\":{}}", "sh600519", "m5").isEmpty())
    }

    @Test
    fun tencentSymbol() {
        assertEquals("sh600519", MinuteKline.txCode("600519"))
        assertEquals("sz000001", MinuteKline.txCode("000001"))
        assertEquals("sh510300", MinuteKline.txCode("510300"))
        assertEquals("bj830799", MinuteKline.txCode("830799"))
        assertEquals("sh600519", MinuteKline.txCode("SH600519"))
        assertNull(MinuteKline.txCode("60051"))
    }
}
