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
        canonicalScore = score,
        resonanceRatio = 0.6,
        centralSummary = null,
        detailStatus = status,
        reason = if (status == "ok") null else "K线不足，无法本地计算",
        buyable = buyable,
        sector = sector,
        riskFlag = riskFlag,
        dataEvidence = LocalScreener.DataEvidence(1.0, 0.1, "2026-06-30", "cnfin:hfq_factor:fixture", "synthetic_fixture", 1L, Long.MAX_VALUE),
    )

    @Test
    fun `明日早盘缺early结果不能借canonical推荐`() {
        for (session in listOf(LocalPicks.Session.REALTIME, LocalPicks.Session.MORNING)) {
            val result = LocalPicks.build(listOf(row("600001", 99.0)), session, "2026-11-02T20:00:00+08:00")
            assertTrue(result.picks.isEmpty(), session.key)
            assertEquals("insufficient", result.status)
        }
    }

    private fun earlyRow(): LocalScreener.Screened {
        val bars = (0 until 120).map { i ->
            val close = 10.0 + i * 0.08 + kotlin.math.sin(i * 0.7) * 0.15
            KBar(java.time.LocalDate.of(2026, 6, 1).plusDays(i.toLong()).toString(),
                close - 0.03, close, close + 0.1, close - 0.1, 1000.0 + i * 20)
        }
        val context = EarlyVoteEngine.Context(mainNetToday = 1.0e8, industryChg5 = 5.0,
            globalAvgChg = 2.0, indexCloses = bars.mapIndexed { i, bar -> bar.date to (3000.0 + i) })
        return row("600001", 91.0).copy(
            earlyTomorrow = EarlyVoteEngine.tomorrow(bars, context),
            earlyMorning = EarlyVoteEngine.morning(bars, context), bars = bars,
        )
    }

    @Test
    fun `相同bars明日和早盘用不同公式且午盘保持canonical`() {
        val s = earlyRow()
        val tomorrow = kotlin.test.assertNotNull(s.earlyTomorrow)
        val morning = kotlin.test.assertNotNull(s.earlyMorning)
        assertTrue(tomorrow.score >= 55 && morning.score >= 55)
        assertTrue(tomorrow.votes.map { it.indicator } != morning.votes.map { it.indicator })
        val real = LocalPicks.build(listOf(s), LocalPicks.Session.REALTIME, "2026-11-02T20:00:00+08:00")
        val early = LocalPicks.build(listOf(s), LocalPicks.Session.MORNING, "2026-11-02T09:30:00+08:00")
        val mid = LocalPicks.build(listOf(s), LocalPicks.Session.MIDDAY, "2026-11-02T13:00:00+08:00")
        assertEquals(tomorrow.score, real.picks.single().score)
        assertEquals(morning.score, early.picks.single().score)
        assertEquals(91.0, mid.picks.single().score)
        assertEquals("EarlyVoteEngine.tomorrow", real.metadata["scoreSource"])
        assertEquals("EarlyVoteEngine.morning", early.metadata["scoreSource"])
        assertTrue(real.picks.single().why.any { it.contains("0.6/0.4") })
        assertTrue(early.picks.single().why.any { it.contains("0.5/0.5") })
        assertEquals(EarlyVoteEngine.VERSION, real.metadata["engineVersion"])
        val archived = IntradayPicks.toRound(real, "2026-11-02T20:00:00+08:00", "realtime")
        assertEquals(EarlyVoteEngine.VERSION, archived.getString("version"))
        assertEquals("EarlyVoteEngine.tomorrow", archived.getString("scoreSource"))
        assertTrue(archived.has("dataAsOf"))
        assertTrue(archived.isNull("dataAsOf"))
        val quotedAt = java.time.OffsetDateTime.parse("2026-11-02T19:59:00+08:00").toInstant().toEpochMilli()
        val withTime = LocalPicks.build(listOf(s.copy(snapshotAtMs = quotedAt, snapshotSource = "synthetic_fixture")),
            LocalPicks.Session.REALTIME, "2026-11-02T20:00:00+08:00")
        assertEquals("2026-11-02T19:59+08:00",
            IntradayPicks.toRound(withTime, "2026-11-02T20:00:00+08:00", "realtime").getString("dataAsOf"))
    }

    @Test
    fun `early安全输入就绪时不依赖另一评分器产分`() {
        val s = earlyRow().copy(detailStatus = "insufficient", canonicalScore = null, safetyInputsReady = true)
        for (session in listOf(LocalPicks.Session.REALTIME, LocalPicks.Session.MORNING)) {
            assertEquals(1, LocalPicks.build(listOf(s), session, "2026-11-02T20:00:00+08:00").picks.size)
            assertTrue(LocalPicks.build(listOf(s.copy(safetyInputsReady = false)), session,
                "2026-11-02T20:00:00+08:00").picks.isEmpty())
        }
    }

    @Test
    fun `early高分仍服从财务涨停风控新鲜度主板门禁`() {
        val s = earlyRow()
        for (session in listOf(LocalPicks.Session.REALTIME, LocalPicks.Session.MORNING)) {
            val rejected = listOf(s.copy(dataEvidence = null), s.copy(pctChg = 9.6),
                s.copy(riskFlag = "risk"), s.copy(snapshotStatus = "stale"), s.copy(code = "300001"))
            for (candidate in rejected) {
                assertTrue(LocalPicks.build(listOf(candidate), session, "2026-11-02T20:00:00+08:00").picks.isEmpty())
            }
        }
    }

    @Test
    fun `同轮bars传递且包装层不重新请求指数或参数`() = kotlinx.coroutines.runBlocking {
        val s = earlyRow()
        kotlin.test.assertSame(s.bars, s.copy(riskFlag = "news").bars)
        kotlin.test.assertNotNull(ExitPlan.compute(s.bars))
        val artifact = LocalPicks.buildWithMarket(listOf(s), LocalPicks.Session.MIDDAY, "2026-11-02T13:00:00+08:00")
        assertEquals(91.0, artifact.picks.single().score)
        val root = java.io.File(System.getProperty("skapp.fixtures")).parentFile
        val holder = java.io.File(root, "ui/src/main/java/com/skapp/app/ui/state/LocalPicksHolder.kt").readText()
        assertTrue(holder.contains("screenedByCode[p.code]?.bars.orEmpty()"))
        assertTrue(!holder.contains("DataCache.kline(p.code"))
        val screener = java.io.File(root, "engine-runtime/src/main/java/com/skapp/app/data/LocalScreener.kt").readText()
        assertTrue(screener.contains("bars = bars,"))
        assertTrue(screener.contains("EarlyVoteEngine.tomorrow(bars, earlyContext)"))
        assertTrue(screener.contains("EarlyVoteEngine.morning(bars, earlyContext)"))
    }

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
        assertEquals(LocalPicks.Session.MORNING, LocalPicks.sessionFor(MarketHours.Phase.PRE_OPEN))
        assertEquals(LocalPicks.Session.MORNING, LocalPicks.sessionFor(MarketHours.Phase.AUCTION_OPEN))
        assertEquals(LocalPicks.Session.MORNING, LocalPicks.sessionFor(MarketHours.Phase.MORNING))
        assertEquals(LocalPicks.Session.MIDDAY, LocalPicks.sessionFor(MarketHours.Phase.LUNCH_BREAK))
        assertEquals(LocalPicks.Session.MIDDAY, LocalPicks.sessionFor(MarketHours.Phase.AFTERNOON))
        assertEquals(LocalPicks.Session.REALTIME, LocalPicks.sessionFor(MarketHours.Phase.CLOSING))
        // 收盘后/休市进入独立实时会话，不复用早午盘。
        assertEquals(LocalPicks.Session.REALTIME, LocalPicks.sessionFor(MarketHours.Phase.AFTER_HOURS))
        assertEquals(LocalPicks.Session.REALTIME, LocalPicks.sessionFor(MarketHours.Phase.CLOSED_DAY))
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

    @Test
    fun `旧中枢高分不能填补缺失链路`() {
        val result = LocalPicks.build(listOf(row("600001", 99.0).copy(canonicalScore = null)),
            LocalPicks.Session.REALTIME, "2026-11-02T20:00:00+08:00")
        assertTrue(result.picks.isEmpty())
        assertEquals("insufficient", result.status)
    }

    @Test
    fun `链路分优先且不再过第二次市况闸门`() {
        val result = LocalPicks.build(listOf(row("600001", 1.0).copy(canonicalScore = 66.0)),
            LocalPicks.Session.MIDDAY, "2026-11-02T13:00:00+08:00", gate = 0.5)
        assertEquals(66.0, result.picks.single().score)
        assertEquals("midday", result.session)
    }

    @Test
    fun `旧快照与非主板不能推荐`() {
        val result = LocalPicks.build(listOf(row("600001", 99.0).copy(snapshotStatus = "stale"), row("300001", 99.0)),
            LocalPicks.Session.MIDDAY, "2026-11-02T13:00:00+08:00")
        assertTrue(result.picks.isEmpty())
    }

    @Test fun `财报复权来源证据缺失不得进入推荐`() {
        val result = LocalPicks.build(listOf(row("600001", 99.0).copy(dataEvidence = null)),
            LocalPicks.Session.REALTIME, "2026-11-02T20:00:00+08:00")
        assertTrue(result.picks.isEmpty())
        assertEquals("insufficient", result.status)
    }
}
