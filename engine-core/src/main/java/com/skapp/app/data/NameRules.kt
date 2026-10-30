package com.skapp.app.data

/** 名称类判定（纯字符串，零 IO）。 */
object NameRules {

    /**
     * ST / 退市整理股：推荐、评分、补涨候选口径里要排除的对象。
     *
     * 注意与 [LiveMarket] 的 5% 涨停判定不同：那里只认 ST，不认「退」——退市整理期涨跌幅
     * 仍是 10%，判成 5% 会把普通波动误标成涨停。两处语义不同，不要合并。
     */
    fun isSTOrDelisting(name: String?): Boolean =
        name != null && (name.contains("ST", ignoreCase = true) || name.contains("退"))
}
