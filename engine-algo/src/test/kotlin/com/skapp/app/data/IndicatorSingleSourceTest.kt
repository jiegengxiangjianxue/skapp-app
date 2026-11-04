package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 指标「单一定义」回归测试。
 *
 * 背景：同一批指标曾经在四处各写一遍，公式分两派——
 *  - EMA 种子：`ExtendedIndicators` / `TrendForecast` 用「首值」，`CanonicalIndicators` 用「前 n 均值」；
 *  - ATR：`ExtendedIndicators` / `CanonicalIndicators` 用 Wilder，`LocalEngine` / `BookSignalEngine` 用「末 n 根简单平均」；
 *  - RSI 全平序列：因子票保持 50.0 中性；Canonical 保留云端 100.0 契约。
 * 结果同一根 K 线在详情页与推股主链拿到不同波幅/不同指标读数。
 *
 * 收敛后：[ExtendedIndicators] 的基础指标一律委托 [CanonicalIndicators]；
 * [LocalEngine] / [BookSignalEngine] / [TrendForecast] / [FactorWeightStrategy] 的私有副本改为委托。
 * 本测试钉住三件事：
 *  1. 委托关系成立（两条路径逐位相等）；
 *  2. ATR 是 Wilder 平滑，不是简单平均（用可区分两者的序列钉死）；
 *  3. 各引擎实际算出的读数与单一实现一致（走公开入口，不靠反射）。
 */
class IndicatorSingleSourceTest {

    /** 20 根日 K：Wilder ATR(14)=4.481339，末 14 根简单平均=4.857143，两者可区分。 */
    private val closes = listOf(
        100.0, 102.0, 101.0, 105.0, 104.0, 108.0, 103.0, 107.0, 110.0, 106.0,
        111.0, 109.0, 113.0, 108.0, 112.0, 115.0, 111.0, 116.0, 114.0, 118.0,
    )

    private val bars: List<KBar> = closes.mapIndexed { i, c ->
        KBar(date = "2026-08-%02d".format(i % 28 + 1), open = c, close = c, high = c + 1.0, low = c - 1.0, vol = 1000.0)
    }

    private fun assertSameList(expected: List<Double?>, actual: List<Double?>, label: String) {
        assertEquals(expected.size, actual.size, "$label 长度不一致")
        expected.indices.forEach { i ->
            val e = expected[i]
            val a = actual[i]
            if (e == null || a == null) {
                assertTrue(e == null && a == null, "$label 第 $i 位 null 状态不一致：expected=$e actual=$a")
            } else {
                assertEquals(e, a, 1e-12, "$label 第 $i 位不一致")
            }
        }
    }

    // ── 1. 委托关系 ────────────────────────────────────────

    @Test
    fun `sma 与 ema 与标准实现逐位相等`() {
        assertSameList(CanonicalIndicators.sma(closes, 5), ExtendedIndicators.sma(closes, 5), "SMA(5)")
        assertSameList(CanonicalIndicators.ema(closes, 12), ExtendedIndicators.ema(closes, 12), "EMA(12)")
    }

    @Test
    fun `ema 种子为前 n 均值而非首值`() {
        val e = ExtendedIndicators.ema(closes, 5)
        // 前 4 位必须是 null（warm-up 丢点）；若退回「种子=首值」实现，这里会是非 null
        (0 until 4).forEach { assertTrue(e[it] == null, "EMA 第 $it 位应为 null，实际 ${e[it]}") }
        assertEquals(closes.take(5).average(), e[4]!!, 1e-12, "EMA 第 5 位应等于前 5 位均值")
    }

    @Test
    fun `atr 与标准实现相等且为 Wilder 平滑`() {
        val atr = ExtendedIndicators.atr(bars, 14)
        val canonical = CanonicalIndicators.atr(
            bars.map { it.high }, bars.map { it.low }, bars.map { it.close }, 14,
        ).lastOrNull()
        assertEquals(canonical!!, atr!!, 1e-12, "ExtendedIndicators.atr 必须与 CanonicalIndicators.atr 相等")
        // 黄金值：Wilder 递推（种子=前 14 个 TR 均值）的结果
        assertEquals(4.481339, atr, 1e-5, "ATR 必须是 Wilder 平滑的 4.481339")
        // 反证：简单平均是 4.857143，若有人改回旧实现，这里会失败
        assertNotEquals(4.857143, atr, 1e-5, "ATR 不能退回「末 n 根简单平均」")
    }

    // ── 2. 各引擎实际读数（公开入口，防回退）─────────────────

    @Test
    fun `本地引擎的 ATR14 与单一实现一致`() {
        // analyze 对短序列会返回 factors=null（不足最小根数），这里用 60 根真实形状的序列
        val long = (0 until 60).map { 100.0 + it * 0.4 + if (it % 3 == 0) 1.2 else -0.8 }
        val longBars = long.mapIndexed { i, c ->
            KBar(date = "2026-06-%02d".format(i % 28 + 1), open = c, close = c, high = c + 1.0, low = c - 1.0, vol = 1000.0)
        }
        val expected = ExtendedIndicators.atr(longBars, 14)!!
        val result = LocalEngine.analyze(longBars)
        val actual = result.factors?.atr14
        assertEquals(expected, actual!!, 1e-12, "LocalEngine 组装出的 atr14 必须与单一实现相等")
    }

    @Test
    fun `书籍信号引擎的 ATR 与单一实现一致`() {
        val expected = ExtendedIndicators.atr(bars, 14)!!
        assertEquals(expected, BookSignalEngine.atr(bars, 14), 1e-12, "BookSignalEngine.atr 必须与单一实现相等")
    }

    @Test
    fun `趋势预测的 EMA 与标准实现逐位相等`() {
        assertSameList(CanonicalIndicators.ema(closes, 12), TrendForecast.ema(closes, 12), "TrendForecast.ema(12)")
    }

    @Test
    fun `因子打分的横盘 RSI 保持中性且与行情展示一致`() {
        val flat = List(60) { 10.0 }
        val flatBars = List(60) { bars.first().copy(close = 10.0) }
        val actual = FactorWeightStrategy.rsi(flat, 14)
        assertEquals(50.0, actual, "横盘不应产生满强度看多票")
        assertEquals(MarketDataClient.rsi(flatBars, 14), actual)
    }

    @Test
    fun `因子打分的非横盘 RSI 仍委托标准实现`() {
        for (values in listOf(closes, List(60) { 10.0 + it }, List(60) { 100.0 - it })) {
            assertEquals(CanonicalIndicators.rsi(values, 14).lastOrNull(), FactorWeightStrategy.rsi(values, 14))
        }
        assertEquals(100.0, FactorWeightStrategy.rsi(List(60) { 10.0 + it }, 14))
        assertEquals(0.0, FactorWeightStrategy.rsi(List(60) { 100.0 - it }, 14))
        assertEquals(100.0, CanonicalIndicators.rsi(List(60) { 10.0 }, 14).lastOrNull())
        assertEquals(null, FactorWeightStrategy.rsi(List(14) { 10.0 }, 14))
    }
}
