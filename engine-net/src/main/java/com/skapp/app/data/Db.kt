package com.skapp.app.data

import org.json.JSONObject

/**
 * 数据库层 —— **全 App 唯一允许访问云端的门**。
 *
 * ## 规则（用户要求：「只有数据库可以调用云端」）
 *
 * 分析链路（[LocalScreener] / [LocalEngine] / [LivePulse] / [LiveMarket] / 各引擎）
 * 只吃**盘中实时数据**，一律不得直接读写云端。云端在这个 App 里的角色只有一个：
 * **库**——放那些「一天/一周才变一次」的表和历史归档。
 *
 * 允许进来的东西只有四类，别的都别塞：
 *
 * | 类别 | 例子 | 为什么算「库」 |
 * |---|---|---|
 * | 代码表 | symbols / stock_names | 全市场名字表，一天一变，实时接口给不全 |
 * | 映射表 | 板块成分股 / 行业映射 | 是「关系表」，不是当日行情 |
 * | 标定参数 | weight_calibration / walkforward | 5~6 万样本回归出来的系数，端上算不出来 |
 * | 历史归档 | close_review / engine_v5 / decision | 已发生的事实，可作对照，**不得当今日判断** |
 *
 * 反例（这些必须实时算，不许进这个门）：
 * 当日行情、当日涨跌、当日资金、当日推荐、当日市场相位。
 *
 * ## 为什么门要做成一个对象
 *
 * 之前 `CloudFeed.` / `SkappApi.fetchX` 散在十几个文件里，「哪段分析偷偷读了云端快照」根本查不出来。
 * 收成 [Db] 之后，`tools/layer_source_guard.py` 只要盯住一个规则：
 * 除本文件与 [CloudFeed] 自身，任何模块出现 `CloudFeed.` / `SkappApi.fetch` 就是违规。
 */
/** 归档文档标识：调用方用 [ArchiveDoc] 即可，不必直接 import CloudFeed。 */
typealias ArchiveDoc = CloudFeed.Doc

object Db {

    // ── 库表 ────────────────────────────────────────────────

    /** 全市场代码表（当日可用，一天变一次，实时接口给不全）。 */
    suspend fun symbols(): List<SymbolEntry> = SkappApi.fetchSymbols()

    /** 代码 → 名称（与代码表同源）。 */
    suspend fun stockNames(): Map<String, String> = SkappApi.fetchStockNames()

    /** 板块三张表：板块强弱引擎 / 成分股映射 / 行业映射。 */
    suspend fun sectorTables(force: Boolean = false): SectorTables {
        val docs = CloudFeed.loadMany(
            listOf(
                CloudFeed.Doc.SECTOR_ENGINE,
                CloudFeed.Doc.STOCK_SECTOR_MAP,
                CloudFeed.Doc.INDUSTRY_MAP,
            ),
            force,
        )
        return SectorTables(
            sector = docs[CloudFeed.Doc.SECTOR_ENGINE]?.json,
            stockSectorMap = docs[CloudFeed.Doc.STOCK_SECTOR_MAP]?.json,
            industryMap = docs[CloudFeed.Doc.INDUSTRY_MAP]?.json,
        )
    }

    /** 权重标定参数（walkforward 样本外 + 分市场状态的权重表）。 */
    suspend fun calibration(force: Boolean = false): CalibrationTables {
        val docs = calibrationDocs(force)
        return CalibrationTables(
            walkforward = docs[CloudFeed.Doc.WALKFORWARD]?.json,
            weightCalibration = docs[CloudFeed.Doc.WEIGHT_CALIBRATION]?.json,
        )
    }

    /** 同上，但保留「是否陈旧/抓取时间」等状态，界面上的数据说明要用。 */
    suspend fun calibrationDocs(force: Boolean = false): Map<ArchiveDoc, CloudFeed.CloudDoc> =
        archiveMany(listOf(CloudFeed.Doc.WALKFORWARD, CloudFeed.Doc.WEIGHT_CALIBRATION), force)

    /** 数据新鲜度说明（纯格式化，转发 CloudFeed 实现，避免界面层 import CloudFeed）。 */
    fun healthText(docs: Collection<CloudFeed.CloudDoc>): String = CloudFeed.healthText(docs)

    /** ETF 份额（交易所每日披露，端上拿不到，只能当库数据）。 */
    suspend fun etfShares(force: Boolean = false): JSONObject? = etfSharesState(force).json

    /** 财报后漂移事件表（云端按公告日倒序，最近 90 天）。 */
    suspend fun peadEvents(force: Boolean = false): JSONObject? =
        CloudFeed.loadMany(listOf(CloudFeed.Doc.PEAD_EVENTS), force)[CloudFeed.Doc.PEAD_EVENTS]?.json

    /** 每日个股涨停名单历史（云端回算，替代端上本地囤日级快照）。 */
    suspend fun limitHistory(force: Boolean = false): JSONObject? =
        CloudFeed.loadMany(listOf(CloudFeed.Doc.LIMIT_HISTORY), force)[CloudFeed.Doc.LIMIT_HISTORY]?.json

    /** 同上，但要「是否陈旧」时用这个。 */
    suspend fun etfSharesState(force: Boolean = false): CloudFeed.CloudDoc =
        CloudFeed.load(CloudFeed.Doc.ETF_SHARES, force)

    // ── 历史归档 ────────────────────────────────────────────

    /**
     * 云端历史产物（engine_v5 / decision / close_review …）。
     *
     * ⚠️ 这些是**已发生的归档**，只能用于「回看/对照/兜底展示」。
     * 当日分析结论必须由本地实时数据算，不许把这里的字段直接当今日判断。
     */
    suspend fun archive(doc: ArchiveDoc, force: Boolean = false): CloudFeed.CloudDoc =
        CloudFeed.load(doc, force)

    suspend fun archiveMany(
        docs: List<ArchiveDoc>,
        force: Boolean = false,
    ): Map<ArchiveDoc, CloudFeed.CloudDoc> = CloudFeed.loadMany(docs, force)

    /** 任意云端 JSON（个别库表没进 CloudFeed 目录时用，例如封板概率表）。 */
    suspend fun json(url: String): JSONObject? = SkappApi.fetchJson(url)

    data class SectorTables(
        val sector: JSONObject?,
        val stockSectorMap: JSONObject?,
        val industryMap: JSONObject?,
    )

    data class CalibrationTables(
        val walkforward: JSONObject?,
        val weightCalibration: JSONObject?,
    )
}
