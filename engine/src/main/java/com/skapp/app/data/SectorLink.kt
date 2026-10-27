package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 板块主线：把云端 sector_engine（27 个板块的强弱分档）落到个股上。
 *
 * 为什么单独成一层、不并进 FactorWeightStrategy 的加权分：
 * 云端对 16 个技术指标做过样本标定（有 bull/bear 胜率、有 rel_weight），但**没有**对
 * 板块强弱做过同等标定——sector_engine 给的是 B/C/D 分档和 7 个维度的分数，没有「B 档板块
 * 的个股次日胜率多少」这种结论。没有标定就编一个权重塞进加权求和，那是拍脑袋，
 * 前面刚把技术指标的权重做成可追溯的，这里不能倒回去。
 *
 * 所以这一层只做「并列的第二道过滤」，和因子否决交叉使用：
 *   - 因子 AVOID 或 板块规避 → 双重否决，界面上直接标出来；
 *   - 因子 PASS 且 板块主线 → 共振，值得多看一眼；
 *   - 两者矛盾时（如因子偏多但板块在 avoid）→ 照实说「矛盾」，不替用户裁决。
 *
 * 数据来源：
 *   sector_engine.json  —— 27 个板块 grade/total/7 维分/龙头/涨停数/资金净额，另带 mainline 与 avoid 名单
 *   industry_map_em.json —— 1050 只个股 → 东财行业，用于把个股落到板块
 */
object SectorLink {

    /** 个股归属的数据来源。两者覆盖率差一个数量级，界面上必须说清用的是哪份。 */
    enum class MapSource(val label: String) {
        STOCK_SECTOR_MAP("成分股反查"),
        INDUSTRY_MAP_EM("行业名映射（兜底）"),
        NONE("无"),
    }

    /** 板块在个股身上的立场 */
    enum class Stance(val label: String) {
        MAINLINE("主线"),
        NEUTRAL("中性"),
        WEAK("弱势"),
        AVOID("板块规避"),
        UNKNOWN("未知板块"),
    }

    data class SectorRow(
        val name: String,
        val grade: String,
        val total: Double,
        val momentum: Double?,
        val capital: Double?,
        val breadth: Double?,
        val avgChg1d: Double?,
        val chg5d: Double?,
        val chg20d: Double?,
        val leader: String?,
        val leaderPct: Double?,
        val nZt: Int,
        val nUp: Int,
        val nDown: Int,
        val capitalNet: Double?,
    )

    data class Board(
        val ts: String,
        val date: String,
        val sectors: List<SectorRow>,
        val mainline: List<String>,
        val avoid: List<String>,
        /** 6 位代码 → 板块名（云端 industry_map_em 的 map） */
        val stockToSector: Map<String, String>,
        val mapCaveat: String = "",
        /** 个股归属用哪份数据：stock_sector_map（首选）/ industry_map_em（兜底）/ 无 */
        val mapSource: MapSource = MapSource.NONE,
        /** 映射覆盖的板块总数（全市场约 127）；sector_engine 只跟踪其中一部分 */
        val mappedSectorTotal: Int = 0,
    ) {
        private val byName: Map<String, SectorRow> = sectors.associateBy { it.name }

        fun sectorOf(code: String): SectorRow? {
            val digits = code.filter { it.isDigit() }.takeLast(6)
            val name = stockToSector[digits] ?: return null
            return byName[name]
        }

        fun sectorNameOf(code: String): String? {
            val digits = code.filter { it.isDigit() }.takeLast(6)
            return stockToSector[digits]
        }

        /**
         * 个股所属板块的立场。
         * 判定顺序：avoid 名单优先（云端点名要避的，不管分数多高）→ mainline → 按 grade 分档。
         */
        fun stance(code: String): Stance {
            val name = sectorNameOf(code) ?: return Stance.UNKNOWN
            if (name in avoid) return Stance.AVOID
            val row = byName[name] ?: return Stance.UNKNOWN
            return when {
                name in mainline && row.grade <= "B" -> Stance.MAINLINE
                name in mainline -> Stance.MAINLINE
                row.grade == "D" -> Stance.WEAK
                row.grade == "C" -> Stance.NEUTRAL
                else -> Stance.NEUTRAL
            }
        }

        fun rowOf(code: String): SectorRow? = sectorOf(code)

        /** 主线板块的完整行（按 total 降序），用于界面展示 */
        val mainlineRows: List<SectorRow>
            get() = sectors.filter { it.name in mainline }.sortedByDescending { it.total }

        /** 强势板块前 N（排除 avoid 名单） */
        fun topSectors(n: Int = 5): List<SectorRow> =
            sectors.filter { it.name !in avoid }.sortedByDescending { it.total }.take(n)
    }

    // ── 解析 ────────────────────────────────────────────────

    /**
     * mapJson 可能是两种格式，字段名都叫 map：
     *   - stock_sector_map.json：{ map: { "601811": "出版" } }        值是板块名，与 sector_engine 同源
     *   - industry_map_em.json：{ map: { "002543": "卫浴电器" } }      值是三级行业名，层级不同
     * 前者优先；后者只在拿不到前者时用来兜底，且界面上要标出来。
     */
    fun parse(
        sectorJson: JSONObject?,
        mapJson: JSONObject?,
        source: MapSource = MapSource.INDUSTRY_MAP_EM,
    ): Board? {
        if (sectorJson == null) return null
        val arr = sectorJson.optJSONArray("sectors") ?: return null
        val rows = ArrayList<SectorRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("sector").takeIf { it.isNotBlank() } ?: continue
            val dims = o.optJSONObject("dims")
            rows.add(
                SectorRow(
                    name = name,
                    grade = o.optString("grade").ifBlank { "?" },
                    total = o.optDouble("total", 0.0),
                    momentum = num(dims, "momentum"),
                    capital = num(dims, "capital"),
                    breadth = num(dims, "breadth"),
                    avgChg1d = num(o, "avg_chg_1d"),
                    chg5d = num(o, "chg_5d"),
                    chg20d = num(o, "chg_20d"),
                    leader = o.optString("leader").takeIf { it.isNotBlank() },
                    leaderPct = num(o, "leader_pct"),
                    nZt = o.optInt("n_zt", 0),
                    nUp = o.optInt("n_up", 0),
                    nDown = o.optInt("n_down", 0),
                    capitalNet = num(o, "capital_net"),
                )
            )
        }
        if (rows.isEmpty()) return null

        val mainline = strList(sectorJson.optJSONArray("mainline"))
        val avoid = strList(sectorJson.optJSONArray("avoid"))

        // industry_map_em: { map: { "002543": "卫浴电器", ... } }
        val stockToSector = HashMap<String, String>(2048)
        mapJson?.optJSONObject("map")?.let { m ->
            val keys = m.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = m.optString(k)
                if (v.isNotBlank()) stockToSector[k.filter { it.isDigit() }.takeLast(6)] = v
            }
        }

        return Board(
            ts = sectorJson.optString("ts"),
            date = sectorJson.optString("date"),
            sectors = rows,
            mainline = mainline,
            avoid = avoid,
            stockToSector = stockToSector,
            mapCaveat = mapJson?.optString("caveat") ?: mapJson?.optString("note") ?: "",
            mapSource = if (stockToSector.isEmpty()) MapSource.NONE else source,
            mappedSectorTotal = mapJson?.optInt("n_sectors", 0) ?: 0,
        )
    }

    private fun strList(a: org.json.JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }

    // ── 加载 ────────────────────────────────────────────────

    /**
     * 取板块表 + 个股归属。
     *
     * 优先 stock_sector_map.json（按板块成分股反查，与 sector_engine 同源，覆盖全市场）；
     * 拿不到才退回 industry_map_em.json——后者的行业层级和板块不一致，实测 27 个板块只对上
     * 8 个、1050 只票里只有 14 只落到板块上，基本等于没有，所以必须标明来源。
     */
    suspend fun load(force: Boolean = false): Board? = withContext(Dispatchers.IO) {
        val docs = CloudFeed.loadMany(
            listOf(
                CloudFeed.Doc.SECTOR_ENGINE,
                CloudFeed.Doc.STOCK_SECTOR_MAP,
                CloudFeed.Doc.INDUSTRY_MAP,
            ),
            force,
        )
        val sectorJson = docs[CloudFeed.Doc.SECTOR_ENGINE]?.json ?: return@withContext null
        val full = docs[CloudFeed.Doc.STOCK_SECTOR_MAP]?.json
        if (full != null) {
            parse(sectorJson, full, MapSource.STOCK_SECTOR_MAP)?.let { return@withContext it }
        }
        parse(sectorJson, docs[CloudFeed.Doc.INDUSTRY_MAP]?.json, MapSource.INDUSTRY_MAP_EM)
    }

    /** 能落到 sector_engine 板块表上的股票数（映射本身覆盖全市场，板块表只跟踪其中一部分） */
    fun mappedCount(board: Board): Int =
        board.stockToSector.keys.count { board.sectorOf(it) != null }

    /** 个股归属的覆盖率：能落到 sector_engine 板块上的股票占比，界面用它决定要不要发警告 */
    fun coverage(board: Board): Double {
        if (board.stockToSector.isEmpty()) return 0.0
        val mapped = board.stockToSector.keys.count { board.sectorOf(it) != null }
        return mapped.toDouble() / board.stockToSector.size
    }
}
