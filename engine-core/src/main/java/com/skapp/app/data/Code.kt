package com.skapp.app.data

/**
 * 证券代码归一化。**纯字符串规则，零 IO。**
 *
 * 为什么在 core：`SymbolIndex`（数据模型）也要用它；界面拼自选/搜索/行情前缀时同样要用。
 * 原先这些方法挂在 `SkappApi`（网络层）上，于是「模型层 → 网络层」形成倒挂依赖，
 * 拆模块时直接把 core 和 net 打成环；界面为了拼个 `sh`/`sz` 前缀还得 import 云端客户端，
 * 这也是「界面绕过库层取数」的入口之一。下沉到 core 后规则不变，依赖方向拧正了。
 *
 * 规则：A 股 6 位补零；港股 hk + 5 位（App 自选里两种代码都有）。
 */
object Code {

    /** 推荐输出的交易权限红线：仅沪深主板（600/601/603/605/000/001/002）。 */
    val MAIN_BOARD_PREFIXES = setOf("600", "601", "603", "605", "000", "001", "002")

    /**
     * 是否沪深主板（推荐口径）。先取末 6 位再比前缀，`sh600000` 这类带市场前缀的输入也判得对。
     *
     * 这是全仓唯一实现：此前 6 处各写一遍（`take(3)` / `length==6 && startsWith` /
     * `startsWith("60")||startsWith("00")`），口径不一致使 003xxx 在统计路径与推荐路径归属相反。
     */
    fun isMainBoard(code: String): Boolean = code.takeLast(6).take(3) in MAIN_BOARD_PREFIXES

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

    /** 取末 6 位数字，无数字时返回空串。只用于展示/对齐，不校验代码是否真实存在。 */
    fun digits6(raw: String): String = raw.filter { it.isDigit() }.takeLast(6)

    /**
     * 行情接口用的带市场前缀代码（腾讯/东财批量报价要 sh/sz/bj）。
     */
    fun prefixed(code: String): String {
        val n = normalize(code) ?: return code
        if (n.startsWith("hk")) return n
        return when (n.take(2)) {
            "60", "68", "90", "11", "13" -> "sh$n"
            "43", "83", "87", "88", "92" -> "bj$n"
            else -> "sz$n"
        }
    }

    /**
     * 只凭代码判断是否是 A 股主板/创业板/科创板代码，不查代码表。
     * 代码表拉不到时用它兜底搜索，避免把「代码表不可用」误导成「查无此股」。
     */
    /** 可按代码直查的 A 股代码段：主板七段 + 创业板 + 科创板。 */
    val A_SHARE_PREFIXES = MAIN_BOARD_PREFIXES + setOf("300", "301", "688", "689")

    fun directSymbol(raw: String): SymbolEntry? {
        val code = normalize(raw) ?: return null
        if (code.startsWith("hk")) return null
        if (code.take(3) !in A_SHARE_PREFIXES) return null
        return SymbolEntry(code, null)
    }
}
