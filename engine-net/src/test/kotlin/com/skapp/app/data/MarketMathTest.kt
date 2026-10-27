package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 行情口径与指标数学的定点验证。
 *
 * 这些错误在界面上「看起来都正常」：涨停多算几只、停牌算成平盘、
 * 横盘股 RSI=100 被当超买、盘中量比只有真值的 1/4 —— 全都长得像合理数据。
 */
class MarketMathTest {

    private fun bar(date: String, close: Double, vol: Double = 1000.0) = KBar(
        date = date,
        open = close,
        close = close,
        high = close * 1.01,
        low = close * 0.99,
        vol = vol,
    )

    private fun row(
        code: String,
        price: Double?,
        pct: Double?,
        prevClose: Double? = null,
        high: Double? = null,
        volLots: Double? = 1000.0,
        name: String = "测试股",
    ) = LiveMarket.Row(
        code = code, name = name, price = price, pctChg = pct, volumeLots = volLots,
        amount = 1.0e8, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0, volRatio = 1.0,
        mv = 1.0e9, mainNet = 0.0, ts = 1_700_000_000_000L,
        high = high, prevClose = prevClose,
    )

    // ── 涨跌停：有昨收就按价判，不许把「差一点」算成涨停 ──

    @Test
    fun `涨到九点七但没到涨停价不能算涨停`() {
        // 昨收 10.00 → 涨停价 11.00；现价 10.97 涨幅 9.7%，不是涨停
        val r = row("600001", price = 10.97, pct = 9.7, prevClose = 10.0, high = 10.98)
        assertFalse(r.atLimitUp, "9.7% 不是主板涨停，旧口径 ±0.35 的余量会把它算进去")
    }

    @Test
    fun `低价股涨停涨幅不足百分之十也要算涨停`() {
        // 昨收 3.11 → 涨停价 3.42（+9.97%）；现价 3.42 就是涨停
        val r = row("600002", price = 3.42, pct = 9.97, prevClose = 3.11, high = 3.42)
        assertTrue(r.atLimitUp)
        assertEquals(3.42, r.limitUpPrice)
    }

    @Test
    fun `三十厘米板的涨停不会被漏判`() {
        // 北交所：昨收 10.00，上限 30% → 13.00
        val r = row("830001", price = 13.0, pct = 30.0, prevClose = 10.0, high = 13.0)
        assertEquals(30.0, r.limitCap)
        assertTrue(r.atLimitUp)
    }

    @Test
    fun `科创板 CDR 六八九按二十厘米判`() {
        val r = row("689001", price = 12.0, pct = 20.0, prevClose = 10.0, high = 12.0)
        assertEquals(20.0, r.limitCap, "689 是科创板 CDR，上限 20%，旧实现按 10% 处理")
        assertTrue(r.atLimitUp)
    }

    @Test
    fun `没有昨收时才退回涨幅近似`() {
        val r = row("600003", price = 11.0, pct = 10.0, prevClose = null, high = null)
        assertTrue(r.atLimitUp, "缺昨收只能用涨幅近似，10% 应判涨停")
        assertNull(r.limitUpPrice)
    }

    @Test
    fun `跌停同样按价判`() {
        val atLimit = row("600004", price = 9.0, pct = -10.0, prevClose = 10.0, high = 10.0)
        assertTrue(atLimit.atLimitDown)
        val nearMiss = row("600005", price = 9.05, pct = -9.5, prevClose = 10.0, high = 10.0)
        assertFalse(nearMiss.atLimitDown)
    }

    // ── 停牌：有行没量，不许算「平盘」 ──

    @Test
    fun `停牌票不可交易`() {
        val suspended = row("600006", price = 0.0, pct = 0.0, volLots = 0.0)
        assertFalse(suspended.tradable)
        val normal = row("600007", price = 10.0, pct = 0.0)
        assertTrue(normal.tradable)
    }

    // ── RSI：横盘不能是 100 ──

    @Test
    fun `完全横盘时 RSI 是五十而不是一百`() {
        val bars = (0 until 40).map { bar("2026-09-%02d".format(it % 28 + 1), 10.0) }
        assertEquals(50.0, MarketDataClient.rsi(bars, 14), "横盘股 RSI=100 会被判「超买」扣分")
    }

    @Test
    fun `单边上涨时 RSI 接近一百`() {
        val bars = (0 until 40).map { bar("2026-09-%02d".format(it % 28 + 1), 10.0 + it) }
        assertTrue((MarketDataClient.rsi(bars, 14) ?: 0.0) > 99.0)
    }

    // ── 盘中折算量比 ──

    @Test
    fun `盘中未走完的量比要按已开市分钟折算`() {
        // 前 5 日均量 4000；今日已走 60 分钟，量 1000（相当于全天 4000）
        val history = (0 until 5).map { bar("2026-10-0%d".format(it + 1), 10.0, 4000.0) }
        val today = bar("2026-10-27", 10.0, 1000.0)
        val bars = history + today
        val raw = MarketDataClient.volumeRatio(bars, 5)!!
        assertEquals(0.25, raw, 1e-9, "原始量比只有 1/4，会把正常放量读成缩量")
        val paced = MarketDataClient.volumeRatioPaced(bars, 5, 60)!!
        assertEquals(1.0, paced, 1e-9)
    }

    @Test
    fun `收盘后不做折算`() {
        val history = (0 until 5).map { bar("2026-10-0%d".format(it + 1), 10.0, 4000.0) }
        val bars = history + bar("2026-10-27", 10.0, 8000.0)
        assertEquals(
            MarketDataClient.volumeRatio(bars, 5),
            MarketDataClient.volumeRatioPaced(bars, 5, 240),
        )
    }
}
