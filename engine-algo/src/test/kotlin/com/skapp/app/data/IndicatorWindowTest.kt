package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 指标的「窗口方向」口径：n 根必须取**最近** n 根，而不是列表开头 n 根。
 *
 * 这类错误不会报错、不会返回 null，数值也在 0~100 之间看着正常，
 * 但算的是几个月前的数据——只有把两段方向相反的 K 线摆在一起才能暴露。
 */
class IndicatorWindowTest {

    /** 先 20 根单边上涨、再 20 根单边下跌；最近 14 根全跌。 */
    private fun upThenDown(): List<KBar> {
        val bars = ArrayList<KBar>(40)
        var p = 10.0
        repeat(20) { i ->
            val prev = p
            p *= 1.02
            bars += KBar("u$i", prev, p, p * 1.005, prev * 0.995, 1000.0)
        }
        repeat(20) { i ->
            val prev = p
            p *= 0.98
            bars += KBar("d$i", prev, p, prev * 1.005, p * 0.995, 1000.0)
        }
        return bars
    }

    @Test
    fun `CMO 取最近 n 根`() {
        // 旧实现 `for (i in 1..n)` 从头遍历：最老 14 根全涨 → CMO=+100，
        // 于是一只正在下跌的票被算出「动量强」。
        val cmo = ExtendedIndicators.cmo(upThenDown(), 14)!!
        assertTrue(cmo < 0.0, "最近 14 根单边下跌，CMO 应为负，实际 $cmo")
    }

    @Test
    fun `MFI 取最近 n 根`() {
        val mfi = ExtendedIndicators.mfi(upThenDown(), 14)!!
        assertTrue(mfi < 50.0, "最近 14 根典型价持续下行，MFI 应低于 50，实际 $mfi")
    }
}
