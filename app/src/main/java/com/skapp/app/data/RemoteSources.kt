package com.skapp.app.data

/**
 * 远端数据源地址。app 只读 raw.githubusercontent.com 静态 JSON（无服务端）。
 *
 * 布局说明（2026-10-21 修复）：app 期望 base(…/data) + "/data/<file>"，
 * 因此三个仓都必须有 data/data/ 双 data 路径。
 */
object RemoteSources {

    const val RAW = "https://raw.githubusercontent.com"

    /** A 仓：主供数 + API 树 + AI 问答（主力数据仓 qwerzxcva） */
    const val OWNER_A = "qwerzxcva"
    const val REPO_A = "stocks-ai-view"

    /** C 仓：镜像 */
    const val OWNER_C = "qwerzxcva"
    const val REPO_C = "stocks-ai-view"

    /** 全市场代码表（qwerzxcva/skapp-data） */
    const val OWNER_SYMBOLS = "qwerzxcva"
    const val REPO_SYMBOLS = "skapp-data"

    /** 个股分片仓（db-stocks-*）：stock.json 全史 K 线/技术面/财务 */
    val SHARD_REPOS = mapOf(
        "00" to "jiegengxiangjianxue1/db-stocks-a",
        "30" to "jiegengxiangjianxue2/db-stocks-b",
        "60" to "jiegengxiangjianxue2/db-stocks-c",
        "68" to "jiegengxiangjianxue3/db-stocks-d",
        "15" to "jiegengxiangjianxue3/db-stocks-d",
        "51" to "jiegengxiangjianxue3/db-stocks-d",
        "50" to "jiegengxiangjianxue3/db-stocks-d",
        "52" to "jiegengxiangjianxue3/db-stocks-d",
        "56" to "jiegengxiangjianxue3/db-stocks-d",
        "58" to "jiegengxiangjianxue3/db-stocks-d",
        "20" to "jiegengxiangjianxue3/db-stocks-d",
        "81" to "jiegengxiangjianxue3/db-stocks-e",
        "83" to "jiegengxiangjianxue3/db-stocks-e",
        "89" to "jiegengxiangjianxue3/db-stocks-e",
        "90" to "jiegengxiangjianxue3/db-stocks-e",
        "92" to "jiegengxiangjianxue3/db-stocks-e",
    )

    /** 腾讯实时行情（app 自选股 30s 刷新用，与站点直连兜底同源） */
    const val QUOTE_QT = "https://qt.gtimg.cn/q="

    /** 腾讯前复权日 K（个股详情 K 线图） */
    fun tencentKline(prefixed: String, count: Int): String =
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" +
            "$prefixed,day,,,$count,qfq"

    fun base(owner: String, repo: String, branch: String = "main"): String =
        "$RAW/$owner/$repo/$branch/data"

    /** app 读取的四个文件（双 data 路径） */
    fun appFile(owner: String, repo: String, name: String): String =
        "${base(owner, repo)}/data/$name"

    /** 静态 API */
    fun apiIndex(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/index.json"

    fun apiMarket(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/market.json"

    /** 消息面: 站点 news_* 四个引擎 + hot_news/news_digest 聚合 */
    fun apiNews(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/news.json"

    /** 板块: 行业/概念/板块池/成分股(移植站点 sector 面板) */
    fun apiSectors(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/sectors.json"

    fun apiStock(owner: String, repo: String, code: String): String =
        "${base(owner, repo)}/api/stocks/$code.json"

    fun apiQa(owner: String, repo: String, code: String): String =
        "${base(owner, repo)}/api/qa/$code.json"

    fun shardBundle(code6: String): String? {
        val repo = SHARD_REPOS[code6.take(2)] ?: return null
        return "$RAW/$repo/main/${code6.take(2)}/$code6/stock.json"
    }
}
