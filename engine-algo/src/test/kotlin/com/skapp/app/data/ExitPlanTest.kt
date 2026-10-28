package com.skapp.app.data

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 退出纪律引擎的规则测试。
 *
 * 这份测试的意义不是「跑通」，而是把口径钉住：破位线必须用**不含今日**的历史，
 * 回吐过半才算减仓，长下影只能给「候选」不能给买点。
 */
class ExitPlanTest {

    private fun ok(message: String, condition: Boolean) = assertTrue(condition, message)

    private fun near(actual: Double?, expect: Double, what: String) {
        assertNotNull(actual)
        ok("$what：期望 $expect 实际 $actual", kotlin.math.abs(actual!! - expect) < 1e-6)
    }

    private fun bar(
        date: String,
        open: Double,
        close: Double,
        high: Double,
        low: Double,
    ) = KBar(date = date, open = open, close = close, high = high, low = low, vol = 1000.0)

    /** 造一段横盘：价格锚在 [base]，振幅 ±1%。 */
    private fun flat(n: Int, base: Double, startDay: Int = 0): List<KBar> =
        (0 until n).map {
            val d = "2026-01-%02d".format((startDay + it) % 28 + 1)
            bar(d, base, base, base * 1.01, base * 0.99)
        }

    @Test
    fun `历史不足时明确报数据不足而不是编一个结论`() {
        val r = ExitPlan.compute(flat(5, 10.0))
        assertEquals(ExitPlan.ACT_INSUFFICIENT, r.action)
        assertNull(r.stopLine)
        assertTrue(r.reasons.first().contains("需 ≥21 根"))
    }

    @Test
    fun `收盘跌破前20日最低 - 清仓`() {
        val hist = flat(20, 10.0)                      // 最低 9.9
        val today = bar("2026-02-01", 9.9, 9.5, 9.95, 9.4)
        val r = ExitPlan.compute(hist + today)
        assertEquals(ExitPlan.ACT_CLEAR, r.action)
        near(r.stopLine, 9.9, "破位线应是 9.90")
        assertTrue(r.broken)
        assertTrue(r.stopDistPct!! < 0)
    }

    @Test
    fun `今日自己砸出新低不影响清仓线口径`() {
        // 关键回归：若把今天算进最低价，stopLine 会跟着变成 9.4，永远不破位
        val hist = flat(20, 10.0)
        val today = bar("2026-02-01", 10.0, 9.5, 10.05, 9.0)
        val r = ExitPlan.compute(hist + today)
        near(r.stopLine, 9.9, "清仓线不能含今日低点")
        assertEquals(ExitPlan.ACT_CLEAR, r.action)
    }

    @Test
    fun `从高点回吐波幅一半 - 减仓`() {
        // 起涨 10，冲到 20（波幅 10），回落到 14（回吐 60% > 一半）但不破 20 日最低
        val bars = buildList {
            addAll(flat(20, 10.0))
            add(bar("2026-02-01", 10.0, 12.0, 12.5, 9.95))
            add(bar("2026-02-02", 12.0, 20.0, 20.0, 11.9))
            add(bar("2026-02-03", 20.0, 14.0, 20.0, 13.9))
        }
        val r = ExitPlan.compute(bars)
        // 14 > 9.9 未破位；止盈线 = 20 - 0.5*(20-10) = 15 → 14 < 15 → 减仓
        near(r.trailLine, 15.0, "止盈线")
        assertEquals(ExitPlan.ACT_TRIM, r.action)
        assertTrue(r.peakDrawdownPct!! > 25)
    }

    @Test
    fun `长下影且收阴 - 只给止跌观察`() {
        val hist = flat(20, 10.0)
        // 振幅 10.0→9.0，实体在上沿，下影长：low 9.0, open 9.98, close 9.9, high 10.0
        val today = bar("2026-02-01", 9.98, 9.9, 10.0, 9.0)
        val r = ExitPlan.compute(hist + today)
        ok("下影占比应 ≥50%", (r.lowerShadow ?: 0.0) >= 0.5)
        assertEquals(ExitPlan.ACT_WATCH, r.action)
        assertTrue(r.reasons.first().contains("候选"))
    }

    @Test
    fun `平淡收阳 - 持有`() {
        val hist = flat(20, 10.0)
        val today = bar("2026-02-01", 10.0, 10.4, 10.5, 9.98)
        val r = ExitPlan.compute(hist + today)
        assertEquals(ExitPlan.ACT_HOLD, r.action)
        assertNotNull(r.stopLine)
        assertTrue(r.rangePos!! in 0.0..1.0)
    }

    @Test
    fun `时间窗数得对 - 显著低点在13日前`() {
        val bars = buildList {
            addAll(flat(20, 10.0, startDay = 1))
            // 一个明显的尖底
            add(bar("2026-01-21", 10.0, 9.0, 10.0, 8.0))
            // 之后 13 根缓涨
            for (i in 1..13) {
                val b = 10.0 + i * 0.1
                add(bar("2026-01-%02d".format(22 + i), b, b, b * 1.005, b * 0.995))
            }
        }
        val r = ExitPlan.compute(bars)
        val hint = r.timeHint
        assertNotNull(hint)
        // 低点在倒数第 14 根，距今日 13 个交易日 → 命中斐波窗口
        ok("hint=$hint", hint!!.contains("13 个交易日"))
        ok("hint=$hint", hint.contains("斐波那契窗口"))
    }
}
