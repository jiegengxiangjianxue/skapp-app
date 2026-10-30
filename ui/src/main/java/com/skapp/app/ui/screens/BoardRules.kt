package com.skapp.app.ui.screens

/** 推荐/补涨输出的交易权限红线：仅沪深主板。 */
internal fun isMainBoardPick(code: String): Boolean = com.skapp.app.data.Code.isMainBoard(code)

internal fun normalizeScreenCode(code: String): String = com.skapp.app.data.Code.digits6(code)
