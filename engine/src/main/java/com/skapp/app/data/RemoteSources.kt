package com.skapp.app.data

/**
 * 远端数据源地址。app 只读 raw.githubusercontent.com 静态 JSON（无服务端）。
 *
 * 布局说明：base 已定位到仓库的 data 目录，appFile 只追加文件名，避免形成 data/data。
 */
object RemoteSources {

    const val RAW = "https://raw.githubusercontent.com"

    /** 行情类公开接口使用的 Android 浏览器 UA（如实声明客户端，不做指纹伪装） */
    const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    /** A 仓：主供数 + API 树 + AI 问答（主力数据仓 qwerzxcva） */
    const val OWNER_A = "qwerzxcva"
    const val REPO_A = "stocks-ai-view"

    /** C 仓：真镜像（与 A 同内容，独立账号/独立 CI 2 分钟调度） */
    const val OWNER_C = "jiegengxiangjianxue"
    const val REPO_C = "stocks-ai-view-b"

    /** 全市场代码表（jiegengxiangjianxue/skapp-data，qwerzxcva 侧已并入删除） */
    const val OWNER_SYMBOLS = "jiegengxiangjianxue"
    const val REPO_SYMBOLS = "skapp-data"

    /** 个股分片仓（db-stocks-*）：stock.json 全史 K 线/技术面/财务
     *  只留真实存在的分片仓；15/51/50/52/56/58（基金/可转债）与 81/83/89/90/92 远端无仓。 */
    val SHARD_REPOS = mapOf(
        "00" to "jiegengxiangjianxue1/db-stocks-a",
        "30" to "jiegengxiangjianxue2/db-stocks-b",
        "60" to "jiegengxiangjianxue2/db-stocks-c",
        "68" to "jiegengxiangjianxue3/db-stocks-d",
        "20" to "jiegengxiangjianxue3/db-stocks-d",
    )

    /** 腾讯实时行情（app 自选股 30s 刷新用，与站点直连兜底同源） */
    const val QUOTE_QT = "https://qt.gtimg.cn/q="

    /** 腾讯前复权日 K（个股详情 K 线图） */
    fun tencentKline(prefixed: String, count: Int): String =
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" +
            "$prefixed,day,,,$count,qfq"

    fun base(owner: String, repo: String, branch: String = "main"): String =
        "$RAW/$owner/$repo/$branch/data"

    /** app 读取 data 目录下的文件。base 已包含一个 data 段，不能重复追加。 */
    fun appFile(owner: String, repo: String, name: String): String =
        "${base(owner, repo)}/$name"

    fun symbols(owner: String = OWNER_SYMBOLS, repo: String = REPO_SYMBOLS): String =
        "${RAW}/$owner/$repo/main/data/search/symbols.json"

    /** 静态 API */
    fun apiIndex(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/index.json"

    fun apiMarket(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/market.json"

    /** 消息面: 站点 news_* 四个引擎 + hot_news/news_digest 聚合 */
    fun apiNews(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/news.json"

    /** 可独立回写的新闻附加与逐条影响分析产物。 */
    fun newsExtra(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "news_extra.json")

    fun newsImpact(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "news_impact.json")

    fun newsImpactV2(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "news_impact_v2.json")

    /** 诊断页使用的真实外部探针地址，避免 UI 散落 raw URL。 */
    fun quoteProbe(): String = "${QUOTE_QT}sh600519"

    fun news724Probe(): String =
        "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns" +
            "?client=web&biz=web7_24&fastColumn=100&column=100&pageSize=5&sortEnd=&sortType=0"

    fun themeReport(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "theme_report.json")

    fun dailyBrief(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "daily_brief.json")

    fun allSources(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "all_sources.json")

    /** Static JSON files used by the website's multi-pool view. */
    fun poolFile(name: String, owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, name)

    fun premarketPicks(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "premarket_picks.json")

    fun middayPicks(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "midday_picks.json")

    fun closeReview(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "close_review.json")

    fun winrateMatrix(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "winrate_matrix.json")

    fun latest(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "latest.json")

    fun trendStrength(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "trend_strength.json")

    fun engineV5(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "engine_v5.json")

    fun enrich(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "enrich.json")

    fun bullBearDebate(owner: String = OWNER_A, repo: String = REPO_A): String =
        appFile(owner, repo, "bull_bear_debate.json")

    /** 板块: 行业/概念/板块池/成分股(移植站点 sector 面板) */
    fun apiSectors(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/sectors.json"

    /** 数据源台账（登记/可用/已接执行器/缺口/不可用） */
    fun apiSources(owner: String = OWNER_A, repo: String = REPO_A): String =
        "${base(owner, repo)}/api/sources.json"

    fun apiStock(owner: String, repo: String, code: String): String =
        "${base(owner, repo)}/api/stocks/$code.json"
    fun apiQa(owner: String, repo: String, code: String): String =
        "${base(owner, repo)}/api/qa/$code.json"

    fun shardBundle(code6: String): String? {
        val repo = SHARD_REPOS[code6.take(2)] ?: return null
        return "$RAW/$repo/main/${code6.take(2)}/$code6/stock.json"
    }
}
