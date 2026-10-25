package com.skapp.app.ui.screens

/** 推荐/补涨输出的交易权限红线：仅沪深主板。 */
internal fun isMainBoardPick(code: String): Boolean =
    code.takeLast(6).take(3) in setOf("600", "601", "603", "605", "000", "001", "002")

internal fun normalizeScreenCode(code: String): String =
    code.filter { it.isDigit() }.takeLast(6)
