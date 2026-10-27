package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 实时市场脉搏的方向性验证。
 *
 * 这些断言不改「能不能跑」，钉的是**方向**：全线红盘不能算成弱势、跌停该比涨停更杀伤、
 * 资金榜不能把流出排进流入。相位分数是界面上一句话的结论，方向搞反比没有更糟。
 */
class LivePulseTest {

    private var ts = 1_700_000_000_000L

    private fun row(
        code: String,
        pct: Double,
        name: String? = "测试股",
        mainNet: Double? = null,
    ) = LiveMarket.Row(
        code = code,
        name = name,
        price = 10.0,
        pctChg = pct,
        volumeLots = 1000.0,
        amount = 1.0e8,
        amplitude = 3.0,
        turnoverRate = 1.5,
        pe = 20.0,
        volRatio = 1.3,
        mv = 1.0e10,
        mainNet = mainNet,
        ts = ts,
    )

    private fun codes(n: Int, prefix: String = "6000") =
        (0 until n).map { "$prefix%02d".format(it) }

    @Test
    fun `全线上涨要判强势`() {
        val rows = codes(80).map { row(it, 3.0) }
        val p = LivePulse.of(rows)
        assertEquals(80, p.total)
        assertEquals(80, p.up)
        assertEquals(0, p.down)
        assertEquals(1.0, p.breadth)
        assertEquals("强势", p.label)
        assertTrue(p.phaseScore >= 40, "全线红盘却给出 ${p.phaseScore}")
    }

    @Test
    fun `全线下挫要判弱势`() {
        val rows = codes(80).map { row(it, -3.0) }
        val p = LivePulse.of(rows)
        assertEquals("弱势", p.label)
        assertTrue(p.phaseScore <= -40, "全线绿盘却给出 ${p.phaseScore}")
    }

    @Test
    fun `涨跌家数打平是平衡`() {
        val up = codes(50, "6001").map { row(it, 1.0) }
        val down = codes(50, "0000").map { row(it, -1.0) }
        val p = LivePulse.of(up + down)
        assertEquals(0.5, p.breadth)
        assertEquals("平衡", p.label)
    }

    @Test
    fun `跌停的杀伤要大于涨停的提振`() {
        val upBase = codes(50).map { row(it, 0.0) } + codes(10, "6002").map { row(it, 10.0) }
        val downBase = codes(50).map { row(it, 0.0) } + codes(10, "6003").map { row(it, -10.0) }
        val up = LivePulse.of(upBase)
        val down = LivePulse.of(downBase)
        assertEquals(10, up.limitUp)
        assertEquals(0, up.limitDown)
        assertEquals(10, down.limitDown)
        // 同样 10 只极端样本：跌停侧的负向幅度必须大于涨停侧的正向幅度
        assertTrue(
            -down.phaseScore > up.phaseScore,
            "涨停 ${up.phaseScore} / 跌停 ${down.phaseScore}：跌停权重应当更高",
        )
    }

    @Test
    fun `涨停判定要分板块并识别ST`() {
        val rows = listOf(
            row("600000", 10.0),              // 主板 10cm
            row("600001", 9.5),               // 差 0.5 个点，算涨停（0.35 余量内）
            row("600002", 9.0),               // 不算
            row("300001", 19.8),              // 创业板 20cm
            row("300002", 10.0),              // 创业板 10% 不算涨停
            row("600003", 5.0, "ST测试"),      // ST 5cm
            row("830001", 29.5),              // 北交所 30cm
        )
        val p = LivePulse.of(rows)
        assertEquals(4, p.limitUp, "实际判到 ${p.limitUp} 只涨停")
    }

    @Test
    fun `资金榜要分出流入流出且按金额排序`() {
        val rows = listOf(
            row("600000", 1.0, mainNet = 5.0e7),
            row("600001", 1.0, mainNet = 9.0e7),
            row("600002", -1.0, mainNet = -8.0e7),
            row("600003", -1.0, mainNet = -3.0e7),
        )
        val p = LivePulse.of(rows, topN = 2)
        assertEquals("600001", p.inflow.first().code)
        assertEquals(2, p.inflow.size)
        assertEquals("600002", p.outflow.first().code)
        assertEquals(2, p.outflow.size)
        assertEquals(9.0e7 + 5.0e7 - 8.0e7 - 3.0e7, p.mainNetTotal)
    }

    @Test
    fun `没有样本时不编造结论`() {
        val p = LivePulse.of(emptyList())
        assertEquals(0, p.total)
        assertNull(p.breadth)
        assertNull(p.medianPct)
        assertEquals("无数据", p.label)
        assertEquals(0.0, p.phaseScore)
        assertTrue(p.inflow.isEmpty() && p.outflow.isEmpty())
    }

    @Test
    fun `中位涨幅用中位数而不是均值`() {
        // 均值会被妖股带偏：+10% 一只 + 其余平盘
        val rows = codes(9).map { row(it, 0.0) } + row("600099", 10.0)
        val p = LivePulse.of(rows)
        assertEquals(0.0, p.medianPct, "中位数应当是 0，均值会是 1%")
    }
}
