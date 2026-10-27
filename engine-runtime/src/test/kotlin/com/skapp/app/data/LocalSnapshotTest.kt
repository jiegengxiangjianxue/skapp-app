package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首屏三张卡的本地产出验证。
 *
 * 这层最容易出的错是「没数据时看起来照样正常」：picks 空着、decision 给个中性、
 * market 全 0——界面上都很像"今天没什么机会"。所以断言重点全在**没数据时的态度**。
 */
class LocalSnapshotTest {

    private fun artifact(
        picks: List<PickSignal> = emptyList(),
        observed: List<Map<String, Any?>> = emptyList(),
    ) = StructuredPickArtifact(
        generatedAt = "2026-10-27T10:30:00+08:00",
        calculationTime = "2026-10-27T10:30:00+08:00",
        date = "2026-10-27",
        session = "midday",
        engine = "中枢引擎",
        status = if (picks.isEmpty()) "insufficient" else "ok",
        reason = if (picks.isEmpty()) "达标 0 只" else null,
        picks = picks,
        metadata = mapOf("observed" to observed, "source" to "local", "qualified" to picks.size),
    )

    private fun signal(code: String, score: Double) = PickSignal(
        code = code,
        name = "测试股",
        price = 10.0,
        pctChg = 2.0,
        score = score,
        buyable = true,
        snapshotStatus = "live",
        engine = "中枢引擎",
    )

    private fun pulse(label: String, score: Double) = LivePulse(
        ts = 1_700_000_000_000L,
        total = 5000,
        up = if (score > 0) 3600 else 900,
        down = if (score > 0) 1100 else 3900,
        flat = 300,
        limitUp = if (score > 0) 40 else 2,
        limitDown = if (score > 0) 2 else 60,
        medianPct = if (score > 0) 1.0 else -1.4,
        breadth = if (score > 0) 0.76 else 0.19,
        totalAmount = 8.0e11,
        mainNetTotal = if (score > 0) 1.0e10 else -2.0e10,
        phaseScore = score,
        label = label,
        inflow = emptyList(),
        outflow = emptyList(),
    )

    @Test
    fun `没有本地产物时不编造推荐`() {
        val picks = LocalSnapshot.todayPicks(null)
        assertTrue(picks.signals.isEmpty())
        assertTrue(picks.values.isEmpty())
        assertEquals("local", picks.sources["source"])
        assertEquals("pending", picks.sources["status"])
    }

    @Test
    fun `本地产物要映射进 signals 而观察位进 values`() {
        val picks = LocalSnapshot.todayPicks(
            artifact(
                picks = listOf(signal("600000", 72.0)),
                observed = listOf(mapOf("code" to "600001", "name" to "观察股", "score" to 48.0)),
            ),
        )
        assertEquals(1, picks.signals.size)
        assertEquals("600000", picks.signals.first().code)
        assertEquals(1, picks.values.size)
        assertEquals("600001", picks.values.first().code)
        // 观察位必须说明「为什么没进推荐」
        assertTrue(picks.values.first().reason!!.contains("未达达标线"))
        assertEquals("2026-10-27", picks.date)
        assertEquals("midday", picks.session)
    }

    @Test
    fun `实时行情不可达时三张卡都要明说而不是给中性结论`() {
        val market = LocalSnapshot.market(null)
        assertEquals("unavailable", market.market["status"])
        assertTrue(market.market["note"].toString().contains("云端"))

        val decision = LocalSnapshot.decision(null, emptyList())
        assertEquals(null, decision.marketRegime)
        assertTrue(decision.regimeDetail!!.contains("不可达"))
        assertTrue(decision.cards.isEmpty())
    }

    @Test
    fun `市场快照要带实时涨跌与资金`() {
        val market = LocalSnapshot.market(pulse("强势", 62.0))
        assertEquals("local", market.market["source"])
        assertEquals(3600, market.market["gainers"])
        assertEquals(40, market.market["limitUp"])
        @Suppress("UNCHECKED_CAST")
        val phase = market.phase as Map<String, Any?>
        assertEquals("强势", phase["label"])
    }

    @Test
    fun `决策的市场状态与仓位要跟着实时相位走`() {
        val strong = LocalSnapshot.decision(pulse("强势", 62.0), emptyList())
        val weak = LocalSnapshot.decision(pulse("弱势", -58.0), emptyList())
        assertEquals("offense", strong.marketRegime)
        assertEquals("defense", weak.marketRegime)
        val strongLevel = strong.positionAdvice["level"] as Double
        val weakLevel = weak.positionAdvice["level"] as Double
        assertTrue(strongLevel > weakLevel, "强势仓位不该低于弱势")
        assertTrue(weakLevel < 0.25, "弱势该给出很低的仓位，实际 $weakLevel")
    }

    @Test
    fun `不可买的票只进回避名单`() {
        val s = LocalScreener.Screened(
            code = "600009",
            name = "涨停股",
            price = 12.0,
            pctChg = 9.9,
            volRatio = 2.0,
            turnover = 1.0,
            pe = 20.0,
            mv = 1.0,
            engine = null,
            centralScore = 80.0,
            detailStatus = "ok",
            buyable = false,
        )
        val d = LocalSnapshot.decision(pulse("强势", 62.0), listOf(s))
        assertEquals("avoid", d.cards.single().action)
        assertTrue(d.avoid.contains("600009"))
    }
}
