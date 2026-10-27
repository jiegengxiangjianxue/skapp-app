package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地算法链路的完整性验证：缓存不串味、候选池不偏科、闸门不被类型坑、妖股判反不回来。
 *
 * 这几处都属于「改一行就悄悄错一天」的位置——线上表现是「推股结果有规律地偏」，
 * 而不是报错。所以每一条都用一个能被推翻的断言钉住。
 */
class ChainIntegrityTest {

    private fun bars(n: Int, closeOf: (Int) -> Double, dateOf: (Int) -> String = { "2026-08-%02d".format(it % 28 + 1) }) =
        (0 until n).map { i ->
            val c = closeOf(i)
            KBar(date = dateOf(i), open = c, close = c, high = c * 1.02, low = c * 0.98, vol = 1000.0 + i)
        }

    // ── 1) 引擎缓存键必须带末根指纹 ──

    @Test
    fun `盘中末根 bar 数值变了就不能复用旧的引擎结果`() {
        val a = bars(60, closeOf = { 10.0 })
        val b = bars(60, closeOf = { 10.0 })
        // 同样 60 根，只有最后一根的收盘/成交量变了（盘中每 60 秒就是这个样子）
        val bUpdated = b.dropLast(1) + b.last().copy(close = 11.0, high = 11.2, vol = 5000.0)
        val ra = DataCache.engine("600000", "qfq", a)
        val rb = DataCache.engine("600000", "qfq", bUpdated)
        assertEquals(10.0, ra.factors?.close)
        assertEquals(11.0, rb.factors?.close, "末根变了却命中旧缓存，盘中一整天都用上午的评分")
    }

    // ── 2) 候选池预选不能只看主力净流入 ──

    private fun row(
        code: String,
        pct: Double,
        volRatio: Double,
        amount: Double,
        mainNet: Double,
    ) = LiveMarket.Row(
        code = code, name = "股$code", price = 10.0, pctChg = pct, volumeLots = 1e5,
        amount = amount, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0, volRatio = volRatio,
        mv = 1e9, mainNet = mainNet, ts = 1_700_000_000_000L,
    )

    @Test
    fun `主力净流出但量比极高的票也能进候选池`() {
        val pool = listOf(
            // 主力大额流入、量比平淡（旧排序会把前 3 名全给这类）
            row("600001", 1.2, 1.25, 3.0e9, 9.0e8),
            row("600002", 1.3, 1.30, 3.0e9, 8.0e8),
            row("600003", 1.1, 1.28, 3.0e9, 7.0e8),
            // 洗盘放量：主力净流出，但量比 4.8
            row("600004", 2.0, 4.8, 2.0e9, -5.0e8),
        )
        val picked = LocalScreener.preselect(pool, limit = 3).map { it.code }
        assertTrue("600004" in picked, "只看主力净流入会把洗盘放量的票永远挡在深算之外，实际选出 $picked")
    }

    @Test
    fun `预选结果不超过上限且保持去重`() {
        val pool = (0 until 100).map { row("60%04d".format(it), it % 5 + 0.5, 1.0 + it % 7, 1e9 + it, it.toDouble()) }
        val picked = LocalScreener.preselect(pool, limit = 30)
        assertEquals(30, picked.size)
        assertEquals(picked.size, picked.map { it.code }.toSet().size)
    }

    @Test
    fun `候选池要按轮换档位摊开而不是每轮都算同一批`() {
        val pool = (0 until 100).map {
            row("60%04d".format(it), 1.0 + (it % 5) * 0.1, 1.0 + it % 3, 1e9 + it, it.toDouble())
        }
        val r0 = LocalScreener.preselect(pool, limit = 30, rotation = 0).map { it.code }
        val r1 = LocalScreener.preselect(pool, limit = 30, rotation = 1).map { it.code }
        val r2 = LocalScreener.preselect(pool, limit = 30, rotation = 2).map { it.code }
        assertEquals(30, r1.size)
        // 三档之间不应完全重叠：否则「轮换」没发生，5000 只里永远只有 30 只被深算
        assertTrue((r0.intersect(r1.toSet())).size < 30, "第 1 档与第 0 档完全相同，轮换无效")
        assertTrue((r1.intersect(r2.toSet())).size < 30)
        // 三档并起来应覆盖整池
        assertEquals(100, (r0 + r1 + r2 + LocalScreener.preselect(pool, 30, 3).map { it.code }).toSet().size)
    }

    @Test
    fun `轮换档位越界要环形回到开头`() {
        val pool = (0 until 40).map { row("60%04d".format(it), 1.0, 1.5, 1e9, it.toDouble()) }
        // 40 只 / 每轮 20 只 → 2 轮铺满，第 2 档回到与第 0 档相同的位置
        val r0 = LocalScreener.preselect(pool, limit = 20, rotation = 0).map { it.code }
        val r2 = LocalScreener.preselect(pool, limit = 20, rotation = 2).map { it.code }
        assertEquals(r0, r2)
        assertEquals(2, LocalScreener.rotationSpan(40, 20))
        assertEquals(1, LocalScreener.rotationSpan(20, 20))
        assertEquals(23, LocalScreener.rotationSpan(4500, 200))
    }

    // ── 3) 全球闸门不能因为数字类型被闷死 ──

    @Test
    fun `外围指数用整数表示涨跌时闸门照样能开`() {
        // 真实接口里 pctChg 可能是 Int（1 / -1）；旧实现 `as? Double` 直接变成 false
        val indices = listOf(
            mapOf<String, Any?>("name" to "道指", "pctChg" to 1),
            mapOf<String, Any?>("name" to "纳指", "pctChg" to 2),
            mapOf<String, Any?>("name" to "标普", "pctChg" to -1),
        )
        val score = StrategyCard.calculateGlobalScore(indices)
        assertTrue(score > 0, "两个涨一个跌，全球分数应为正，实际 $score（被 as? Double 吞掉了）")
    }

    // ── 4) 妖股判定方向 ──

    @Test
    fun `低位横盘算非妖股`() {
        val bs = bars(40, closeOf = { if (it == 39) 9.5 else 10.0 })
        assertTrue(StrategyCard.checkNotYaoGu(bs))
    }

    @Test
    fun `十日翻三成算妖股`() {
        val bs = bars(40, closeOf = { i -> if (i < 30) 10.0 else 10.0 + (i - 29) * 0.5 })
        assertFalse(StrategyCard.checkNotYaoGu(bs), "10 日涨 50%，不该进「低位温和放量」策略卡")
    }

    @Test
    fun `贴着十日高点算追高`() {
        val bs = bars(40, closeOf = { i -> if (i < 35) 10.0 else 10.0 + (i - 34) * 0.05 })
        assertFalse(StrategyCard.checkNotYaoGu(bs), "离 10 日高点不到 2%，属于追高")
    }

    // ── 5) 心跳 tick 的深算时点不能自相矛盾 ──

    @Test
    fun `策略卡规则名要与实际口径一致`() {
        // 规则名里写了「240 日位<35% + 量比 + 涨 0-5% + 非妖股」，端口径必须都真的在
        val src = java.io.File("src/main/java/com/skapp/app/data/StrategyCard.kt").takeIf { it.exists() }?.readText()
        assertNotNull(src)
        assertTrue(src.contains("pos240"))
        assertTrue(src.contains("checkNotYaoGu"))
    }
}
