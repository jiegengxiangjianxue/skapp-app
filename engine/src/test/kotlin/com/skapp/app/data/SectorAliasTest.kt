package com.skapp.app.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 板块别名归一化的回归测试。
 *
 * 背景：sector_engine 27 个板块里有 6 个对不上东财 f100 的板块名
 * （东财新分类统一加了「Ⅱ」后缀 + 国有大型银行多一层层级错位），
 * 这些板块的个股在决策页全部落到「未知板块」。
 *
 * 这里锁住三件事：
 *   1. 六个板块经别名后都能归属；
 *   2. 「非白酒」的成员绝不能并进白酒（黄酒/啤酒不是白酒）；
 *   3. 「银行Ⅱ」只认六大行，招行/城商行不得被标成国有大型银行。
 */
class SectorAliasTest {

    private fun sectorEngine(names: List<String>): JSONObject {
        val sectors = org.json.JSONArray()
        names.forEach { n ->
            sectors.put(
                JSONObject().apply {
                    put("sector", n)
                    put("grade", "C")
                    put("total", 50.0)
                }
            )
        }
        return JSONObject().apply {
            put("ts", "2026-10-27T10:00:00+08:00")
            put("date", "2026-10-27")
            put("sectors", sectors)
            put("mainline", org.json.JSONArray())
            put("avoid", org.json.JSONArray())
        }
    }

    private fun mapDoc(entries: Map<String, String>): JSONObject {
        val m = JSONObject()
        entries.forEach { (k, v) -> m.put(k, v) }
        return JSONObject().apply {
            put("map", m)
            put("n_sectors", 127)
        }
    }

    private val six = listOf("白酒", "国有大型银行", "轨交设备", "电视广播", "地面兵装", "专业连锁")

    private fun boardWith(entries: Map<String, String>): SectorLink.Board =
        SectorLink.parse(
            sectorEngine(six),
            mapDoc(entries),
            SectorLink.MapSource.STOCK_SECTOR_MAP,
        )!!

    @Test
    fun `六个原本对不上的板块现在都能归属`() {
        val board = boardWith(
            mapOf(
                "002646" to "白酒Ⅱ",
                "601398" to "银行Ⅱ",
                "601766" to "轨交设备Ⅱ",
                "600037" to "电视广播Ⅱ",
                "600435" to "地面兵装Ⅱ",
                "600682" to "专业连锁Ⅱ",
            )
        )
        assertEquals("白酒", board.sectorOf("002646")?.name)
        assertEquals("国有大型银行", board.sectorOf("601398")?.name)
        assertEquals("轨交设备", board.sectorOf("601766")?.name)
        assertEquals("电视广播", board.sectorOf("600037")?.name)
        assertEquals("地面兵装", board.sectorOf("600435")?.name)
        assertEquals("专业连锁", board.sectorOf("600682")?.name)
    }

    @Test
    fun `非白酒的成员不会被并进白酒`() {
        // 000752 西藏发展 / 600059 古越龙山 属于「非白酒」，是黄酒啤酒
        val board = boardWith(mapOf("002646" to "白酒Ⅱ", "000752" to "非白酒", "600059" to "非白酒"))
        assertEquals("白酒", board.sectorOf("002646")?.name)
        assertNull(board.sectorOf("000752"), "非白酒不能被并进白酒")
        assertEquals("非白酒", board.sectorNameOf("000752"), "原始板块名应保留")
    }

    @Test
    fun `银行只认六大行，招行和城商行不得标成国有大型银行`() {
        val board = boardWith(
            mapOf(
                "601398" to "银行Ⅱ", // 工商银行
                "601288" to "银行Ⅱ", // 农业银行
                "600036" to "银行Ⅱ", // 招商银行：股份行
                "001227" to "银行Ⅱ", // 兰州银行：城商行
            )
        )
        assertEquals("国有大型银行", board.sectorOf("601398")?.name)
        assertEquals("国有大型银行", board.sectorOf("601288")?.name)
        assertNull(board.sectorOf("600036"), "招行不是国有大型银行")
        assertNull(board.sectorOf("001227"), "兰州银行不是国有大型银行")
        assertEquals("银行Ⅱ", board.sectorNameOf("600036"), "非白名单保留原名")
    }

    @Test
    fun `别名表不能顺手改动无关板块`() {
        val board = boardWith(mapOf("300750" to "电池", "601766" to "轨交设备Ⅱ"))
        assertEquals("电池", board.sectorNameOf("300750"))
        assertNull(board.sectorOf("300750"), "电池不在 27 板块表里，不应伪造归属")
    }

    @Test
    fun `映射后覆盖率应覆盖所有板块名`() {
        val board = boardWith(
            mapOf(
                "002646" to "白酒Ⅱ",
                "601398" to "银行Ⅱ",
                "601766" to "轨交设备Ⅱ",
                "600037" to "电视广播Ⅱ",
                "600435" to "地面兵装Ⅱ",
                "600682" to "专业连锁Ⅱ",
            )
        )
        // 6 只全部能落到板块上，不该有漏网
        assertEquals(6, board.mappedCount)
        assertEquals(1.0, board.coverageRatio, 0.0001)
    }
}
