package com.skapp.app.data

/**
 * 证券代码归一化。
 *
 * 为什么单独放 core：`SymbolIndex`（数据模型）也要用它。原先它挂在 `SkappApi` 上，
 * 于是「模型层 → 网络层」形成倒挂依赖，拆模块时直接把 core 和 net 打成环。
 * 归一化本质是纯字符串处理，没有任何 IO，属于 core 的职责。
 *
 * 规则：A 股 6 位补零；港股 hk + 5 位（App 自选里两种代码都有）。
 */
object Code {

    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.startsWith("hk")) {
            val d = s.filter { it.isDigit() }.padStart(5, '0')
            return if (d == "00000") null else "hk$d"
        }
        val digits = s.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val b = digits.padStart(6, '0')
        return if (b.length == 6) b else null
    }
}
