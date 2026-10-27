package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SuperTrend 方向必须真的会翻转。
 *
 * 旧实现把上下轨写反了（「上轨」= hl2 − 3·ATR），于是「收盘 > 上轨」几乎恒成立，
 * 方向永远是 +1：这个引擎在每只票上都给 68 分看多，既拉高综合分，又给共振灌了一个
 * 永远 bullish 的票。下跌行情里它是唯一不会翻空的引擎——所以这里用下跌序列钉死它。
 */
class SuperTrendTest {

    private fun bars(closes: List<Double>) = closes.mapIndexed { i, c ->
        KBar(
            date = "2026-08-%02d".format(i % 28 + 1),
            open = c, close = c, high = c * 1.01, low = c * 0.99, vol = 1000.0,
        )
    }

    @Test
    fun `单边上涨判多头`() {
        val bs = bars((0 until 60).map { 10.0 + it * 0.5 })
        val (dir, series) = ExtendedIndicators.superTrend(bs)
        assertEquals(1.0, dir)
        assertEquals(bs.size, series.size)
    }

    @Test
    fun `单边下跌判空头`() {
        val bs = bars((0 until 60).map { 40.0 - it * 0.5 })
        val (dir, _) = ExtendedIndicators.superTrend(bs)
        assertEquals(-1.0, dir, "下跌序列仍判多头，说明上下轨又写反了")
    }

    @Test
    fun `趋势中途翻转要跟着翻`() {
        // 先涨 40 根，再跌 40 根
        val up = (0 until 40).map { 10.0 + it * 0.5 }
        val down = (0 until 40).map { 29.5 - it * 0.6 }
        val bs = bars(up + down)
        val (dir, series) = ExtendedIndicators.superTrend(bs)
        assertEquals(-1.0, dir)
        // 上涨段结尾必须是多头，否则「中途翻转」这件事根本没发生
        assertTrue(series[38] == 1.0, "上涨段末尾应为多头，实际 ${series[38]}")
    }

    @Test
    fun `数据不足时返回不可得`() {
        val (dir, series) = ExtendedIndicators.superTrend(bars((0 until 5).map { 10.0 }))
        assertEquals(null, dir)
        assertTrue(series.isEmpty())
    }

    @Test
    fun `横盘时 MFI 是五十而不是一百`() {
        val bs = bars((0 until 40).map { 10.0 })
        assertEquals(50.0, ExtendedIndicators.mfi(bs, 14), "横盘 MFI=100 会被资金引擎判成超买")
    }
}
