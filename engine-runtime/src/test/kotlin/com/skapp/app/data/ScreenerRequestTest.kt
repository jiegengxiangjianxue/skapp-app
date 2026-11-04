package com.skapp.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScreenerRequestTest {
    private fun row() = LiveMarket.Row(
        code = "600000", name = "fixture", price = 10.0, pctChg = 2.0,
        volumeLots = null, amount = null, amplitude = null, turnoverRate = null,
        pe = null, volRatio = null, mv = null, mainNet = null, ts = 1L,
    )

    @Test
    fun `equal concurrent requests compute once but completed requests are not cached`() = runBlocking {
        val requests = LocalScreener.RequestCoordinator()
        val key = LocalScreener.RequestKey(snapshotIn = listOf(row()))
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key) { count++; gate.await(); emptyList() }
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key.copy(snapshotIn = listOf(row().copy()))) { count++; emptyList() }
        }
        assertEquals(1, count)
        gate.complete(Unit)
        assertEquals(owner.await(), waiter.await())
        requests.run(key) { count++; emptyList() }
        assertEquals(2, count)
    }

    @Test
    fun `all criteria and request inputs distinguish concurrent work`() = runBlocking {
        val requests = LocalScreener.RequestCoordinator()
        val key = LocalScreener.RequestKey(snapshotIn = listOf(row()))
        val c = key.criteria
        val criteria = listOf(
            c.copy(minChgPct = 2.0), c.copy(maxChgPct = 8.0), c.copy(minVolRatio = 2.0),
            c.copy(minScore = 60.0), c.copy(minCanonical = 60.0), c.copy(vetoByRiskFlag = false),
            c.copy(excludeST = false), c.copy(maxCandidates = 2), c.copy(klineCount = 60),
        )
        val keys = listOf(key) + criteria.map { key.copy(criteria = it) } + listOf(
            key.copy(mode = "all"), key.copy(dropLastBar = true), key.copy(rotation = 1),
            key.copy(fallbackCodes = listOf("600000")),
            key.copy(evidenceByCode = mapOf("600000" to LocalScreener.DataEvidence(
                null, null, null, null, "fixture", 1L, 2L))),
            key.copy(news = listOf(EngineChain.NewsEvidence(
                "600000", "fixture", "title", "positive", null, null, 1L, "fixture"))),
            key.copy(newsAvailable = true), key.copy(snapshotIn = listOf(row().copy(ts = 2L))),
            key.copy(snapshotIn = listOf(row().copy(price = 11.0))),
            key.copy(snapshotIn = emptyList()), key.copy(snapshotIn = null),
        )
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val jobs = keys.map { request ->
            async(start = CoroutineStart.UNDISPATCHED) {
                requests.run(request) { count++; gate.await(); emptyList() }
            }
        }
        assertEquals(keys.size, count)
        gate.complete(Unit)
        jobs.forEach { it.await() }
    }

    @Test
    fun `owner failure reaches waiters and permits retry`() = runBlocking {
        supervisorScope {
            val requests = LocalScreener.RequestCoordinator()
            val key = LocalScreener.RequestKey()
            val gate = CompletableDeferred<Unit>()
            var count = 0
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                requests.run(key) { count++; gate.await(); error("fixture failure") }
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                requests.run(key) { count++; emptyList() }
            }
            gate.complete(Unit)
            assertFailsWith<IllegalStateException> { owner.await() }
            assertFailsWith<IllegalStateException> { waiter.await() }
            requests.run(key) { count++; emptyList() }
            assertEquals(2, count)
        }
    }

    @Test
    fun `waiter cancellation does not cancel owner`() = runBlocking {
        val requests = LocalScreener.RequestCoordinator()
        val key = LocalScreener.RequestKey()
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key) { count++; gate.await(); emptyList() }
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key) { count++; emptyList() }
        }
        waiter.cancelAndJoin()
        assertTrue(owner.isActive)
        gate.complete(Unit)
        assertTrue(owner.await().isEmpty())
        assertEquals(1, count)
    }

    @Test
    fun `owner cancellation releases key for retry`() = runBlocking {
        val requests = LocalScreener.RequestCoordinator()
        val key = LocalScreener.RequestKey()
        val gate = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key) { gate.await(); emptyList() }
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run(key) { error("must join owner") }
        }
        owner.cancelAndJoin()
        assertFailsWith<kotlinx.coroutines.CancellationException> { waiter.await() }
        assertTrue(requests.run(key) { emptyList() }.isEmpty())
    }

    @Test
    fun `explicit empty snapshot never invokes fetch or fallback`() = runBlocking {
        var fetches = 0
        assertTrue(LocalScreener.snapshotForRequest(emptyList()) { fetches++; listOf(row()) }.isEmpty())
        assertEquals(0, fetches)
        val progress = mutableListOf<String>()
        assertTrue(LocalScreener.screen(
            fallbackCodes = listOf("600000"), snapshotIn = emptyList(),
            news = emptyList(), newsAvailable = false, onProgress = { progress.add(it) },
        ).isEmpty())
        assertEquals(listOf("复用本轮全市场快照…"), progress)
        assertEquals(listOf(row()), LocalScreener.snapshotForRequest(null) { fetches++; listOf(row()) })
        assertEquals(1, fetches)
    }

    private fun screened(score: Double, risk: String? = null): LocalScreener.Screened {
        val canonical = CanonicalEngine.Result(
            "600000", score, 0.0, 0.25, 1, 1, 0,
            listOf(CanonicalEngine.Vote("trend", 1, 0.4, "fixture"),
                CanonicalEngine.Vote("macd", -1, 0.6, "fixture")), "neutral", "fixture",
        )
        return LocalScreener.Screened(
            code = "600000", name = "fixture", price = 10.0, pctChg = 2.0,
            volRatio = null, turnover = null, pe = null, mv = null, engine = null,
            centralScore = 90.0, resonanceRatio = 0.9, effectiveScore = 99.0,
            chain = EngineChain.ChainResult(
                score, canonical, null, null, null, emptyList(), "fixture", false,
                emptyList(), risk, "ok",
            ),
            detailStatus = "ok", buyable = true,
        )
    }

    private fun card(s: LocalScreener.Screened): EngineFeed.DecisionCard {
        val pulse = LivePulse(
            ts = 1_700_000_000_000L, total = 1, up = 1, down = 0, flat = 0,
            limitUp = 0, limitDown = 0, medianPct = 2.0, breadth = 1.0,
            totalAmount = 0.0, mainNetTotal = 0.0, phaseScore = 62.0,
            label = "强势", inflow = emptyList(), outflow = emptyList(),
        )
        return EngineFeedLive.assemble(pulse, listOf(s), emptyList()).decision!!.cards.single()
    }

    @Test
    fun `decision card shows canonical30 not legacy90 and does not buy`() {
        val card = card(screened(30.0))
        assertEquals(30.0, card.composite)
        assertEquals(mapOf("trend" to 70.0, "macd" to 20.0), card.dims)
        assertEquals(0.25, card.confidence)
        assertNotEquals("buy", card.action)
    }

    @Test
    fun `risk veto prevents buy even if high score and buyable`() {
        assertEquals("avoid", card(screened(90.0, "flow_tape_weak")).action)
        assertEquals("avoid", card(screened(90.0, "flow_tape_weak").copy(riskFlag = null)).action)
    }
}
