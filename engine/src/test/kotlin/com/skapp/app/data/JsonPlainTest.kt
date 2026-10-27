package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `org.json` → Kotlin 容器的转换测试（vc19 闪退修复的配套）。
 *
 * 注意这套测试**抓不到**当初那个 bug：JVM 测试跑在 `org.json:json:20240303` 上，
 * 那个 jar 的 `JSONObject` 自带 `toMap()` 成员，所以哪怕代码又写回 `json.toMap()`，
 * 这里的测试依旧全绿——真机才炸。拦这个的活交给 `tools/org_json_api_guard.py`（扫字节码）。
 *
 * 本文件管的是另一半：转换**语义**（嵌套怎么展开、顺序保不保、非对象元素怎么处理），
 * 因为四份重复实现原来语义并不一致，合并时最容易悄悄改掉数据形状。
 */
class JsonPlainTest {

    @Test
    fun `nested objects and arrays are expanded recursively`() {
        val root = JSONObject(
            """
            {"name":"平安银行","num":12.5,"ok":true,"none":null,
             "meta":{"level":2,"tags":["a","b"]},
             "rows":[{"code":"000001","v":1},{"code":"600000","v":2}]}
            """.trimIndent()
        )
        val map = root.toPlainMap()

        assertEquals("平安银行", map["name"])
        // org.json:json 把数字解析成 BigDecimal/Integer/Long，Android 上是 Double，
        // 所以调用方一律走 toString().toDoubleOrNull()——这里按 Number 比较，不锁具体实现类型
        assertEquals(12.5, (map["num"] as Number).toDouble(), 0.0001)
        assertEquals(true, map["ok"])
        // org.json 的 null 是哨兵对象 JSONObject.NULL，不是 Kotlin null；
        // 这是转换前后一致的行为（下游都用 toString() 兜），这里显式钉住，避免有人「顺手」改成 null
        assertTrue(map.containsKey("none"))
        assertTrue(map["none"] === JSONObject.NULL)

        // 嵌套对象 → Map
        @Suppress("UNCHECKED_CAST")
        val meta = map["meta"] as Map<String, Any?>
        assertEquals(2, meta["level"])
        // 嵌套数组 → List（元素递归）
        assertEquals(listOf("a", "b"), meta["tags"])

        // 对象数组 → List<Map>，不再残留 JSONObject
        @Suppress("UNCHECKED_CAST")
        val rows = map["rows"] as List<Map<String, Any?>>
        assertEquals(2, rows.size)
        assertEquals("000001", rows[0]["code"])
        assertEquals(2, rows[1]["v"])
    }

    @Test
    fun `all keys survive the conversion`() {
        // 注意：org.json 内部是 HashMap，键顺序本身不保证，所以这里只断言「键集合完整」。
        // 用 LinkedHashMap 只是为了让同一次运行内迭代顺序稳定，consumer 一律按 key 取值。
        val root = JSONObject("""{"z":1,"a":2,"m":3}""")
        assertEquals(setOf("z", "a", "m"), root.toPlainMap().keys.toSet())
    }

    @Test
    fun `plain list recurses into object elements`() {
        val arr = JSONArray("""[1,{"k":"v"},[true]]""")
        val list = arr.toPlainList()
        assertEquals(1, list[0])
        @Suppress("UNCHECKED_CAST")
        assertEquals("v", (list[1] as Map<String, Any?>)["k"])
        assertEquals(listOf(true), list[2])
    }

    @Test
    fun `map list skips non-object elements`() {
        val arr = JSONArray("""[{"code":"000001"},"junk",42,{"code":"600000"}]""")
        val rows = arr.toPlainMapList()
        assertEquals(2, rows.size)
        assertEquals("000001", rows[0]["code"])
        assertEquals("600000", rows[1]["code"])
    }

    @Test
    fun `empty container does not throw`() {
        assertTrue(JSONObject().toPlainMap().isEmpty())
        assertTrue(JSONArray().toPlainMapList().isEmpty())
        assertTrue(JSONArray().toPlainList().isEmpty())
    }

    @Test
    fun `self reference of deep nesting stays finite`() {
        // 三层嵌套数组：确认递归没有把层数丢掉（旧的四份实现里有两份只递归一层）
        val root = JSONObject("""{"a":[[{"b":[1,2]}]]}""")
        @Suppress("UNCHECKED_CAST")
        val level1 = (root.toPlainMap()["a"] as List<Any?>)
        @Suppress("UNCHECKED_CAST")
        val level2 = level1[0] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val leaf = (level2[0] as Map<String, Any?>)["b"]
        assertEquals(listOf(1, 2), leaf)
    }
}
