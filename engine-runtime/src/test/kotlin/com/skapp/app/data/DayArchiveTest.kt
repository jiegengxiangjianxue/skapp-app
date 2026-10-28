package com.skapp.app.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本地日级存档 + 连板/晋级/板块动量计算。
 *
 * 这一组是整个「连板高度/晋级率/板块周期」链路的地基：存档一旦写脏（写进停牌日的空快照、
 * 或被盘中数据覆盖终版），后面所有统计都是错的，而且**错得看不出来**——
 * 连板高度会莫名从 5 变成 1，晋级率会变成 0。
 */
class DayArchiveTest {

    // ── 存档读写 ─────────────────────────────────────────

    private fun tempDir(): File = File.createTempFile("days", "").let {
        it.delete(); it.mkdirs(); it
    }

    /** 2026-10-27（周二）10:00 CST：存档只接受「当日盘中」的快照，测试统一用这个时点。 */
    private val now: Long = java.util.Calendar.getInstance(
        java.util.TimeZone.getTimeZone("Asia/Shanghai"),
    ).apply { set(2026, 9, 27, 10, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    private fun row(code: String, pct: Double, price: Double = 10.0, vol: Double = 1000.0, name: String = "股$code") =
        LiveMarket.Row(
            code = code, name = name, price = price, pctChg = pct, volumeLots = vol,
            amount = 1.0e8, amplitude = 1.0, turnoverRate = 1.0, pe = 10.0, volRatio = 1.0,
            mv = 1.0e9, mainNet = 0.0, ts = now,
            high = price, prevClose = price / (1 + pct / 100.0),
        )

    private fun manyRows(n: Int = 200, prefix: String = "60", pct: Double = 1.0) =
        (0 until n).map { row("$prefix%04d".format(it), pct, price = 10.0 + it * 0.01) }

    @Test
    fun `写入再读出要一模一样`() {
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        val rows = manyRows(150).toMutableList()
        // 让其中一只涨停（昨收 10 → 涨停价 11）
        rows[0] = row("600000", 10.0, price = 11.0)
        runCapture(rows, final = true, now = now)
        val days = MarketDayArchive.dayKeys()
        assertEquals(1, days.size)
        val snap = MarketDayArchive.load(days.first())
        assertNotNull(snap)
        assertEquals(150, snap.entries.size)
        assertTrue(snap.final)
        assertEquals(setOf("600000"), snap.limitUpCodes())
        assertEquals(11.0, snap.closeOf("600000"))
    }

    @Test
    fun `v3 存档要带上最高最低开盘`() {
        // 退出纪律（长下影/区间位置）全靠当根 high-low；写过一次读出来就没有，
        // 等于历史全是「只有收盘价」，那价格位置判断只能降级说明。
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        val rows = manyRows(150).toMutableList()
        rows[0] = row("600000", 1.0, price = 10.5)
            .copy(high = 10.9, low = 9.8, open = 10.0)
        runCapture(rows, final = true, now = now)
        val snap = MarketDayArchive.load(MarketDayArchive.dayKeys().first())!!
        assertTrue(snap.hasRangeInfo, "v3 写出的存档必须标记带区间信息")
        val e = snap.entries.first { it.code == "600000" }
        assertEquals(10.9, e.high)
        assertEquals(9.8, e.low)
        assertEquals(10.0, e.open)
        // 其它票没给 high/low：可以是 null，但绝不能填成收盘价假装有
        val other = snap.entries.first { it.code != "600000" }
        assertNull(other.open)
    }

    @Test
    fun `快照太少说明是接口故障，不写脏数据`() {
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        // 只有 5 只（接口半挂），绝不能当成「今天全市场只有 5 只票」存进去
        assertTrue(!runCapture(manyRows(5)))
        assertEquals(0, MarketDayArchive.storedDays())
    }

    private fun runCapture(rows: List<LiveMarket.Row>, final: Boolean = false, now: Long = this.now): Boolean =
        kotlinx.coroutines.runBlocking { MarketDayArchive.capture(rows, final = final, now = now) }

    @Test
    fun `终版写过之后不再被盘中数据覆盖`() {
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        runCapture(manyRows(150), final = true, now = now)
        val date = MarketDayArchive.dayKeys().first()
        val before = MarketDayArchive.load(date)!!
        // 又来一轮盘中写入（同一分钟），必须被拒
        val wrote = runCapture(manyRows(150, pct = 5.0), final = false, now = now + 1000)
        assertTrue(!wrote)
        assertEquals(before.entries.size, MarketDayArchive.load(date)!!.entries.size)
        assertTrue(MarketDayArchive.load(date)!!.final)
    }

    @Test
    fun `休市日不存档`() {
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        // 2026-10-25 是周日：接口仍会返回上周五的盘口，写进「今天」会凭空多出一个交易日
        val sunday = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, 9, 25, 11, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val rows = manyRows(150).map { it.copy(ts = sunday) }
        assertTrue(!runCapture(rows, now = sunday), "周日不该写入存档")
        assertEquals(0, MarketDayArchive.storedDays())
    }

    @Test
    fun `盘前拿到的上一交易日收盘价不能记成今天`() {
        MarketDayArchive.init(tempDir())
        MarketDayArchive.clearAll()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, 9, 27, 9, 5, 0); set(java.util.Calendar.MILLISECOND, 0)   // 周二 09:05 盘前
        }
        val now = cal.timeInMillis
        val yesterdayTs = now - 24 * 3600_000L
        val rows = manyRows(150).map { it.copy(ts = yesterdayTs) }
        assertTrue(!runCapture(rows, now = now), "盘前快照的 ts 还是昨天，不能记成今天的行情")
        assertEquals(0, MarketDayArchive.storedDays())
    }

    @Test
    fun `v1 老存档还能读，封板率退化为不可得而不是百分百`() {
        val dir = tempDir()
        MarketDayArchive.init(dir)
        MarketDayArchive.clearAll()
        // 手写一份 v1 格式（没有「摸过涨停」这一位）
        val f = File(dir, "market_days/2026-10-20.dat.gz")
        f.parentFile.mkdirs()
        java.util.zip.GZIPOutputStream(f.outputStream()).use { out ->
            out.write(
                ("#v1\t2026-10-20\t0\t1\n" +
                    "R\t600001\t10.0000\t100000000.0000\t1\t11.0000\n" +
                    "R\t600002\t5.0000\t100000000.0000\t0\t10.5000\n").toByteArray(),
            )
        }
        val snap = MarketDayArchive.load("2026-10-20")
        assertNotNull(snap)
        assertEquals(2, snap.entries.size)
        val s = LimitBoardCalc.compute(listOf(snap))!!
        assertEquals(1, s.limitUp)
        assertEquals(0, s.broken, "v1 没有炸板信息，不能凭空判出炸板")
        assertNull(s.seal, "没有炸板信息时封板率是不可得，不是 100%")
    }

    // ── 连板 / 晋级 ───────────────────────────────────────

    private fun day(date: String, limitUp: List<String>, all: List<String> = emptyList()): DayMarketSnapshot {
        val codes = (all + limitUp).distinct()
        return DayMarketSnapshot(
            date = date,
            ts = 0,
            final = true,
            entries = codes.map {
                DayMarketSnapshot.Entry(it, if (it in limitUp) 10.0 else 1.0, 1e8, it in limitUp, 10.0)
            },
        )
    }

    @Test
    fun `连板高度要按连续天数算而不是按今日涨停数`() {
        val days = listOf(
            day("2026-10-21", listOf("600001", "600002")),
            day("2026-10-22", listOf("600001")),
            day("2026-10-23", listOf("600001", "600003")),
        )
        val s = LimitBoardCalc.compute(days)!!
        assertEquals(3, s.height, "600001 连了三天，高度应是 3")
        assertEquals(listOf("600001"), s.heightCodes)
        assertEquals(2, s.limitUp)
        assertEquals(1, s.firstBoard, "600003 昨天没涨停 = 首板")
        assertEquals(1, s.ge2, "600001 昨天也涨停 = 连板")
        // 昨日（10-22）只有 600001 涨停，今天它继续涨停 → 晋级率 100%
        assertEquals(1.0, s.promo!!, 1e-9)
    }

    @Test
    fun `只有一天数据时连板相关字段不可得`() {
        val s = LimitBoardCalc.compute(listOf(day("2026-10-23", listOf("600001"))))!!
        assertEquals(-1, s.firstBoard)
        assertEquals(-1, s.ge2)
        assertNull(s.promo, "「昨天没数据」不等于「昨天没涨停」")
        assertEquals(1, s.height)
    }

    @Test
    fun `晋级率不会被昨日零涨停除爆`() {
        val days = listOf(day("2026-10-22", emptyList(), all = listOf("600010")), day("2026-10-23", listOf("600001")))
        val s = LimitBoardCalc.compute(days)!!
        assertNull(s.promo)
        assertEquals(1, s.firstBoard)
        assertEquals(1, s.height, "昨天那只没涨停，今天的板只是首板，高度 1")
    }

    @Test
    fun `昨日涨停今日表现要看这批票的真实涨跌`() {
        val yesterday = DayMarketSnapshot(
            "2026-10-22", 0, true,
            listOf(
                DayMarketSnapshot.Entry("600001", 10.0, 1e8, true, 10.0),
                DayMarketSnapshot.Entry("600002", 10.0, 1e8, true, 10.0),
            ),
        )
        val today = DayMarketSnapshot(
            "2026-10-23", 0, true,
            listOf(
                DayMarketSnapshot.Entry("600001", 10.0, 1e8, true, 11.0),   // 连板
                DayMarketSnapshot.Entry("600002", -6.0, 1e8, false, 9.4),   // 炸了
            ),
        )
        val s = LimitBoardCalc.compute(listOf(yesterday, today))!!
        assertEquals(2.0, s.ydayLimitUpAvgPct!!, 1e-9)
    }

    // ── 板块历史动量 ─────────────────────────────────────

    @Test
    fun `板块 5 日动量要复利累乘而不是简单相加`() {
        val days = (0 until 5).map { i ->
            DayMarketSnapshot(
                "2026-10-2$i", 0, true, emptyList(),
                listOf(DayMarketSnapshot.SectorDay("半导体", 2.0, 40, 5, 1e10)),
            )
        }
        val m = SectorHistoryCalc.momentum(days).first()
        val expected = ((1.02 * 1.02 * 1.02 * 1.02 * 1.02) - 1) * 100
        assertEquals(expected, m.ret5d!!, 1e-6)
        assertEquals(5, m.upStreak)
        assertNull(m.ret20d, "只有 5 天，20 日动量必须留空")
    }

    @Test
    fun `天数不足时板块动量返回空而不是零`() {
        val one = listOf(DayMarketSnapshot("2026-10-27", 0, true, emptyList(),
            listOf(DayMarketSnapshot.SectorDay("半导体", 2.0, 40, 5, 1e10))))
        assertTrue(SectorHistoryCalc.momentum(one).isEmpty())
    }
}
