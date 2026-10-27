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
}
