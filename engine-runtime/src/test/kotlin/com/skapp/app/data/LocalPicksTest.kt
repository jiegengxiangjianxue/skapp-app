package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 盘前/午盘本地推股产出层的行为约束。
 *
 * 这里不测「某只票该不该买」（那是策略问题），只钉住三件事：
 *   1) 达标才出：评分不足 / 涨停附近 / K 线不足，一律不进 picks，不伪装成推荐；
 *   2) 未达标要可见但不误导：只能以 metadata.observed 的「观察」形式出现；
 *   3) 口径必须可追溯：盘前卡要标明剔除了当日未完成日 K，否则等于拿未来数据推过去。
 */
class LocalPicksTest {

    private fun row(
        code: String,
        score: Double?,
        pct: Double = 2.0,
        buyable: Boolean = true,
        status: String = "ok",
        sector: String? = null,
        riskFlag: String? = null,
    ) = LocalScreener.Screened(
        code = code,
        name = "票$code",
        price = 10.0,
        pctChg = pct,
        volRatio = 1.8,
        turnover = 1.0e8,
        pe = 20.0,
        mv = 5.0e10,
        engine = null,
        centralScore = score,
        resonanceRatio = 0.6,
        centralSummary = null,
        detailStatus = status,
        reason = if (status == "ok") null else "K线不足，无法本地计算",
        buyable = buyable,
        sector = sector,
        riskFlag = riskFlag,
    )

    @Test
    fun `达标的票才进 picks`() {
        val artifact = LocalPicks.build(
            screened = listOf(row("600001", 62.0), row("000002", 71.5, pct = 4.2)),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:30:00+08:00",
        )
        assertEquals("ok", artifact.status)
        assertEquals(2, artifact.picks.size)
        // 按 centralScore 降序
        assertEquals("000002", artifact.picks.first().code)
        assertTrue(artifact.picks.all { it.score != null && it.score!! >= LocalPicks.MIN_SCORE })
        assertEquals("midday", artifact.session)
    }

    @Test
    fun `名单容量是整个 MAX_PICKS 而不是前五只`() {
        // 板块各不同：绕开每板块 2 只的上限，单独验证名单容量本身。
        // 曾经只有前 5 只算趋势，界面又写着「仅名单前几只算趋势」，被读成名单只出 5 只。
        val rows = (1..12).map { i ->
            row("6000%02d".format(i), score = 90.0 - i, sector = "板块$i")
        }
        val artifact = LocalPicks.build(
            screened = rows,
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:30:00+08:00",
        )
        assertEquals(LocalPicks.MAX_PICKS, artifact.picks.size)
        assertTrue(artifact.picks.size > 5)
    }

    @Test
    fun `未达标的票只进 observed 不进 picks`() {
        val artifact = LocalPicks.build(
            screened = listOf(row("600001", 48.0), row("600002", 62.0)),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:30:00+08:00",
        )
        assertEquals(listOf("600002"), artifact.picks.map { it.code })
        val observed = artifact.metadata["observed"] as List<*>
        val codes = observed.mapNotNull { (it as Map<*, *>)["code"]?.toString() }
        assertEquals(listOf("600001"), codes)
    }

    @Test
    fun `涨停附近不给推荐即使分数很高`() {
        val artifact = LocalPicks.build(
            screened = listOf(
                row("600001", 88.0, pct = 9.7, buyable = false),
                row("600002", 70.0, pct = 3.0, buyable = true),
            ),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:30:00+08:00",
        )
        assertEquals(listOf("600002"), artifact.picks.map { it.code })
    }

    @Test
    fun `K线不足时如实说不足而不是给空推荐`() {
        val artifact = LocalPicks.build(
            screened = listOf(row("600001", null, status = "insufficient")),
            session = LocalPicks.Session.PREMARKET,
            calcTime = "2026-10-28T09:10:00+08:00",
        )
        assertTrue(artifact.picks.isEmpty())
        assertEquals("insufficient", artifact.status)
        assertTrue(artifact.reason.orEmpty().contains("K 线不足"))
    }

    @Test
    fun `盘前口径默认剔除当日未完成日K并可追溯`() {
        val pre = LocalPicks.build(
            screened = listOf(row("600001", 61.0)),
            session = LocalPicks.Session.PREMARKET,
            calcTime = "2026-10-28T09:10:00+08:00",
        )
        assertEquals(true, pre.metadata["droppedLastBar"])
        assertTrue(pre.metadata["basis"].toString().contains("上一交易日"))

        val mid = LocalPicks.build(
            screened = listOf(row("600001", 61.0)),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T11:20:00+08:00",
        )
        assertFalse(mid.metadata["droppedLastBar"] as Boolean)
    }

    @Test
    fun `会话归属按交易时段划分`() {
        assertEquals(LocalPicks.Session.PREMARKET, LocalPicks.sessionFor(MarketHours.Phase.PRE_OPEN))
        assertEquals(LocalPicks.Session.PREMARKET, LocalPicks.sessionFor(MarketHours.Phase.AUCTION_OPEN))
        assertEquals(LocalPicks.Session.MIDDAY, LocalPicks.sessionFor(MarketHours.Phase.MORNING))
        assertEquals(LocalPicks.Session.MIDDAY, LocalPicks.sessionFor(MarketHours.Phase.LUNCH_BREAK))
        assertEquals(LocalPicks.Session.MIDDAY, LocalPicks.sessionFor(MarketHours.Phase.AFTERNOON))
        // 收盘后/休市不生成新卡（存档仍可看）
        assertEquals(null, LocalPicks.sessionFor(MarketHours.Phase.AFTER_HOURS))
        assertEquals(null, LocalPicks.sessionFor(MarketHours.Phase.CLOSED_DAY))
    }

    @Test
    fun `板块强度能改变推股顺序`() {
        // 70 分在弱板块（20）→ 64；62 分在强板块（100）→ 72
        val weakSector = row("600001", 70.0).copy(
            sector = "弱板块",
            sectorStrength = 20.0,
            effectiveScore = LocalScreener.effectiveScoreOf(70.0, 20.0),
        )
        val strongSector = row("600002", 62.0).copy(
            sector = "强板块",
            sectorStrength = 100.0,
            effectiveScore = LocalScreener.effectiveScoreOf(62.0, 100.0),
        )
        val artifact = LocalPicks.build(
            screened = listOf(weakSector, strongSector),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:00:00+08:00",
        )
        assertEquals(
            listOf("600002", "600001"),
            artifact.picks.map { it.code },
            "推股卡片必须按 effectiveScore 排：这里再按 centralScore 排一次会把筛选页的加权盖掉",
        )
    }

    @Test
    fun `推股理由写明板块与强度`() {
        val s = row("600001", 70.0).copy(
            sector = "白酒",
            sectorStrength = 72.0,
            effectiveScore = LocalScreener.effectiveScoreOf(70.0, 72.0),
        )
        val artifact = LocalPicks.build(
            screened = listOf(s),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-28T10:00:00+08:00",
        )
        val why = artifact.picks.single().why
        assertTrue(why.any { it.contains("白酒") && it.contains("72") }, "理由里要给出板块与强度：$why")
    }

    @Test
    fun `同一板块最多进两只，超出的记进 metadata 而不是静默丢弃`() {
        val artifact = LocalPicks.build(
            screened = listOf(
                row("600001", 88.0, sector = "半导体"),
                row("600002", 86.0, sector = "半导体"),
                row("600003", 84.0, sector = "半导体"),
                row("000004", 70.0, sector = "银行"),
            ),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-29T10:30:00+08:00",
        )
        val codes = artifact.picks.map { it.code }
        assertEquals(2, codes.count { it in setOf("600001", "600002", "600003") })
        assertTrue(artifact.picks.any { it.code == "000004" })
        val skipped = artifact.metadata["sectorSkipped"] as List<*>
        assertTrue(skipped.any { it.toString().contains("600003") })
        assertEquals(2, artifact.metadata["maxPerSector"])
    }

    @Test
    fun `板块未映射的票不受板块上限限制`() {
        val artifact = LocalPicks.build(
            screened = listOf(
                row("600001", 80.0),
                row("600002", 78.0),
                row("600003", 76.0),
            ),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-29T10:30:00+08:00",
        )
        assertEquals(3, artifact.picks.size)
    }

    @Test
    fun `多个池同时命中能把分低的票排到前面`() {
        val artifact = LocalPicks.build(
            screened = listOf(
                row("600001", 72.0),
                row("600002", 68.0),
            ),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-29T10:30:00+08:00",
            poolHits = mapOf("600002" to 2),
        )
        // 68 分 + 2 池 × 4 分 = 76 > 72 分无池，验证池证据真的参与排序
        assertEquals("600002", artifact.picks.first().code)
        assertEquals(mapOf("600002" to 2), artifact.metadata["poolHits"])
    }

    @Test
    fun `不给池命中时排序与旧版一致`() {
        val artifact = LocalPicks.build(
            screened = listOf(row("600001", 72.0), row("600002", 68.0)),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-29T10:30:00+08:00",
        )
        assertEquals(listOf("600001", "600002"), artifact.picks.map { it.code })
        assertEquals(emptyMap<String, Int>(), artifact.metadata["poolHits"])
    }

    @Test
    fun `引擎风控否决的票再高分也不进推荐`() {
        val artifact = LocalPicks.build(
            screened = listOf(
                row("600001", 90.0, riskFlag = "资金与盘口双弱"),
                row("600002", 66.0),
            ),
            session = LocalPicks.Session.MIDDAY,
            calcTime = "2026-10-29T10:30:00+08:00",
        )
        assertEquals(listOf("600002"), artifact.picks.map { it.code })
        val vetoed = artifact.metadata["riskVetoed"] as List<*>
        assertTrue(vetoed.any { it.toString().contains("600001") })
    }
}
