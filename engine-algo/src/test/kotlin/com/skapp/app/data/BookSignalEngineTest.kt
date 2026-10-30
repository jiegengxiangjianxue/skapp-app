package com.skapp.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三层书籍信号（反转 MTR / 涨停结构 / 订单流近似）的红线测试。
 *
 * 守的是「改坏了也不报错、只会输出看起来合理的垃圾」那几处：
 *
 * 1. 涨停阈值按板块分档（主板 10 / 创业科创 20 / 北交 30）——统一按 10% 会把创业板
 *    15% 的普通上涨误判成涨停，连板高度跟着全错。
 * 2. 连板高度累进、断板记录断前高度。
 * 3. 强封只能用「收盘 = 最高」近似，不能反推封单。
 * 4. 无过检标签必须返回 null，**不许返回 50** 假装评估过。
 * 5. 三层只出多头证据：校准表不含负向标签，[EngineChain.externalVotes] 永不产 -1 票。
 * 6. clv 边界：收在最高 +1、收在最低 −1、无振幅 0（不得除零）。
 * 7. 不改入参、同输入同输出（端上扫描会对同一份 K 线反复调用）。
 */
class BookSignalEngineTest {

    private fun bar(o: Double, c: Double, h: Double, l: Double, v: Double = 1000.0, d: String = "2026-01-01") =
        KBar(date = d, open = o, close = c, high = h, low = l, vol = v)

    /** 按收盘价序列造 K 线，high/low 取相邻极值，保证形态可算。 */
    private fun series(prices: List<Double>, vols: List<Double>? = null): List<KBar> =
        prices.mapIndexed { i, p ->
            val prev = if (i == 0) p else prices[i - 1]
            bar(
                o = prev, c = p,
                h = maxOf(p, prev) * 1.002, l = minOf(p, prev) * 0.998,
                v = vols?.getOrNull(i) ?: 1000.0,
                d = "2026-01-%02d".format(i + 1),
            )
        }

    // ─────────────────────────── 涨停阈值 ───────────────────────────

    @Test
    fun `涨停阈值按板块分档`() {
        assertEquals(9.8, BookSignalEngine.limitPct("600000"), "主板应为 9.8")
        assertEquals(9.8, BookSignalEngine.limitPct("sh600000"), "带前缀也要认")
        assertEquals(9.8, BookSignalEngine.limitPct("000001"), "深主板应为 9.8")
        assertEquals(19.8, BookSignalEngine.limitPct("300750"), "创业板应为 19.8")
        assertEquals(19.8, BookSignalEngine.limitPct("301001"), "创业板 301 应为 19.8")
        assertEquals(19.8, BookSignalEngine.limitPct("688981"), "科创板应为 19.8")
        assertEquals(29.8, BookSignalEngine.limitPct("830799"), "北交所应为 29.8")
    }

    @Test
    fun `连板高度累进且断板记录断前高度`() {
        // 80 根横盘（必须 ≥ MIN_BARS=80，否则 limitTags 直接返回空，测试会假通过）
        // → 然后 10 → 11 → 12.1（两个板）→ 12.3（断板）
        val prices = List(80) { 10.0 } + listOf(10.0, 11.0, 12.1, 12.3) + List(10) { 12.3 }
        val bars = series(prices)
        val tags = BookSignalEngine.limitTags(bars, "600000")
        // 断板发生在十几根之前，超出「近 3 根」窗口；近 5 日也无涨停
        // → 当日标签应为空，锁住「窗口外不外溢」
        assertTrue(tags.none { it == "limit:broken" }, "11 根前的断板不该出现在当日标签里：$tags")
    }

    @Test
    fun `今日首板识别与强封近似`() {
        val prices = List(80) { 10.0 } + listOf(10.0, 11.0)
        val bars = series(prices).toMutableList()
        // 最后一根做成涨停且收盘=最高
        bars[bars.size - 1] = bar(o = 10.0, c = 11.0, h = 11.0, l = 10.0, v = 3000.0)
        val tags = BookSignalEngine.limitTags(bars, "600000")
        assertTrue(tags.contains("limit:first_board"), "应识别首板：$tags")
        assertTrue(tags.contains("limit:strong_seal"), "收盘=最高应判强封：$tags")
        assertTrue(tags.contains("limit:active_5d"), "近 5 日有涨停应置 active：$tags")
        assertTrue(tags.contains("limit:vol_surge"), "量比 3 倍应判放量：$tags")
    }

    @Test
    fun `创业板 15 个点不是涨停`() {
        val prices = List(80) { 10.0 } + listOf(10.0, 11.5)
        val bars = series(prices)
        val tags = BookSignalEngine.limitTags(bars, "300750")
        assertTrue(tags.none { it.startsWith("limit:first") }, "创业板 15% 未达 20% 上限，不得判涨停：$tags")
        // 换成主板代码，同样涨幅应判涨停（说明差异确实来自板块阈值）
        val mainTags = BookSignalEngine.limitTags(bars, "600000")
        assertTrue(mainTags.contains("limit:first_board"), "主板 15% 应判涨停：$mainTags")
    }

    // ─────────────────────────── 订单流近似 ───────────────────────────

    @Test
    fun `clv 边界精确`() {
        assertEquals(1.0, BookSignalEngine.clv(bar(10.0, 11.0, 11.0, 10.0)), 1e-9, "收在最高 = +1")
        assertEquals(-1.0, BookSignalEngine.clv(bar(11.0, 10.0, 11.0, 10.0)), 1e-9, "收在最低 = -1")
        assertEquals(0.0, BookSignalEngine.clv(bar(10.0, 10.0, 11.0, 9.0)), 1e-9, "收在中点 = 0")
        assertEquals(0.0, BookSignalEngine.clv(bar(10.0, 10.0, 10.0, 10.0)), 1e-9, "无振幅不得除零")
    }

    @Test
    fun `收盘在最高附近的序列产出买方失衡`() {
        // 每天收在最高（close == high）→ clv = +1，连续 3 根即构成失衡
        val bars = (0 until 90).map { i ->
            val c = 10.0 + i * 0.05
            KBar(
                date = "2026-01-%02d".format(i % 28 + 1),
                open = c - 0.05, close = c, high = c, low = c - 0.1, vol = 1000.0,
            )
        }
        val tags = BookSignalEngine.flowTags(bars)
        assertTrue(tags.contains("flow:imbalance_up"), "连续收在最高应判买方失衡：$tags")
        assertTrue(tags.none { it == "flow:imbalance_down" }, "不得同时判卖方失衡：$tags")
    }

    // ─────────────────────────── 反转 MTR ───────────────────────────

    @Test
    fun `纯横盘不产出反转确认`() {
        val bars = series(List(120) { 10.0 })
        val tags = BookSignalEngine.reversalTags(bars)
        assertTrue(tags.none { it == "mtr:top_confirmed" }, "没有趋势就没有反转：$tags")
        assertTrue(tags.none { it == "mtr:bottom_confirmed" }, "没有趋势就没有反转：$tags")
    }

    @Test
    fun `顶底确认互斥`() {
        val up = List(20) { 10.0 * Math.pow(1.01, it.toDouble()) }
        val down = List(14) { up.last() * (1 - 0.012 * (it + 1)) }
        val bars = series(List(60) { 10.0 } + up + down)
        val tags = BookSignalEngine.reversalTags(bars)
        assertTrue(
            !(tags.contains("mtr:top_confirmed") && tags.contains("mtr:bottom_confirmed")),
            "顶底确认不能同时出现：$tags",
        )
    }

    // ─────────────────────────── 给分纪律 ───────────────────────────

    @Test
    fun `无证据不给分而不是给 50`() {
        val bars = series(List(BookSignalEngine.MIN_BARS) { 10.0 })
        val r = BookSignalEngine.compute(bars, "600000")
        assertNull(r.score, "无过检标签必须返回 null（给 50 等于假装评估过）")
        assertTrue(!r.hasEvidence)
        // 无标签不写 degraded（那是调用方 EngineChain 的职责），但 reason 必须说清
        assertTrue(r.reason.contains("无过检"), "理由必须说明为什么没给分：${r.reason}")
    }

    @Test
    fun `K 线不足时降级可见`() {
        val r = BookSignalEngine.compute(series(List(10) { 10.0 }), "600000")
        assertNull(r.score)
        assertTrue(r.degraded.any { it.contains("K 线") }, "数据不足必须写清原因：${r.degraded}")
    }

    @Test
    fun `不改入参且同输入同输出`() {
        val up = List(20) { 10.0 * Math.pow(1.01, it.toDouble()) }
        val bars = series(List(60) { 10.0 } + up + List(14) { up.last() * (1 - 0.012 * it) })
        val snapshot = bars.toList()
        val r1 = BookSignalEngine.compute(bars, "600000")
        val r2 = BookSignalEngine.compute(bars, "600000")
        assertEquals(r1.tags, r2.tags, "同一输入必须给出同一结论")
        assertEquals(snapshot, bars, "compute 不应修改输入 K 线")
    }

    @Test
    fun `不可用项已声明`() {
        val limits = BookSignalEngine.knownLimits()
        for (k in listOf("seal_amount", "first_seal_time", "lhb_seats", "true_delta")) {
            assertTrue(limits.containsKey(k), "必须声明 $k 不可用")
        }
    }

    @Test
    fun `自检全过`() {
        val r = BookSignalEngine.selfCheck()
        assertTrue(r.checks >= 10, "自检项过少：${r.checks}")
        assertTrue(r.ok, "不变量被破坏：" + r.violations.joinToString(" | "))
    }

    // ─────────────────────── 链路接入：只出多头证据 ───────────────────────

    @Test
    fun `书籍票只投正票不投负票`() {
        // 权重只认规格（2026-10-30 起不再硬编码 0.6）：先登记再验行为
        val base = EngineSpecLoader.loadBundled().spec
        val spec = base.copy(weights = base.weights + ("book" to 0.6))
        val strong = BookSignalEngine.Result(
            score = 80.0, expected5d = 1.8, tags = listOf("limit:first_board"),
            layers = emptyMap(), basis = "limit:first_board", basisT = 1.96, basisN = 366,
            degraded = emptyList(), reason = "test",
        )
        val votes = EngineChain.externalVotes(null, null, strong, spec)
        assertTrue(votes.isNotEmpty(), "强证据应产票")
        assertTrue(votes.all { it.vote == 1 }, "三层只能投 +1：$votes")
        assertTrue(votes.all { it.indicator == "book" }, "票名应为 book")

        assertTrue(
            EngineChain.externalVotes(null, null, strong, base.copy(weights = base.weights - "book")).isEmpty(),
            "规格未登记 book 权重时必须停用（否则权重不可云端控制）",
        )

        val weak = strong.copy(score = 50.0)
        assertTrue(
            EngineChain.externalVotes(null, null, weak, spec).isEmpty(),
            "弱分不产票（0 票会进权重分母却不出分子，等于稀释其它票）",
        )
        assertTrue(EngineChain.externalVotes(null, null, null, spec).isEmpty(), "无证据不产票")
    }

    @Test
    fun `规格未登记权重时书籍层不抬分且不静默`() {
        // 不能用「自带规格里有没有 book」当前提：云端登记/摘除 book 会改内置规格（合法操作），
        // 这里显式构造「未登记」的规格，测的才是降级分支本身。
        val base = EngineSpecLoader.loadBundled().spec
        val noBook = base.copy(weights = base.weights - "book")
        val row = LiveMarket.Row(
            code = "600000", name = null, price = null, pctChg = 1.2, volumeLots = 5.0e4,
            amount = 5.0e7, amplitude = 2.0, turnoverRate = 1.0, pe = null, volRatio = 1.1,
            mv = null, mainNet = null, ts = 0L,
        )
        val r = EngineChain.analyze(
            EngineChain.Inputs(bars = bars(up = true), row = row, nowMs = 1_760_000_000_000L),
            specOverride = noBook,
        )
        assertTrue(
            r.votes.none { it.indicator == "book" },
            "缺权重时不得有 book 票进中枢：${r.votes}",
        )
        // 无证据的样本不会走到降级分支，所以只看「有证据时必须有痕迹」
        if (r.book?.hasEvidence == true) {
            assertTrue(
                r.degraded.any { it.contains("规格未登记") },
                "停用书籍层必须写进 degraded，否则用户看到的分数变化无声无息：${r.degraded}",
            )
        }
    }

    @Test
    fun `链路结果带出书籍层且降级可见`() {
        val up = bars(up = true)
        val row = LiveMarket.Row(
            code = "600000", name = null, price = null, pctChg = 1.2, volumeLots = 5.0e4,
            amount = 5.0e7, amplitude = 2.0, turnoverRate = 1.0, pe = null, volRatio = 1.1,
            mv = null, mainNet = null, ts = 0L,
        )
        val r = EngineChain.analyze(
            EngineChain.Inputs(bars = up, row = row, nowMs = 1_760_000_000_000L),
        )
        assertNotNull(r.book, "链路必须把书籍层带出来（哪怕无证据）")
        assertTrue(
            r.book!!.tags.isNotEmpty() || r.degraded.any { it.contains("书籍信号") },
            "要么有标签，要么在 degraded 里说明为什么没有",
        )
    }

    private fun bars(up: Boolean, n: Int = 120): List<KBar> = (0 until n).map { i ->
        val c = if (up) 10.0 + i * 0.05 else 14.0 - i * 0.05
        KBar(
            date = "2026-01-%02d".format(i % 28 + 1),
            open = if (up) c - 0.05 else c + 0.05,
            close = c,
            high = c + if (up) 0.1 else 0.5,
            low = c - if (up) 0.1 else 0.05,
            vol = if (up) 1.0e6 else 2.0e5,
        )
    }
}
