package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 板块本地聚合的方向性验证。
 *
 * 这类聚合错了界面上看不出来（板块涨幅差个零点几、涨停少算几家，都是「看起来正常」），
 * 所以逐条钉：未映射不许进板块、涨停按各板上限算、停牌不计入均值。
 */
class LocalSectorsTest {

    private fun row(
        code: String,
        pct: Double?,
        amount: Double = 1.0e8,
        name: String = "股$code",
    ) = LiveMarket.Row(
        code = code,
        name = name,
        price = 10.0,
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
    )

    private val map = mapOf(
        "600000" to "银行",
        "600036" to "银行",
        "300001" to "创业板股",
        "000001" to "银行",
    )

    @Test
    fun `未映射的股票不许塞进任何板块`() {
        val res = LocalSectors.aggregate(
            listOf(row("600000", 1.0), row("999999", 5.0)),
            sectorOf = { map[it] },
        )
        assertEquals(1, res.mapped)
        assertEquals(1, res.unmapped)
        assertEquals(1, res.rows.size, "未映射的票不该凭空生出一个板块")
        assertTrue(res.rows.none { it.name.contains("未知") })
        assertEquals(0.5, res.coverage)
    }

    @Test
    fun `板块涨幅取平均而停牌股不参与`() {
        val res = LocalSectors.aggregate(
            listOf(row("600000", 2.0), row("600036", 4.0), row("000001", null)),
            sectorOf = { map[it] },
        )
        val bank = res.rows.single()
        assertEquals(3, bank.n, "成分数要把停牌股算进股本，只是不参与均值")
        assertEquals(3.0, bank.chg1d!!)   // (2+4)/2，不能被 null 当 0 拉低到 2.0
    }

    @Test
    fun `涨停按各板块涨跌幅上限判定`() {
        val res = LocalSectors.aggregate(
            listOf(
                row("600000", 9.9),    // 主板 10% 涨停
                row("300001", 19.9),   // 创业板 20% 涨停
                row("600036", 9.0),    // 主板未涨停
            ),
            sectorOf = { map[it] },
        )
        val bank = res.rows.first { it.name == "银行" }
        val cyb = res.rows.first { it.name == "创业板股" }
        assertEquals(1, bank.limitUp)
        assertEquals(1, cyb.limitUp, "创业板 19.9% 必须算涨停，不能一律按 9.6% 判")
    }

    @Test
    fun `板块成交额与龙头要跟上`() {
        val res = LocalSectors.aggregate(
            listOf(
                row("600000", 1.0, 1.0e8, "浦发"),
                row("600036", 5.0, 3.0e8, "招商"),
            ),
            sectorOf = { map[it] },
        )
        val bank = res.rows.single()
        assertEquals(4.0e8, bank.heat, 1.0)
        assertEquals("招商", bank.leader)
        assertEquals(5.0, bank.leaderPct)
        assertEquals(2, bank.up)   // 两只都涨
    }

    @Test
    fun `板块按成交额降序`() {
        val res = LocalSectors.aggregate(
            listOf(row("600000", 1.0, 1.0e8), row("300001", 1.0, 9.0e8)),
            sectorOf = { map[it] },
        )
        assertEquals("创业板股", res.rows.first().name)
    }

    @Test
    fun `实时行情不可达时看板要明说而不是给空看板`() {
        val board = LocalSectors.boardMap(null, null)
        assertEquals("insufficient", board["status"])
        assertTrue(board["reason"].toString().contains("未使用云端归档"))
    }

    @Test
    fun `看板的涨停池与涨跌榜来自实时快照`() {
        val rows = listOf(
            row("600000", 10.0),
            row("600001", -9.98),
            row("600002", 1.5),
        )
        val pulse = LivePulse.of(rows)
        val res = LocalSectors.aggregate(rows, sectorOf = { map[it] ?: "其他" })
        val board = LocalSectors.boardMap(res, pulse, rows)
        assertEquals("ok", board["status"])
        assertEquals("local", board["source"])
        @Suppress("UNCHECKED_CAST")
        val limitUp = board["limit_up"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val limitDown = board["limit_down"] as List<Map<String, Any?>>
        assertEquals(listOf("600000"), limitUp.map { it["code"] })
        assertEquals(listOf("600001"), limitDown.map { it["code"] })
        // 指数不在这里编
        assertEquals(emptyList<Map<String, Any?>>(), board["indices"])
    }

    @Test
    fun `宽度卡片不给结论时说明不可得`() {
        assertNull(LocalSectors.breadth(null)["total"])
        assertEquals("unavailable", LocalSectors.breadth(null)["status"])
        val b = LocalSectors.breadth(LivePulse.of(listOf(row("600000", 3.0))))
        assertEquals("ok", b["status"])
        assertEquals(1, b["total"])
    }

    @Test
    fun `板块快照要标明概念板块本地不可得`() {
        val res = LocalSectors.aggregate(listOf(row("600000", 1.0)), sectorOf = { map[it] })
        val snap = LocalSectors.snapshot(res)
        assertEquals("ok", snap.status)
        assertEquals(1, snap.industries.size)
        assertTrue(snap.degradedReason!!.contains("概念"))
        assertTrue(snap.concepts.isEmpty())
    }

    // ── 止跌结构（收盘位置 + 长下影）────────────────────────────

    /** 造一根带 high/low/open 的当根盘口：用于结构判定。 */
    private fun barRow(
        code: String,
        price: Double,
        open: Double,
        high: Double,
        low: Double,
        pct: Double,
    ) = LiveMarket.Row(
        code = code, name = "股$code", price = price, pctChg = pct,
        volumeLots = 1.0, amount = 1.0e8, amplitude = 1.0, turnoverRate = 1.0,
        pe = 10.0, volRatio = 1.0, mv = 1.0e9, mainNet = 0.0, ts = 1_700_000_000_000L,
        high = high, low = low, open = open, prevClose = open,
    )

    @Test
    fun `收在区间上沿且长下影 - 判止跌雏形`() {
        // 每只：low 9.0 high 10.0，收盘 9.9（收位 90%），下影从 9.5 拉到 9.9 → 长下影，当日收阴
        val rows = (1..4).map { i ->
            barRow("60000$i", price = 9.9, open = 9.95, high = 10.0, low = 9.0, pct = -0.5)
        }
        val res = LocalSectors.aggregate(rows, sectorOf = { "科技" })
        val r = res.rows.single()
        assertEquals("止跌雏形", r.stopping)
        assertTrue(r.closePos!! >= 0.85)
        assertEquals(4, r.longShadowN)
    }

    @Test
    fun `收在区间下沿 - 判单边下杀`() {
        // low 9.0 high 10.0，收盘 9.05（收位 5%）：这就是「阴线实体、贴着最低收」
        val rows = (1..3).map { i ->
            barRow("60001$i", price = 9.05, open = 10.0, high = 10.0, low = 9.0, pct = -9.5)
        }
        val r = LocalSectors.aggregate(rows, sectorOf = { "科技" }).rows.single()
        assertEquals("单边下杀", r.stopping)
        assertTrue(r.closePos!! <= 0.1)
    }

    @Test
    fun `缺 high-low 时不猜结构`() {
        val res = LocalSectors.aggregate(listOf(row("600000", 1.0)), sectorOf = { "银行" })
        val r = res.rows.single()
        assertNull(r.closePos)
        assertNull(r.stopping, "没有当根区间就不该给结构结论")
    }

    @Test
    fun `板块强度四维加权且缺项重新归一`() {
        // 4 只全涨（均值 1.625%）+ 无涨停 + 无 high/low（结构维缺席）
        //   涨幅 50+1.625/3*50 = 77.08 × 0.35
        //   广度 100 × 0.30
        //   涨停 0/4 = 0 × 0.15
        //   结构 缺项不计入权重 → 总权重 0.80
        val res = LocalSectors.aggregate(
            listOf(row("600000", 2.0), row("600036", 1.0), row("000001", 3.0), row("601398", 0.5)),
            sectorOf = { "银行" },
        )
        val s = res.rows.single().strength
        assertEquals(71.2, s!!, 0.6, "缺结构维时按 0.35/0.30/0.15 重新归一，不能当 0 分拉低")
    }

    @Test
    fun `全员下跌的板块强度应远低于中性`() {
        val res = LocalSectors.aggregate(
            listOf(row("600000", -2.0), row("600036", -3.0), row("000001", -1.0)),
            sectorOf = { "银行" },
        )
        val s = res.rows.single().strength!!
        assertTrue(s < 20.0, "全员下跌 + 广度 0，强度应变 $s")
    }

    @Test
    fun `没有任何可算维度时强度为 null`() {
        val res = LocalSectors.aggregate(listOf(row("600000", null)), sectorOf = { "银行" })
        assertNull(res.rows.single().strength, "没数据就不给强度，不能默认 50")
    }
}
