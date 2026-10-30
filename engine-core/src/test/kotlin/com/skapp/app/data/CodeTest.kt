package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 代码归一化 / 前缀 / 直查（纯规则，无 IO）。
 *
 * 这三个方法原先挂在网络层客户端上，界面为了拼个 sh/sz 前缀得 import 云端客户端——
 * 正是「界面绕过库层取数」的入口之一。下沉到 core 后规则不变，但依赖方向拧正了。
 */
class CodeTest {

    @Test
    fun `归一化补零并保留港股前缀`() {
        assertEquals("600000", Code.normalize("600000"))
        assertEquals("000001", Code.normalize("000001"))
        assertEquals("600000", Code.normalize("sh600000"))
        assertEquals("hk00700", Code.normalize("HK700"))
        assertNull(Code.normalize("abc"))
    }

    @Test
    fun `行情前缀按市场判定`() {
        assertEquals("sh600000", Code.prefixed("600000"))
        assertEquals("sh688981", Code.prefixed("688981"))
        assertEquals("sz000001", Code.prefixed("000001"))
        assertEquals("sz300750", Code.prefixed("300750"))
        assertEquals("bj430047", Code.prefixed("430047"))
        // 港股原样返回（腾讯港股前缀就是 hk）
        assertEquals("hk00700", Code.prefixed("hk700"))
    }

    @Test
    fun `直查只认 A 股常规代码段`() {
        assertEquals("600000", Code.directSymbol("600000")?.code)
        assertEquals("300750", Code.directSymbol("300750")?.code)
        assertNull(Code.directSymbol("hk00700"), "港股不该被当成 A 股直查结果")
        assertNull(Code.directSymbol("900001"), "B 股代码段不在直查范围")
        assertEquals("001234", Code.directSymbol("1234")?.code, "短代码补零后是深市常规段，允许直查")
        assertNull(Code.directSymbol("abc"))
    }

    @Test
    fun `取末六位数字用于对齐`() {
        assertEquals("600519", Code.digits6("sh600519"))
        assertEquals("000001", Code.digits6("000001"))
        assertEquals("", Code.digits6("abc"), "无数字返回空串，调用方靠 length==6 自行过滤")
    }

    @Test
    fun `主板判定只认沪深主板七段前缀`() {
        assertTrue(Code.isMainBoard("600519"))
        assertTrue(Code.isMainBoard("000001"))
        assertTrue(Code.isMainBoard("605588"))
        // 带市场前缀也要判对：旧实现 take(3) 会把 sh600519 判成非主板，进而整表过滤成空
        assertTrue(Code.isMainBoard("sh600519"))
        assertFalse(Code.isMainBoard("003816"), "003 深主板不在推荐口径内")
        assertFalse(Code.isMainBoard("300750"))
        assertFalse(Code.isMainBoard("688981"))
        assertFalse(Code.isMainBoard("830799"))
    }
}
