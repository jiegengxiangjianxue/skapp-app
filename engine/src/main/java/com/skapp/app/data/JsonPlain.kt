package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * `org.json` → 纯 Kotlin 容器（含嵌套）。
 *
 * ## 为什么这些函数不能叫 `toMap()` / `toList()`（vc19 真机闪退的根因）
 *
 * 早先的解析函数曾用 `private fun JSONObject.toMap()` 做转换。看着没问题，实际上是死代码：
 * **Kotlin 的方法解析里，成员函数优先于扩展函数**。编译期 `:engine` 依赖的是
 * `org.json:json:20240303`（`compileOnly`），这个 jar 的 `JSONObject` **自带** `toMap()`
 * 成员，于是每一处 `json.toMap()` 都被解析成那个成员，编译成：
 *
 *     invokevirtual org/json/JSONObject.toMap:()Ljava/util/Map;
 *
 * 而 Android 平台内置的 `org.json`（运行时 `/apex/.../core-libart.jar`，编译期 `android.jar`）
 * **从来没有** `toMap()` 这个方法——不是版本高低问题，是两边压根不是同一个库
 * （android-35 的 `JSONObject` 48 个方法，独立的 `org.json:json:20240303` 有 101 个）：
 *
 *     javap -classpath $ANDROID_HOME/platforms/android-35/android.jar org.json.JSONObject | grep toMap
 *     # 0 行
 *
 * 结果就是真机上一进决策页就 `NoSuchMethodError` 崩掉，而编译与 JVM 单测全绿——**测试同样跑在
 * 那个自带 `toMap()` 的 jar 上**，两边不同源。
 *
 * 修法是换名：`toPlainMap()` / `toPlainList()` 在 `org.json` 里不存在同名成员，
 * 扩展函数就不可能被成员抢走解析。**以后别把名字改回去**，CI 里
 * `tools/org_json_api_guard.py` 会把字节码里引用到的每个 org.json 方法与 `android.jar`
 * 的方法集逐个比对，越界直接红。
 *
 * ## 为什么合并成一份
 *
 * 原先 `SkappApi` / `ExtendedDataClient` / `HoloClient` / `SiteBoardClient` 各抄了一份，
 * 且语义还不一致（前两者的数组元素会递归，后两者只做 `opt(i)`，嵌套对象会漏成 `JSONObject`）。
 * 四份重复正是这个 bug 能躺这么久没人发现的原因。现在统一走这里，语义取「全递归」：
 * 嵌套对象 → `Map`，嵌套数组 → `List`，字符串 / 数字 / 布尔原样。
 */

/** JSONObject → Map（嵌套对象与数组都递归展开）。 */
internal fun JSONObject.toPlainMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(length())
    keys().forEach { key -> out[key] = plainValue(this, key) }
    return out
}

/** JSONArray → List（元素递归展开）。 */
internal fun JSONArray.toPlainList(): List<Any?> =
    (0 until length()).map { i -> plainValue(this, i) }

/** JSONArray（对象数组）→ List<Map>，非对象元素丢弃。等价于旧的 `toMapList()`。 */
internal fun JSONArray.toPlainMapList(): List<Map<String, Any?>> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.toPlainMap() }

private fun plainValue(o: JSONObject, key: String): Any? = when (val v = o.opt(key)) {
    is JSONObject -> v.toPlainMap()
    is JSONArray -> v.toPlainList()
    else -> v
}

private fun plainValue(a: JSONArray, i: Int): Any? = when (val v = a.opt(i)) {
    is JSONObject -> v.toPlainMap()
    is JSONArray -> v.toPlainList()
    else -> v
}
