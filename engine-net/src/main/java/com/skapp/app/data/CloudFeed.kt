package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 云端引擎数据的统一入口。
 *
 * 过去每个模块自己拼 URL、自己建连接、失败就静默返回 null，结果是：云端 90 多个
 * 九十几个 data 下的 json 里 App 只用到 3 个，而且某个文件挂了没人知道。这里把取数收成一条链路：
 *
 *   注册表(Doc) → 并发拉取 → 内存缓存(带 TTL) → 数据陈旧度(按文件自身 ts) → 降级
 *
 * 三种状态明确区分，不混淆：
 *   - `json == null`            拉不到（网络/404），调用方必须自行降级
 *   - `stale == true`           拿到了但文件本身 ts 太旧（云端任务没跑），仍可用但要标注
 *   - 正常                       新鲜可用
 *
 * 云端各文件的 ts 格式并不统一（ISO 带时区 / "yyyy-MM-dd HH:mm:ss" / 只有 date），
 * 这里统一解析成毫秒，解析不出来的按「未知」处理而不是当成新鲜。
 */
object CloudFeed {

    const val BASE = "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data"

    private const val TIMEOUT = 15000

    /** 日更类数据的内存缓存时长：云端最快也是十几分钟一次，缓存 10 分钟足够。口径见 [FreshnessPolicy]。 */
    private const val TTL_MS = FreshnessPolicy.CLOUD_TTL_MS

    /** 超过这个年龄就标记 stale。日更的任务 36 小时没更新，基本可以认为当天没跑成。口径见 [FreshnessPolicy]。 */
    private const val STALE_HOURS = FreshnessPolicy.CLOUD_STALE_HOURS

    /** 云端数据文件注册表。新增一个文件只需在这里加一行。 */
    enum class Doc(val file: String, val note: String) {
        ENGINE_MANIFEST("engine_manifest.json", "引擎参数清单：版本 + sha256 + 回测基线，端上按它校验口径"),
        WEIGHT_CALIBRATION("weight_calibration.json", "16 指标分层权重（v3 校准，5~6 万样本）"),
        WALKFORWARD("walkforward.json", "样本外回测：IS/OOS 胜率与过拟合判定"),
        RPS_ROTATION("rps_rotation.json", "RPS 区间涨幅百分位 Top30"),
        MARKET_BREADTH("market_breadth_history.json", "涨跌停/炸板/封板率历史"),
        STRATEGY_CARD("strategy_card.json", "云端策略卡：规则 + 回测 + 候选"),
        ENGINE_V5("engine_v5.json", "引擎 v5 Top 榜"),
        ENGINE_FUSION("engine_fusion.json", "多引擎加权融合 E 值与一致性"),
        DECISION("decision.json", "云端决策：市况/仓位建议/卡片"),
        MARKET_PHASE("market_phase.json", "市况分档"),
        CYCLE_POOL("cycle_pool_v2.json", "周期池 v2"),
        SECTOR_ENGINE("sector_engine.json", "板块强度引擎"),
        ETF_SHARES("etf_shares.json", "宽基 ETF 份额（按指数聚合）"),
        GLOBAL_MARKETS("global_markets.json", "全球市场情绪"),
        INDUSTRY_MAP("industry_map_em.json", "个股 → 东财三级行业（1050 只，与板块层级不一致，仅兜底）"),
        STOCK_SECTOR_MAP("stock_sector_map.json", "个股 → 板块（按板块成分股反查，与 sector_engine 同源）"),
        AI_PICK_V4("ai_pick_v4.json", "AI 选股 v4"),
        PEAD_EVENTS("pead_events.json", "财报后漂移事件（按公告日倒序，最近 90 天）"),
        LIMIT_HISTORY("limit_history.json", "每日个股涨停名单（云端回算，260 个交易日）"),

        /**
         * 以下六项是「参数/对照」文件，端上只**读值**、不重算（[CloudParams] 是唯一读取口）。
         *
         * 实测 2026-10-29（raw.githubusercontent 直连，UA 正常）：
         *  - `engine_weights.json` / `walkforward.json` / `anomaly_report.json` → 200，可用；
         *  - `central_orchestrator.json` / `tomorrow_candidate_engine.json` /
         *    `morning_pick_engine.json` / `regime_router.json` → 404（云端只写本地 data 目录，
         *    没有发布到远端）。这四项必然走 cold_start 回落，端上用自己的引擎顶上，
         *    等云端哪天发布后无需改代码就会自动生效——所以不能因为 404 就不注册。
         */
        ENGINE_WEIGHTS("engine_weights.json", "引擎权重标定 v1（特征重要性→权重，云端跑出，端上只读）"),
        CENTRAL_REVIEW("central_orchestrator.json", "云端中枢终审（gate×Σv·D 的 S 分；未发布→cold_start）"),
        TOMORROW_CANDIDATE("tomorrow_candidate_engine.json", "明日候选 6 维打分（未发布→端上 EarlyVoteEngine 自算）"),
        MORNING_PICK("morning_pick_engine.json", "早盘 6 维打分（未发布→端上 EarlyVoteEngine 自算）"),
        REGIME("regime_router.json", "市场环境判定与 gate 系数（未发布→端上 RegimeGate 自算）"),
        ANOMALY_REPORT("anomaly_report.json", "当日异动报告（阈值类结论，端上只作对照）")
        ;

        /**
         * 判断类文件（S3）：云端算「今天该给什么结论」的那几个。端上实时链
         * （LocalScreener / EngineFeedLive / NewsCalcEngine）已自算同构结论，
         * 这些只作归档与对照，**不得作为当日结论的供数**——否则会出现两套同日结论并存、
         * 口径互不可验（云端 16 指标标定 vs 端上手写权重）。
         */
        val judgement: Boolean get() = this in JUDGEMENT_DOCS

        companion object {
            val JUDGEMENT_DOCS: Set<Doc> = setOf(
                ENGINE_V5, ENGINE_FUSION, DECISION, MARKET_PHASE,
                CYCLE_POOL, SECTOR_ENGINE, AI_PICK_V4, STRATEGY_CARD,
            )
        }
    }

    data class CloudDoc(
        val doc: Doc,
        val json: JSONObject?,
        /** 本次实际取到数据的时间（毫秒）；命中缓存时是缓存写入时间 */
        val fetchedAt: Long,
        /** 文件自身 ts 距今小时数；解析不出为 null */
        val ageHours: Double?,
        /** 从缓存拿的（而非本次网络获取） */
        val fromCache: Boolean = false,
        val error: String? = null,
    ) {
        val ok: Boolean get() = json != null
        val stale: Boolean get() = ok && ageHours != null && ageHours > STALE_HOURS
        val tsText: String
            get() = when {
                !ok -> "不可用"
                ageHours == null -> "时间未知"
                ageHours < 1 -> "${(ageHours * 60).toInt()} 分钟前"
                ageHours < 48 -> "${ageHours.toInt()} 小时前"
                else -> "${(ageHours / 24).toInt()} 天前"
            }
    }

    private data class Entry(val doc: CloudDoc, val expiresAt: Long)

    private val cache = HashMap<Doc, Entry>()

    private fun now() = System.currentTimeMillis()

    /**
     * 一次拉多个文件：并发，互不拖累；单个失败只影响它自己。
     *
     * [allowJudgement] = false 时，判断类文件（[Doc.judgement]）直接拒供，
     * 只有对照面板（`Db.compareDocs`）显式放行才能读到——防止两套同日结论并存。
     */
    suspend fun loadMany(
        // 收 Collection 而不是 List：Db.docs(wanted: Collection<Doc>) 直接转交过来，
        // 声明成 List 会让「按 Set 去重后批量取」的调用方编译不过（Db.kt:98）。
        docs: Collection<Doc>,
        force: Boolean = false,
        allowJudgement: Boolean = false,
    ): Map<Doc, CloudDoc> = coroutineScope {
        docs.map { d -> async { d to load(d, force, allowJudgement) } }.awaitAll().toMap()
    }

    suspend fun load(
        doc: Doc,
        force: Boolean = false,
        allowJudgement: Boolean = false,
    ): CloudDoc {
        // 判断类文件：端上自算同构结论，这里拒供（不进缓存、不发请求）
        if (shouldRefuseJudgement(doc, allowJudgement)) return refused(doc)
        if (!force) {
            synchronized(cache) {
                cache[doc]?.let { e ->
                    if (e.expiresAt > now()) return e.doc.copy(fromCache = true)
                }
            }
        }
        val fetched = withContext(Dispatchers.IO) { fetch(doc) }
        if (fetched.ok) {
            synchronized(cache) { cache[doc] = Entry(fetched, now() + TTL_MS) }
        } else {
            // 拉失败时退回旧缓存（哪怕过期），让 UI 还能显示上次的数据并标陈旧
            synchronized(cache) { cache[doc] }?.let { old ->
                return old.doc.copy(fromCache = true, error = fetched.error)
            }
        }
        return fetched
    }

    /** 判断类拒供判定（纯函数，[load] 与测试共用；不触网）。 */
    internal fun shouldRefuseJudgement(doc: Doc, allowJudgement: Boolean): Boolean =
        doc.judgement && !allowJudgement

    /** 拒供占位：`ok == false`，`error` 写明原因，界面按「未加载」处理。 */
    private fun refused(doc: Doc): CloudDoc = CloudDoc(
        doc = doc,
        json = null,
        fetchedAt = now(),
        ageHours = null,
        error = ERROR_JUDGEMENT_REFUSED,
    )

    /** 判断类文件拒供时的错误文案（界面/测试共用一个口径）。 */
    const val ERROR_JUDGEMENT_REFUSED = "judgement-refused：端上自算，仅对照面板可读"

    private fun fetch(doc: Doc): CloudDoc {
        var conn: HttpURLConnection? = null
        val at = now()
        return try {
            conn = (URL("$BASE/${doc.file}").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
            }
            if (conn.responseCode != 200) {
                return CloudDoc(doc, null, at, null, error = "HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            CloudDoc(doc, json, at, ageHoursOf(json))
        } catch (e: Exception) {
            CloudDoc(doc, null, at, null, error = e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** 各文件的 ts 字段格式不一致，逐个试；都失败返回 null（宁可不判新鲜，也不假定新鲜）。 */
    private fun ageHoursOf(json: JSONObject): Double? {
        val raw = json.optString("ts").takeIf { it.isNotBlank() }
            ?: json.optString("recorded_at").takeIf { it.isNotBlank() }
            ?: json.optString("generated_at").takeIf { it.isNotBlank() }
            ?: return null
        val ms = parseStamp(raw) ?: return null
        val diff = now() - ms
        return if (diff < 0) 0.0 else diff / 3_600_000.0
    }

    private val stampFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",   // 2026-10-27T00:10:02+08:00
        "yyyy-MM-dd'T'HH:mm:ss",      // 2026-10-26T23:51:08
        "yyyy-MM-dd HH:mm:ss",        // 2026-10-26 23:51:08
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
    )

    private fun parseStamp(raw: String): Long? {
        // 去掉毫秒（2026-10-26T23:51:08.123+08:00 -> 2026-10-26T23:51:08+08:00）
        val s = raw.trim().replace(Regex("""\.\d+"""), "")
        for (f in stampFormats) {
            try {
                val fmt = SimpleDateFormat(f, Locale.CHINA).apply { isLenient = true }
                return fmt.parse(s)?.time
            } catch (_: Exception) {
                // 试下一个
            }
        }
        return null
    }

    fun invalidate() = synchronized(cache) { cache.clear() }

    /** 给界面用的一句话数据健康度：几个文件可用 / 几个陈旧。 */
    fun healthText(docs: Collection<CloudDoc>): String {
        val list = docs.toList()
        if (list.isEmpty()) return "未加载"
        val ok = list.count { it.ok }
        val stale = list.count { it.stale }
        val fail = list.size - ok
        return buildString {
            append("$ok/${list.size} 可用")
            if (stale > 0) append(" · $stale 陈旧")
            if (fail > 0) append(" · $fail 失败")
        }
    }
}
