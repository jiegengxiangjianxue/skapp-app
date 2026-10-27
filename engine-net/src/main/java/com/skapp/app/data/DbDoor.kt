package com.skapp.app.data

/**
 * 库表门面：界面只能通过这里碰云端。
 *
 * ## 为什么要有这一层
 *
 * 数据来源规则是「**只有数据库可以调用云端**」。`:ui` 之前有几十处直接
 * `SkappApi.fetchXxx()` / `SiteBoardClient.xxx()`，等于界面绕过库层自己吃云端快照。
 * 迁移时如果只是把 `SkappApi` 换成另一个名字，规则就形同虚设——所以统一收进
 * `DbTables`，语义上就是「库里的一张表」：看到 `DbTables.news()` 就知道
 * 这是**已发生的归档/配置表**，不是实时数据。
 *
 * ## 每条都标了口径
 *
 * - **归档类**（news / closeReview / winrate / sectors / theme / summary / board）：
 *   云端 CI 收盘后产出，**只用于回看**，不能当今日判断。当日结论走
 *   `LivePulse` / `LocalSnapshot` / `LocalSectors` / `LocalScreener`。
 * - **配置表类**（symbols / stockNames / dataSources / pools 名单）：
 *   一天变一次，实时接口给不全，只能读库（成分行情仍由本地实时重算）。
 * - **明细类**（stockDetail / financial / newsImpact / qa）：
 *   按需单票请求-响应，本来就不是「实时行情」。
 */
object DbTables {

    /** 全市场代码表（配置表）。 */
    suspend fun symbols(): List<SymbolEntry> = Db.symbols()

    /** 代码 → 名称（配置表）。 */
    suspend fun stockNames(): Map<String, String> = Db.stockNames()

    /** 收盘复盘归档（云端 CI 产出，只回看）。 */
    suspend fun closeReview(): CloseReview = SkappApi.fetchCloseReview()

    /** 胜率矩阵（云端 CI 回测产出，只回看）。 */
    suspend fun winrate(): WinrateMatrix = SkappApi.fetchWinrateMatrix()

    /** 消息面归档：爬取 + 四引擎分类的产物，非实时行情。 */
    suspend fun news(): NewsSnapshot = SkappApi.fetchNews()

    /**
     * 板块归档三张表（板块强弱 / 成分映射 / 行业映射）。
     *
     * 当日板块强弱请用 LocalSectors（实时快照 × 本地映射现算）；
     * 这里只提供历史归档与映射表兜底，界面拿到时须标「回看」。
     */
    suspend fun sectors(): SectorsSnapshot = SkappApi.fetchSectors()

    /** 数据源台账（配置表）。 */
    suspend fun dataSources(): DataSourcesSnapshot = SkappApi.fetchDataSources()

    /** 单票档案（基本面/公司资料，按需查询）。 */
    suspend fun stockDetail(code: String): StockDetail? = SkappApi.fetchStockDetail(code)

    /** 单票财务（按需查询）。 */
    suspend fun financial(code: String): Map<String, Any?>? = SkappApi.fetchStockFinancial(code)

    /** 消息 → 个股影响归档（随 news 一起产出）。 */
    suspend fun newsImpact(): List<Map<String, Any?>> = SkappApi.fetchNewsImpact()

    /** 池子名单（配置表：云端维护的选股池构成，成分行情由 repricePools 本地实时重算）。 */
    suspend fun pools(): List<SiteBoardClient.Pool> = SiteBoardClient.pools()

    /** 看板归档（latest.json / market_now.json）：只回看，当日看板走 LocalSectors.boardMap。 */
    suspend fun board(): Map<String, Any?> = SiteBoardClient.board()

    /** 主题报告归档（只回看）。 */
    suspend fun theme(): org.json.JSONObject? = SiteBoardClient.themeReport()

    /** 每日简评归档（只回看）。 */
    suspend fun summary(): Map<String, Any?> = SiteBoardClient.summary()

    /** 市场宽度归档（只回看；当日宽度走 LocalSectors.breadth）。 */
    suspend fun marketBreadth(): Map<String, Any?> = SiteBoardClient.marketBreadth()

    /** 池子成分本地实时补价（不是云端调用：先吃全市场快照，缺的才补拉）。 */
    suspend fun repricePools(pools: List<SiteBoardClient.Pool>): List<SiteBoardClient.Pool> =
        SiteBoardClient.repriceLive(pools)

    /** 资金流归档（云端 CI 产出，只回看；当日资金走 LivePulse.mainNetTotal / inflow / outflow）。 */
    suspend fun smartMoney(): SmartMoney.Signal? = SmartMoney.fetch()

    /** AI 问答归档（按需查询）。 */
    suspend fun qa(code: String): AiQa? = SkappApi.fetchQa(code)
}
