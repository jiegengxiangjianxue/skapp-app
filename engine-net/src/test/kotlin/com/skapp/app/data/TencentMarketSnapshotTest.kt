package com.skapp.app.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TencentMarketSnapshotTest {
    private fun fields(code: String = "600519", time: String = "20261030150000"): JSONArray = JSONArray(
        List(88) { index -> when (index) {
            1 -> "测试股"
            2 -> code
            3 -> "100.50"
            4 -> "99.00"
            5 -> "99.50"
            6 -> "1234"
            30 -> time
            32 -> "1.52"
            33 -> "102.00"
            34 -> "98.00"
            else -> ""
        } },
    )

    private fun parse(body: JSONObject, codes: List<String> = listOf("600519"), total: Int? = 1) =
        TencentQuote.parseMarketRows(body.toString(), codes, total)

    @Test fun `typed 88 string array maps price lots and date from the same report`() {
        val rows = parse(JSONObject().put("sh600519", fields()))
        val row = rows.single()
        assertEquals(100.5, row.price)
        assertEquals(99.0, row.prevClose)
        assertEquals(99.5, row.open)
        assertEquals(1234.0, row.volumeLots)
        assertEquals(1.52, row.pctChg)
        assertEquals(102.0, row.high)
        assertEquals(98.0, row.low)
        assertEquals(TencentQuote.sourceTimestamp("20261030150000"), row.ts)
        assertEquals("2026-10-30 15:00:00", LiveQuote.quoteOf(row).ts)
        assertEquals(TencentQuote.MARKET_SOURCE, row.source)
        assertNull(row.amount)
        assertNull(row.pe)
        assertNull(row.mv)
        assertNull(row.volRatio)
        assertNull(row.mainNet)
        assertTrue(LiveMarket.completeCoverage(rows))
    }

    @Test fun `missing identity mismatch duplicate identity and partial provenance never prove coverage`() {
        val good = JSONObject().put("sh600519", fields())
        assertFalse(LiveMarket.completeCoverage(parse(good, listOf("600519", "600000"), 2)))
        assertTrue(parse(JSONObject().put("sh600519", fields("600000"))).isEmpty())
        val duplicate = good.put("sh600000", fields("600519"))
        val rows = parse(duplicate, listOf("600519", "600000"), 2)
        assertEquals(1, rows.size)
        assertFalse(LiveMarket.completeCoverage(rows))
        assertFalse(LiveMarket.completeCoverage(parse(JSONObject().put("sh600519", fields()), total = null)))
        assertTrue(parse(JSONObject().put("sz600519", fields())).isEmpty())
        val duplicateKeys = "{\"sh600519\":" + fields() + ",\"sh600519\":" + fields() + "}"
        assertTrue(TencentQuote.parseMarketRows(duplicateKeys, listOf("600519"), 1).isEmpty())
    }

    @Test fun `invalid calendar future partial timestamp and untyped arrays are rejected`() {
        for (time in listOf("20260230150000", "20261030246000", "20990101150000", "202610301500", "20261030150000junk", "00000101150000")) {
            assertTrue(parse(JSONObject().put("sh600519", fields(time = time))).isEmpty(), time)
        }
        assertTrue(parse(JSONObject().put("sh600519", fields().put(3, 100.5))).isEmpty())
        assertTrue(parse(JSONObject().put("sh600519", JSONArray(List(35) { "" }))).isEmpty())
        for (price in listOf("NaN", "Infinity", "0", "-1")) {
            assertTrue(parse(JSONObject().put("sh600519", fields().put(3, price))).isEmpty())
        }
    }

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

    private fun withFake(respond: FakeConnection.() -> String?, block: suspend (List<FakeConnection>) -> Unit) = runBlocking {
        val factory = Http.openConnection
        val fault = Http.fault
        val connections = CopyOnWriteArrayList<FakeConnection>()
        try {
            Http.fault = null
            Http.openConnection = { url -> FakeConnection(url, respond).also { connections.add(it) } }
            block(connections)
        } finally {
            Http.openConnection = factory
            Http.fault = fault
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun sina(deadline: Long): List<LiveMarket.Row> = suspendCoroutine { continuation ->
        val method = LiveMarket::class.java.getDeclaredMethod("fetchSinaAll", Long::class.javaPrimitiveType, Continuation::class.java)
        method.isAccessible = true
        try {
            val result = method.invoke(LiveMarket, deadline, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as List<LiveMarket.Row>)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            continuation.resumeWithException(e.targetException)
        }
    }

    private fun universeReply(url: URL, count: Int = 3000, missingPage: Int? = null): String? {
        if (url.path.endsWith("getHQNodeStockCount")) return "\"$count\""
        if (url.host == "sqt.gtimg.cn") {
            val codes = url.query.substringAfter("q=").substringBefore('&').split(',')
            return JSONObject().also { body -> codes.forEach { body.put(it, fields(it.drop(2))) } }.toString()
        }
        val page = url.query.substringAfter("page=").substringBefore('&').toInt()
        if (page == missingPage) return null
        return JSONArray().also { arr ->
            val start = (page - 1) * 100
            for (i in start until minOf(start + 100, 3000)) {
                arr.put(JSONObject().put("code", (600000 + i).toString()).put("trade", 9999).put("ticktime", "15:00:00"))
            }
        }.toString()
    }

    @Test fun `complete official enumeration upgrades every quote and keeps source date not fetch date`() = withFake(
        respond = { universeReply(url) },
    ) { connections ->
        val rows = sina(System.nanoTime() + 18_000_000_000L)
        assertEquals(3000, rows.size)
        assertTrue(LiveMarket.completeCoverage(rows))
        assertTrue(rows.all { it.expectedTotal == 3000 && it.price == 100.5 && it.ts == TencentQuote.sourceTimestamp("20261030150000") })
        assertEquals(1, connections.count { it.url.path.endsWith("getHQNodeStockCount") })
        assertEquals(30, connections.count { it.url.host == "sqt.gtimg.cn" })
        assertTrue(connections.all { it.connectTimeout in 1..3000 && it.readTimeout == it.connectTimeout })
    }

    @Test fun `missing page count mismatch and duplicate universe identities cannot claim full market`() {
        for (mode in listOf("missing", "count", "duplicate")) {
            withFake(respond = {
                if (mode == "duplicate" && url.path.endsWith("getHQNodeData") && url.query.startsWith("page=2&")) {
                    universeReply(URL(url.toString().replace("page=2&", "page=1&")))
                } else universeReply(url, count = if (mode == "count") 3001 else 3000, missingPage = if (mode == "missing") 2 else null)
            }) { _ ->
                val rows = sina(System.nanoTime() + 18_000_000_000L)
                assertTrue(rows.isNotEmpty())
                assertTrue(rows.all { it.expectedTotal == null })
                assertFalse(LiveMarket.completeCoverage(rows), mode)
            }
        }
    }

    @Test fun `one missing tencent row keeps verified total but not full coverage`() = withFake(
        respond = {
            val body = universeReply(url)
            if (url.host == "sqt.gtimg.cn" && url.query.contains("sh600000")) {
                JSONObject(body!!).apply { remove("sh600000") }.toString()
            } else body
        },
    ) { _ ->
        val rows = sina(System.nanoTime() + 18_000_000_000L)
        assertEquals(2999, rows.size)
        assertTrue(rows.all { it.expectedTotal == 3000 && it.ts > 0 })
        assertFalse(LiveMarket.completeCoverage(rows))
    }

    @Test fun `a missing tencent response is partial even with a complete enumeration`() = withFake(
        respond = { if (url.host == "sqt.gtimg.cn") null else universeReply(url) },
    ) { _ ->
        val rows = sina(System.nanoTime() + 18_000_000_000L)
        assertEquals(3000, rows.size)
        assertTrue(rows.all { it.ts == 0L && it.source == "sina/Market_Center" })
        assertFalse(LiveMarket.completeCoverage(rows))
    }

    @Test fun `expired budget does not open any tencent connection`() = withFake(respond = { error("expired request") }) { connections ->
        assertTrue(TencentQuote.marketSnapshot(listOf("600519"), 1, System.nanoTime() - 1).isEmpty())
        assertTrue(connections.isEmpty())
    }

    @Test fun `six permits cap concurrency and expired waiters never open sockets`() {
        val started = CountDownLatch(6)
        val release = CountDownLatch(1)
        withFake(respond = {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            null
        }) { connections ->
            val codes = (600000 until 601000).map { it.toString() }
            val result = kotlinx.coroutines.coroutineScope {
                val task = async(Dispatchers.IO) { TencentQuote.marketSnapshot(codes, 1000, System.nanoTime() + 500_000_000L) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                    delay(550)
                } finally { release.countDown() }
                task.await()
            }
            assertTrue(result.isEmpty())
            assertEquals(6, connections.size)
        }
    }

    @Test fun `cancellation prevents publication and queued tencent requests`() {
        val started = CountDownLatch(6)
        val release = CountDownLatch(1)
        withFake(respond = {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            null
        }) { connections ->
            kotlinx.coroutines.coroutineScope {
                val task = async(Dispatchers.IO) { TencentQuote.marketSnapshot((600000 until 601000).map { it.toString() }, 1000, System.nanoTime() + 18_000_000_000L) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                    task.cancel()
                } finally { release.countDown() }
                assertFailsWith<CancellationException> { task.await() }
            }
            assertEquals(6, connections.size)
        }
    }
}
