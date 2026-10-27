package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 补齐四块「原以为端上算不了」的内容，方向逐条钉住。
 *
 * 这类补算最危险的错法是**把不可得算成 0**：RPS 250 日填 0 会被当成「倒数第一」，
 * 封板率没有炸板数据却报 100% 会让人以为板板都封死。所以下面既验算得对不对，
 * 也验缺料时是不是明说「不可得」。
 */
class LiveExtrasTest {

    private fun row(
        code: String,
        pct: Double? = 1.0,
        price: Double? = 10.0,
        prevClose: Double? = 10.0,
        high: Double? = 10.2,
        chg60d: Double? = 5.0,
        amount: Double = 1.0e8,
        name: String = "股$code",
    ) = LiveMarket.Row(
        code = code,
        name = name,
        price = price,
        pctChg = pct,
        volumeLots = 1.0,
        amount = amount,
        amplitude = 1.0,
        turnoverRate = 1.0,
        pe = 10.0,
        volRatio = 1.0,
        mv = 1.0e9,
        mainNet = 1.0e6,
        ts = 1_700_000_000_000L,
        high = high,
        prevClose = prevClose,
        chg60d = chg60d,
    )

    // ── 封板率 ──

    @Test
    fun `封板率按涨停除以涨停加炸板`() {
        val rows = listOf(
            // 涨停：现价 = 涨停价 11.00
            row("600001", pct = 10.0, price = 11.0, prevClose = 10.0, high = 11.0),
            // 炸板：摸到 11.00 又回落
            row("600002", pct = 5.0, price = 10.5, prevClose = 10.0, high = 11.0),
            // 没摸过涨停
            row("600003", pct = 1.0, price = 10.1, prevClose = 10.0, high = 10.2),
        )
        val s = LiveExtras.seal(rows)
        assertEquals(1, s.limitUp)
        assertEquals(1, s.broken)
        assertEquals(0.5, s.ratio)
    }

    @Test
    fun `缺少昨收或最高价时不计入分母而不是当成没炸板`() {
        // 没有昨收就推不出涨停价，两种状态都判不了 → 不进分子也不进分母
        val rows = listOf(
            row("600001", pct = 10.0, price = 11.0, prevClose = null, high = 11.0),
            row("600002", pct = 9.0, price = 10.9, prevClose = null, high = 10.95),
        )
        val s = LiveExtras.seal(rows)
        assertEquals(0, s.limitUp)
        assertEquals(0, s.broken)
        assertNull(s.ratio, "没有可用样本时封板率必须不可得，不能报 100%")

        // 昨收在、最高价缺：现价封在涨停价就是涨停（不需要最高价）；但炸板判不了，不进分母
        val s2 = LiveExtras.seal(listOf(row("600003", pct = 10.0, price = 11.0, prevClose = 10.0, high = null)))
        assertEquals(1, s2.limitUp)
        assertEquals(0, s2.broken)
        assertEquals(1.0, s2.ratio)
    }

    // ── RPS ──

    @Test
    fun `rps60 跟着 60 日涨幅单调`() {
        val rows = (0 until 200).map { i ->
            row("60%04d".format(i), chg60d = i.toDouble() - 50)
        }
        val r = LiveExtras.rps(rows, top = 5)
        assertNotNull(r)
        assertEquals(5, r.top.size)
        assertTrue(r.top.first().rps60!! > r.top.last().rps60!!, "涨幅高的百分位必须更高")
        assertTrue(r.top.first().rps60!! > 90.0)
        // 排序名次与百分位同向
        assertEquals(1, r.top.first().rank)
    }

    @Test
    fun `缺料时 RPS 留空而不是填 0`() {
        val rows = (0 until 50).map { row("60%04d".format(it)) }   // 样本不足 100
        assertNull(LiveExtras.rps(rows), "样本不够时百分位无意义，应返回 null")
        val no60 = (0 until 200).map { row("60%04d".format(it), chg60d = null) }
        assertNull(LiveExtras.rps(no60), "没有 60 日涨幅就不能算 RPS")
    }

    @Test
    fun `RPS 只给算得出的那一档并在 note 里说清`() {
        val rows = (0 until 200).map { i -> row("60%04d".format(i), chg60d = i.toDouble()) }
        val r = LiveExtras.rps(rows)!!
        val t = r.top.first()
        assertNotNull(t.rps60)
        assertNull(t.rps20)
        assertNull(t.rps120)
        assertNull(t.rps250, "250 日需逐票日 K，不能拿年初至今冒充")
        assertTrue(r.note.contains("不是 0"))
    }

    // ── 周期池 ──

    @Test
    fun `板块周期分档按涨幅与上涨占比判定`() {
        val res = LocalSectors.aggregate(
            listOf(
                row("600001", pct = 3.0, amount = 5.0e8),
                row("600002", pct = 2.0, amount = 5.0e8),
                row("600003", pct = -3.0, amount = 1.0e8),
            ),
            sectorOf = { if (it == "600003") "弱势板" else "强势板" },
        )
        val c = LiveExtras.cycle(res)!!
        val hot = c.rows.first { it.name == "强势板" }
        val cold = c.rows.first { it.name == "弱势板" }
        assertEquals("主升", hot.phase)
        assertEquals("杀跌", cold.phase)
        assertEquals(2, hot.nUp)
        assertEquals(1, cold.nDown)
        // 成交额占比加起来是 1
        assertEquals(1.0, c.rows.sumOf { it.heatShare ?: 0.0 }, 1e-6)
    }

    @Test
    fun `周期池要说明这是当日横截面而不是周期序列`() {
        val res = LocalSectors.aggregate(listOf(row("600001")), sectorOf = { "某板块" })
        val c = LiveExtras.cycle(res)!!
        assertEquals(1, c.nDays)
        assertTrue(c.note.contains("不是"))
        assertTrue(c.rows.isNotEmpty())
    }

    @Test
    fun `没有板块数据时周期池返回空而不是编一个`() {
        assertNull(LiveExtras.cycle(null))
        assertNull(LiveExtras.cycle(LocalSectors.aggregate(emptyList(), sectorOf = { null })))
    }

    // ── AI 精选 ──

    @Test
    fun `AI 精选必须标明是本地评分不是 LLM`() {
        val rows = (0 until 10).map { row("60%04d".format(it)) }
        val screened = listOf(
            LocalScreener.Screened(
                code = "600001", name = "甲", price = 10.0, pctChg = 2.0, volRatio = 1.2,
                turnover = 1e8, pe = 20.0, mv = 1e10, engine = null,
                centralScore = 66.0, resonanceRatio = 0.6, detailStatus = "ok", buyable = true,
            ),
            LocalScreener.Screened(
                code = "600002", name = "乙", price = 10.0, pctChg = 1.0, volRatio = 1.1,
                turnover = 1e8, pe = 20.0, mv = 1e10, engine = null,
                centralScore = 0.0, resonanceRatio = 0.0, detailStatus = "ok", buyable = false,
            ),
        )
        val p = LiveExtras.aiPick(rows, screened)
        assertEquals("local-score", p.basis)
        assertEquals(10, p.nScanned)
        assertEquals(1, p.nCandidates, "只有正分的才算候选")
        assertTrue(p.note.contains("不是 LLM"))
    }
}
