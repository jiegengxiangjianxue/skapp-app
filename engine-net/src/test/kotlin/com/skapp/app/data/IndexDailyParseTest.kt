package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 指数日 K 解析测试 —— 用**真实响应**当夹具（`testdata/parity/index_kline.json`）。
 *
 * 为什么必须有它：指数通道是本轮新开的取数路径（holo 仓没有指数，见 [IndexDaily] 注释），
 * 一旦字段顺序读错（腾讯是 o,c,h,l,v，东财是逗号串同序），regime 会拿到错的收盘价，
 * 而错的价格**不会报错**，只会让 gate 静默偏移。
 */
class IndexDailyParseTest {

    private fun fixture(): JSONObject {
        val base = System.getProperty("skapp.fixtures") ?: "../testdata"
        val f = File(base, "parity/index_kline.json")
        if (!f.isFile) fail("缺少夹具：${f.absolutePath}")
        return JSONObject(f.readText())
    }

    @Test
    fun `腾讯 day 数组按 开收高低量 解析`() {
        val fx = fixture()
        val bars = IndexDaily.parseTencent(fx.getString("tencent"), "sh000001")
        assertTrue(bars.size >= 5, "应解析出 5 根，实际 ${bars.size}")
        val first = bars.first()
        assertEquals("2026-10-22", first.date)
        assertEquals(3714.48, first.open, 1e-6)
        assertEquals(3658.03, first.close, 1e-6)
        assertEquals(3714.48, first.high, 1e-6)
        assertEquals(3649.86, first.low, 1e-6)
        assertTrue(first.vol > 0.0)
        // 升序且收盘全部为正 —— regime 判定只吃收盘，这条必须成立
        assertTrue(bars.zipWithNext().all { (a, b) -> a.date <= b.date })
        assertTrue(bars.all { it.close > 0.0 })
    }

    @Test
    fun `东财 klines 逗号串按 开收高低量 解析`() {
        val bars = IndexDaily.parseEastmoney(fixture().getString("eastmoney"))
        assertEquals(2, bars.size)
        assertEquals("2026-10-28", bars[0].date)
        assertEquals(3695.12, bars[0].open, 1e-6)
        assertEquals(3678.44, bars[0].close, 1e-6)
        assertEquals(3701.20, bars[0].high, 1e-6)
        assertEquals(3660.31, bars[0].low, 1e-6)
        assertEquals(3688.91, bars[1].close, 1e-6)
    }

    @Test
    fun `坏响应返回空表而不是造数`() {
        assertEquals(emptyList(), IndexDaily.parseTencent("not json", "sh000001"))
        assertEquals(emptyList(), IndexDaily.parseTencent("{}", "sh000001"))
        assertEquals(emptyList(), IndexDaily.parseEastmoney("{\"data\":{}}"))
        assertEquals(emptyList(), IndexDaily.parseEastmoney(""))
    }

    @Test
    fun `secid 换算`() {
        assertEquals("1.000001", IndexDaily.secid("sh000001"))
        assertEquals("0.399001", IndexDaily.secid("sz399001"))
        // 纯数字按**个股**规则补前缀（000xxx → sz），所以指数必须写全 sh/sz，
        // 否则沪深 300 会被当成深市个股：这是调用方责任，测试把它钉成约定而不是掩盖。
        assertEquals("0.000300", IndexDaily.secid("000300"))
        assertEquals("1.000300", IndexDaily.secid("sh000300"))
    }
}
