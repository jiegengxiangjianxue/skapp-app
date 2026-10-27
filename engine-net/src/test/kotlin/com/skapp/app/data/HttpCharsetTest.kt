package com.skapp.app.data

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Http] 收敛后的口径钉子（2026-10-27）。
 *
 * 这些映射原来散在 6 处各写一遍（MarketDataClient / SkappApi / ExtendedDataClient …），
 * 出错方式不是崩溃而是**静默乱码或空表**，界面上看不出来。所以用测试钉住：
 * 字符集一旦被后人「顺手统一成 UTF-8」，这里立刻红。
 */
class HttpCharsetTest {

    @Test
    fun `gbk 大小写都映射到 GBK`() {
        assertEquals(Charset.forName("GBK"), Http.charset("gbk"))
        assertEquals(Charset.forName("GBK"), Http.charset("GBK"))
    }

    @Test
    fun `gb2312 与 gb18030 映射到 GB18030 而不是 UTF-8`() {
        // 原 MarketDataClient / SkappApi 把这类当 UTF-8 读（乱码）；收敛后按 GB18030
        assertEquals(Charset.forName("GB18030"), Http.charset("gb2312"))
        assertEquals(Charset.forName("GB18030"), Http.charset("gb18030"))
    }

    @Test
    fun `其余字符集名一律回落 UTF-8`() {
        assertEquals(Charsets.UTF_8, Http.charset("utf-8"))
        assertEquals(Charsets.UTF_8, Http.charset("UTF-8"))
        assertEquals(Charsets.UTF_8, Http.charset(""))
        assertEquals(Charsets.UTF_8, Http.charset("big5"))
    }

    @Test
    fun `Probe 非 2xx 无正文且 ok 为假`() {
        val p = Http.Probe(404, null)
        assertFalse(p.ok)
        assertNull(p.body)
        assertEquals("", p.text)
    }

    @Test
    fun `Probe 2xx 视为成功`() {
        val p = Http.Probe(200, "ok")
        assertTrue(p.ok)
        assertEquals("ok", p.text)
    }

    @Test
    fun `Probe 连接层失败用 code -1 与 error 承载原因`() {
        val p = Http.Probe(-1, null, "UnknownHostException")
        assertFalse(p.ok)
        assertEquals(-1, p.code)
        assertEquals("UnknownHostException", p.error)
    }
}
