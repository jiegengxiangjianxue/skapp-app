package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地引擎池装配的方向性验证。
 *
 * 装配层是把「实时快照 + 本地筛股」翻译成界面认识的 payload，**翻译错了界面上看不出来**：
 * 强势市场配上防守建议、不可得字段填 0 冒充「连板 0 家」、把不可买的票标成 buy，
 * 这些都长得像正常数据。所以这里逐条钉住。
 */
class EngineFeedLiveTest {

    private fun engineResult(bull: Int = 8, bear: Int = 2, neutral: Int = 2) = LocalEngineResult(
        factors = null,
        engines = listOf(
            EngineScore("trend", 72.0, "bullish", listOf("MA20 上穿 MA60")),
            EngineScore("macd", 38.0, "bearish", listOf("柱状线缩短")),
            EngineScore("volume", 55.0, "neutral", emptyList()),
        ),
        composite = 68.0,
        nValid = 12,
        barsUsed = 120,
        bullCount = bull,
        bearCount = bear,
        neutralCount = neutral,
    )

    private fun screened(
        code: String = "600000",
        score: Double = 68.0,
        buyable: Boolean = true,
    ) = LocalScreener.Screened(
        code = code,
        name = "测试股",
        price = 10.0,
        pctChg = 2.0,
        volRatio = 1.5,
        turnover = 1.2e8,
        pe = 20.0,
        mv = 1e10,
        engine = engineResult(),
        centralScore = score,
        resonanceRatio = 0.75,
        detailStatus = "ok",
        buyable = buyable,
    )

    private fun pulse(label: String) = LivePulse(
        ts = 1_700_000_000_000L,
        total = 5000,
        up = 3800,
        down = 1000,
        flat = 200,
        limitUp = 50,
        limitDown = 3,
        medianPct = 1.2,
        breadth = 0.79,
        totalAmount = 9.0e11,
        mainNetTotal = 1.2e10,
        phaseScore = if (label == "强势") 62.0 else -55.0,
        label = label,
        inflow = emptyList(),
        outflow = emptyList(),
    )

    @Test
    fun `装配结果要标成本地实时并如实列出缺项`() {
        val feeds = EngineFeedLive.assemble(pulse("强势"), listOf(screened()), emptyList())
        assertEquals("live", feeds.source)
        assertNotNull(feeds.engineV5)
        assertTrue(feeds.missing.any { it.contains("cycle_pool") })
        assertTrue(feeds.missing.any { it.contains("rps_rotation") })
        assertTrue(feeds.missing.any { it.contains("ai_pick") })
        // 云端独有的产物不许伪造
        assertEquals(null, feeds.cyclePool)
        assertEquals(null, feeds.rpsRotation)
        assertEquals(null, feeds.aiPick)
    }

    @Test
    fun `引擎投票方向要跟信号一致`() {
        val feeds = EngineFeedLive.assemble(pulse("强势"), listOf(screened()), emptyList())
        val votes = feeds.engineV5!!.top.first().votes
        assertEquals(1, votes.first { it.indicator == "trend" }.vote)
        assertEquals(-1, votes.first { it.indicator == "macd" }.vote)
        assertEquals(0, votes.first { it.indicator == "volume" }.vote)
        assertEquals("MA20 上穿 MA60", votes.first { it.indicator == "trend" }.why)
    }

    @Test
    fun `强势市场要给进攻而弱势市场要给防守`() {
        val strong = EngineFeedLive.assemble(pulse("强势"), listOf(screened()), emptyList())
        val weak = EngineFeedLive.assemble(pulse("弱势"), listOf(screened()), emptyList())
        assertEquals("offense", strong.decision!!.marketRegime)
        assertEquals("defense", weak.decision!!.marketRegime)
        assertTrue(
            strong.decision!!.positionAdvice.level > weak.decision!!.positionAdvice.level,
            "强势仓位建议不该低于弱势",
        )
        // 弱势时仓位上限要给得下来
        assertTrue(weak.decision!!.positionAdvice.cap < strong.decision!!.positionAdvice.cap)
    }

    @Test
    fun `不可得的相位字段不能填零冒充`() {
        val feeds = EngineFeedLive.assemble(pulse("强势"), listOf(screened()), emptyList())
        val daily = feeds.marketPhase!!.daily.single()
        assertEquals(-1, daily.firstBoard)
        assertEquals(-1, daily.ge2)
        assertEquals(-1, daily.height)
        assertEquals(-1.0, daily.promo)
        assertEquals(-1.0, daily.seal)
        // 但能实时算的必须给出来
        assertTrue(feeds.marketPhase!!.labels["涨停家数"]!!.contains("50"))
        assertTrue(feeds.marketPhase!!.labels["口径"]!!.contains("实时"))
    }

    @Test
    fun `不可买的票只能进回避名单不能给买入动作`() {
        val feeds = EngineFeedLive.assemble(pulse("强势"), listOf(screened(buyable = false)), emptyList())
        val card = feeds.decision!!.cards.single()
        assertEquals("avoid", card.action)
        assertTrue(feeds.decision!!.avoid.contains("600000"))
    }

    @Test
    fun `没有候选时引擎池为空而不是编造`() {
        val feeds = EngineFeedLive.assemble(pulse("强势"), emptyList(), emptyList())
        assertTrue(feeds.engineV5!!.top.isEmpty())
        assertTrue(feeds.decision!!.cards.isEmpty())
        // 相位是市场级的，没有候选也该有
        assertNotNull(feeds.marketPhase)
    }

    @Test
    fun `外围指数要按实时涨跌算情绪`() {
        val indices = listOf(
            mapOf<String, Any?>("name" to "道指", "pctChg" to 0.8),
            mapOf<String, Any?>("name" to "纳指", "pctChg" to 1.2),
            mapOf<String, Any?>("name" to "恒指", "pctChg" to -0.3),
        )
        val feeds = EngineFeedLive.assemble(pulse("强势"), listOf(screened()), indices)
        val g = feeds.globalMarkets!!
        assertEquals(3, g.nIndices)
        assertEquals(3, g.nOk)
        assertTrue(g.sentiment!! > 0, "两涨一跌应当是净偏多")
    }
}
