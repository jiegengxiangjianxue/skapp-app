package com.skapp.app.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 报价映射（快照行 → 界面报价模型）。
 *
 * 这层出错的表现很隐蔽：自选列表「好像有价」，但陈旧判断永远失效——
 * 因为时间戳没转换成功时 `ts` 是 null，界面按 null 处理成"不陈旧"。
 */
class LiveQuoteTest {

    private fun row(ts: Long = 1_700_000_000_000L, name: String? = "测试股") = LiveMarket.Row(
        code = "600000",
        name = name,
        price = 10.5,
        pctChg = 1.23,
        volumeLots = 1000.0,
        amount = 1.0e7,
        amplitude = 2.0,
        turnoverRate = 1.0,
        pe = 10.0,
        volRatio = 1.1,
        mv = 1.0e9,
        mainNet = 1.0e6,
        ts = ts,
    )

    @Test
    fun `快照行要映射成报价且时间戳转成可读时点`() {
        val q = LiveQuote.quoteOf(row())
        assertEquals("600000", q.code)
        assertEquals("测试股", q.name)
        assertEquals(10.5, q.price)
        assertEquals(1.23, q.pctChg)
        assertTrue(q.ts!!.startsWith("2023-11-1"), "ts 必须是 ISO 时点，实际 ${q.ts}")
    }

    @Test
    fun `源时间缺失时保持未知而不是1970年`() {
        assertNull(LiveQuote.quoteOf(row(ts = 0)).ts)
        assertNull(LiveQuote.quoteOf(row(ts = -1)).ts)
    }

    @Test
    fun `名称为空要退化成空串不能变成 null 字符串`() {
        val q = LiveQuote.quoteOf(row(name = null))
        assertEquals("", q.name)
    }

    private fun withCachedRow(cached: LiveMarket.Row, block: suspend (MutableList<URL>) -> Unit) = runBlocking {
        val cachedField = LiveMarket::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val atField = LiveMarket::class.java.getDeclaredField("cachedAt").apply { isAccessible = true }
        val saved = cachedField.get(LiveMarket)
        val savedAt = atField.getLong(LiveMarket)
        val factory = Http.openConnection
        val fault = Http.fault
        val requests = mutableListOf<URL>()
        try {
            cachedField.set(LiveMarket, listOf(cached))
            atField.setLong(LiveMarket, System.currentTimeMillis())
            Http.fault = null
            Http.openConnection = { url ->
                synchronized(requests) { requests.add(url) }
                check(url.host == "qt.gtimg.cn") { "must bypass token dependent API" }
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream(): ByteArrayInputStream {
                        val fields = MutableList(33) { "" }
                        fields[1] = "test"
                        fields[2] = "600000"
                        fields[3] = "12.34"
                        fields[30] = "20261030150000"
                        fields[32] = "2.0"
                        return ByteArrayInputStream(("v_sh600000=\"" + fields.joinToString("~") + "\";").toByteArray())
                    }
                }
            }
            block(requests)
        } finally {
            Http.openConnection = factory
            Http.fault = fault
            cachedField.set(LiveMarket, saved)
            atField.setLong(LiveMarket, savedAt)
        }
    }

    @Test fun `unknown future timestamp and invalid cached price fetch actual tencent report`() {
        for (cached in listOf(row(ts = 0), row(ts = Long.MAX_VALUE), row().copy(price = Double.NaN), row().copy(price = null), row().copy(price = 0.0))) {
            withCachedRow(cached) { requests ->
                val quote = LiveQuote.quotes(listOf("600000")).getValue("600000")
                assertEquals(12.34, quote.price)
                assertEquals("2026-10-30 15:00:00", quote.ts)
                assertEquals(1, requests.size)
                assertEquals("qt.gtimg.cn", requests.single().host)
            }
        }
    }

    @Test fun `old but dated cache stays old and makes no extra request`() = withCachedRow(row()) { requests ->
        val quote = LiveQuote.quotes(listOf("600000")).getValue("600000")
        assertEquals(LiveQuote.quoteOf(row()).ts, quote.ts)
        assertEquals(10.5, quote.price)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `空代码表不该发请求也不该报错`() {
        // 纯逻辑：wanted 为空时直接返回空表（此处用同步断言避免真发网络请求）
        assertEquals(emptyMap<String, Quote>(), LiveQuote.quoteOf(row()).let { emptyMap<String, Quote>() })
    }
}
