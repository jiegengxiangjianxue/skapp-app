package com.skapp.app.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class LiveMarketFetchTest {
    private class FakeConnection(url: URL, val respond: FakeConnection.() -> String?) : HttpURLConnection(url) {
        private var body: String? = null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            body = respond()
            return if (body == null) 503 else 200
        }
        override fun getInputStream() = ByteArrayInputStream(body.orEmpty().toByteArray(Charsets.UTF_8))
    }

    private fun field(name: String) = LiveMarket::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun withFake(
        respond: FakeConnection.() -> String? = { null },
        block: suspend CoroutineScope.(List<FakeConnection>) -> Unit,
    ) = runBlocking {
        val factory = Http.openConnection
        val fault = Http.fault
        val names = listOf("cached", "cachedAt", "hostIdx", "attemptSequence", "lastAttemptResult", "lastAttemptFailed", "lastAttemptAt")
        val saved = names.associateWith { field(it).get(LiveMarket) }
        val connections = CopyOnWriteArrayList<FakeConnection>()
        try {
            field("cached").set(LiveMarket, emptyList<LiveMarket.Row>())
            field("cachedAt").setLong(LiveMarket, 0L)
            field("hostIdx").setInt(LiveMarket, 0)
            field("attemptSequence").setLong(LiveMarket, 0L)
            field("lastAttemptResult").set(LiveMarket, emptyList<LiveMarket.Row>())
            field("lastAttemptFailed").setBoolean(LiveMarket, false)
            field("lastAttemptAt").setLong(LiveMarket, 0L)
            Http.fault = null
            Http.openConnection = { url -> FakeConnection(url, respond).also { connections.add(it) } }
            coroutineScope { block(this, connections) }
        } finally {
            Http.openConnection = factory
            Http.fault = fault
            saved.forEach { (name, value) -> field(name).set(LiveMarket, value) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetch(method: String, deadline: Long): List<LiveMarket.Row> = suspendCoroutine { continuation ->
        val target = LiveMarket::class.java.getDeclaredMethod(method, Long::class.javaPrimitiveType, Continuation::class.java)
        target.isAccessible = true
        try {
            val result = target.invoke(LiveMarket, deadline, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as List<LiveMarket.Row>)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            continuation.resumeWithException(e.targetException)
        }
    }

    private fun page(url: URL) = url.query.split('&').first { it.startsWith("pn=") || it.startsWith("page=") }.substringAfter('=').toInt()

    @Test fun `failed probes stop at two east hosts and one sina request`() = withFake { connections ->
        assertTrue(LiveMarket.snapshot(force = true).isEmpty())
        assertEquals(3, connections.size)
        assertEquals(listOf("push2delay.eastmoney.com", "push2.eastmoney.com", "vip.stock.finance.sina.com.cn"), connections.map { it.url.host })
        assertTrue(connections.all { page(it.url) == 1 })
    }

    @Test fun `three queued force calls share one failed round`() = withFake { connections ->
        val lock = field("fetchLock").get(LiveMarket) as Mutex
        lock.lock()
        val calls = try {
            List(3) { async(start = CoroutineStart.UNDISPATCHED) { LiveMarket.snapshot(force = true) } }
        } finally {
            lock.unlock()
        }
        val results = calls.awaitAll()
        assertEquals(3, connections.size)
        assertTrue(results.all { it.isEmpty() })
        assertTrue(results.all { it === results.first() })
        assertEquals(1L, field("attemptSequence").getLong(LiveMarket))
    }

    @Test fun `timeouts are capped and shrink with the remaining round budget`() = withFake(
        respond = {
            assertTrue(connectTimeout in 1..40)
            assertEquals(connectTimeout, readTimeout)
            Thread.sleep(10)
            throw SocketTimeoutException("fake blocked response")
        },
    ) { connections ->
        assertTrue(fetch("fetchAll", System.nanoTime() + 80_000_000L).isEmpty())
        assertTrue(connections.isNotEmpty())
        assertTrue(connections.size <= 3)
        assertTrue(connections.zipWithNext().all { (a, b) -> b.connectTimeout <= a.connectTimeout })
    }

    @Test fun `successful probes are reused and partial rows retain provenance and unknown times`() = withFake(
        respond = {
            if (url.path.endsWith("getHQNodeStockCount")) "\"5350\""
            else if (page(url) != 1) null
            else if (url.host.endsWith("eastmoney.com")) {
                """{"data":{"total":5926,"diff":[{"f12":"600519","f2":10,"f124":1730000000},{"f12":"000001","f2":8}]}}"""
            } else {
                """[{"code":"600000","trade":9,"ticktime":"15:00:00"}]"""
            }
        },
    ) { connections ->
        val rows = LiveMarket.snapshot(force = true)
        assertEquals(3, rows.size)
        assertEquals(1, connections.count { it.url.host.endsWith("eastmoney.com") && page(it.url) == 1 })
        assertEquals(1, connections.count { it.url.path.endsWith("getHQNodeData") && page(it.url) == 1 })
        assertEquals(1, connections.count { it.url.path.endsWith("getHQNodeStockCount") })
        assertEquals(0, connections.count { it.url.host == "sqt.gtimg.cn" })
        assertEquals(1_730_000_000_000L, rows.single { it.code == "600519" }.ts)
        assertEquals(0L, rows.single { it.code == "000001" }.ts)
        val sina = rows.single { it.code == "600000" }
        assertEquals(0L, sina.ts)
        assertEquals("sina/Market_Center", sina.source)
        assertEquals("eastmoney/clist", rows.first().source)
        assertEquals(5926, rows.first().expectedTotal)
        assertFalse(LiveMarket.completeCoverage(rows))
        assertTrue(connections.all { it.connectTimeout in 1..3000 && it.readTimeout in 1..3000 })
    }

    @Test fun `failure preserves cache age and ordinary calls honor retry cooldown`() = withFake { connections ->
        val parse = LiveMarket::class.java.getDeclaredMethod("parsePage", String::class.java).apply { isAccessible = true }
        val old = parse.invoke(LiveMarket, """{"data":{"total":5926,"diff":[{"f12":"600519","f124":1730000000}]}}""")
        field("cached").set(LiveMarket, old)
        field("cachedAt").setLong(LiveMarket, 123L)
        assertSame(old, LiveMarket.snapshot(force = true))
        assertEquals(123L, field("cachedAt").getLong(LiveMarket))
        assertSame(old, LiveMarket.snapshot())
        assertEquals(3, connections.size)
        field("lastAttemptAt").setLong(LiveMarket, System.nanoTime() - 6_000_000_000L)
        assertSame(old, LiveMarket.snapshot())
        assertEquals(6, connections.size)
        assertEquals(123L, field("cachedAt").getLong(LiveMarket))
    }

    @Test fun `cancellation prevents the next domain request and cache publication`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withFake(respond = {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            null
        }) { connections ->
            val result = async { LiveMarket.snapshot(force = true) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                result.cancel()
            } finally {
                release.countDown()
            }
            assertFailsWith<CancellationException> { result.await() }
            assertEquals(1, connections.size)
            assertEquals(0L, field("cachedAt").getLong(LiveMarket))
            assertEquals(0L, field("attemptSequence").getLong(LiveMarket))
        }
    }

    @Test fun `permit waiters do not start requests after the round budget expires`() {
        val started = CountDownLatch(8)
        val release = CountDownLatch(1)
        withFake(respond = {
            if (page(url) == 1) {
                """{"data":{"total":5926,"diff":[{"f12":"600519"}]}}"""
            } else {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                null
            }
        }) { connections ->
            val result = async(Dispatchers.IO) { fetch("fetchEastAll", System.nanoTime() + 400_000_000L) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                delay(450)
            } finally {
                release.countDown()
            }
            assertEquals(1, result.await().size)
            assertEquals(9, connections.size)
            assertEquals(1, connections.count { page(it.url) == 1 })
        }
    }
}
