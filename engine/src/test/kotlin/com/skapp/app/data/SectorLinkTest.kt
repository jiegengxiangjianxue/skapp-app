package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 板块层的验证。重点有两个：
 *   1. 板块级的东西（分档、主线、回避名单）必须如实解析出来；
 *   2. **个股归属的覆盖率必须如实反映，不能因为查到就当作全覆盖**——
 *      云端 industry_map_em 只有 1050 只票 / 80 个行业，而且行业名与 sector_engine
 *      不是同一层级（「白酒」vs「白酒Ⅲ」），实际只有少数板块能对上。
 *      测试里把「多数股票查不到板块」这件事钉住，防止以后有人加个模糊匹配把 UNKNOWN 消掉。
 */
class SectorLinkTest {

    private val root = listOf(File("src/test/resources"), File("engine/src/test/resources"))
        .first { it.exists() }

    private fun json(name: String) = JSONObject(File(root, name).readText())

    private fun board(): SectorLink.Board {
        val b = SectorLink.parse(json("sector_engine.json"), json("industry_map_em.json"))
        return assertNotNull(b, "sector_engine 解析失败")
    }

    @Test
    fun `板块分档与名单如实解析`() {
        val b = board()
        println("ts=${b.ts} date=${b.date} 板块数=${b.sectors.size} 主线=${b.mainline} 回避=${b.avoid.size} 个")
        assertEquals(27, b.sectors.size, "云端 27 个板块")
        assertEquals(listOf("出版", "白酒"), b.mainline)
        assertTrue(b.avoid.size >= 6, "回避名单应被解析出来，实际 ${b.avoid.size}")

        // 分档只应有 B/C/D
        val grades = b.sectors.map { it.grade }.toSet()
        println("分档: $grades")
        assertTrue(grades.all { it in setOf("B", "C", "D") }, "意外分档: $grades")

        // 最高分板块应能给出龙头与涨停数
        val top = b.sectors.maxByOrNull { it.total }!!
        println("最高分: ${top.name} ${top.grade} ${top.total} 龙头=${top.leader} 涨停=${top.nZt}")
        assertTrue(top.total > 0)
    }

    @Test
    fun `强势榜会排除回避名单`() {
        val b = board()
        val top = b.topSectors(10)
        val leaked = top.map { it.name }.filter { it in b.avoid }
        println("强势榜: ${top.map { "${it.name}(${it.grade} ${it.total})" }}")
        assertTrue(leaked.isEmpty(), "回避名单里的板块不应出现在强势榜: $leaked")
        assertTrue(top.size <= 10)
        assertEquals(top.map { it.total }, top.map { it.total }.sortedDescending(), "强势榜应按分数降序")
    }

    @Test
    fun `个股归属查得到就是主线查不到就是未知——绝不模糊匹配`() {
        val b = board()
        println("个股归属覆盖 ${b.stockToSector.size} 只 / 板块 ${b.sectors.size} 个")
        assertTrue(b.stockToSector.isNotEmpty(), "至少要有一部分映射")

        // 出版是主线板块，industry_map 里有它的成分股
        val pubStocks = b.stockToSector.filterValues { it == "出版" }.keys.take(3)
        println("出版成分股样本: $pubStocks")
        if (pubStocks.isNotEmpty()) {
            val st = b.stance(pubStocks.first())
            println("${pubStocks.first()} → ${st.label}（板块 ${b.sectorNameOf(pubStocks.first())}）")
            assertEquals(SectorLink.Stance.MAINLINE, st, "出版属于主线")
        }

        // 不在映射里的票必须是 UNKNOWN，而不是被就近塞进某个板块
        val unknown = b.stance("999999")
        assertEquals(SectorLink.Stance.UNKNOWN, unknown)
        assertEquals(null, b.sectorOf("999999"))

        // 覆盖率要如实暴露：能映射到 sector_engine 板块的股票数应远小于 1050
        val mapped = b.stockToSector.keys.count { b.sectorOf(it) != null }
        val coverage = mapped.toDouble() / b.stockToSector.size
        println("能落到 sector_engine 板块的: $mapped / ${b.stockToSector.size} = ${"%.1f".format(coverage * 100)}%")
        assertTrue(coverage < 0.9, "云端映射与板块层级不一致时覆盖率必然偏低，若接近全覆盖说明发生了错误匹配")
    }

    @Test
    fun `成分股反查映射优先级高于行业名映射`() {
        // stock_sector_map 的 map 值是板块名，与 sector_engine 同源，应该 100% 落得上
        val full = SectorLink.parse(
            json("sector_engine.json"),
            json("stock_sector_map_sample.json"),
            SectorLink.MapSource.STOCK_SECTOR_MAP,
        )
        val b = assertNotNull(full)
        println("源=${b.mapSource.label} 归属 ${b.stockToSector.size} 只")

        assertEquals(SectorLink.MapSource.STOCK_SECTOR_MAP, b.mapSource)
        assertEquals(SectorLink.Stance.MAINLINE, b.stance("600519"), "茅台在白酒（主线）")
        assertEquals("白酒", b.sectorNameOf("600519"))
        // 煤炭开采在 avoid 名单里，即便 grade 是 D 也应判为板块规避
        println("601088 → ${b.stance("601088").label}（板块 ${b.sectorNameOf("601088")}）")
        assertEquals(SectorLink.Stance.AVOID, b.stance("601088"), "回避名单优先于分档")

        // 这份映射是合成的，6 只票都应能落上，覆盖率 100%
        assertEquals(1.0, SectorLink.coverage(b), 1e-9)
    }

    @Test
    fun `成分股反查的真实映射能把覆盖率从百分之一拉到可用`() {
        // 云端真实产物（2026-10-27, build_stock_sector_map）：2706 只股票 / 127 个板块
        val real = SectorLink.parse(
            json("sector_engine.json"),
            json("stock_sector_map.json"),
            SectorLink.MapSource.STOCK_SECTOR_MAP,
        )
        val b = assertNotNull(real, "真实映射解析失败")
        val cov = SectorLink.coverage(b)
        val fallback = SectorLink.parse(json("sector_engine.json"), json("industry_map_em.json"))
        val covFallback = fallback?.let { SectorLink.coverage(it) } ?: 0.0

        println("成分股反查: ${b.stockToSector.size} 只，落到板块的覆盖 ${"%.1f".format(cov * 100)}%")
        println("行业名兜底: ${fallback?.stockToSector?.size ?: 0} 只，覆盖 ${"%.1f".format(covFallback * 100)}%")

        // 注意：sector_engine 只跟踪 27 个重点板块，而这份映射覆盖全市场 127 个板块，
        // 所以「能落到 27 个板块上」的比例天然不可能高。要看的是绝对数量是否够用。
        val mapped = b.stockToSector.keys.count { b.sectorOf(it) != null }
        val mappedFallback = fallback?.stockToSector?.keys?.count { fallback.sectorOf(it) != null } ?: 0
        println("能落到 sector_engine 板块的：成分股反查 $mapped 只 vs 行业名兜底 $mappedFallback 只")
        assertTrue(mapped > 300, "至少要有几百只票可归属，实际 $mapped")
        assertTrue(mapped > mappedFallback * 10, "应远好于行业名兜底（之前实测只有 14 只）")
        assertTrue(cov < 0.5, "27/127 的板块覆盖决定上限，覆盖率不应异常偏高（偏高说明板块匹配出错了）")

        // 抽查：命中的板块里个股能正确归属
        val named = b.stockToSector.entries.firstOrNull { b.sectorOf(it.key) != null }
        assertNotNull(named, "至少有一只票能落到板块上")
        println("抽查 ${named.key} → ${named.value} (${b.stance(named.key).label})")
        assertEquals(named.value, b.sectorNameOf(named.key))
    }

    @Test
    fun `缺数据时返回空而不是造一个空壳板块表`() {
        assertEquals(null, SectorLink.parse(null, null))
        assertEquals(null, SectorLink.parse(JSONObject("{}"), JSONObject("{}")))
        // 只有板块、没有映射：板块级仍可用，个股归属为空
        val onlySector = SectorLink.parse(json("sector_engine.json"), null)
        assertNotNull(onlySector)
        assertTrue(onlySector.stockToSector.isEmpty())
        assertEquals(SectorLink.Stance.UNKNOWN, onlySector.stance("600519"))
    }
}
