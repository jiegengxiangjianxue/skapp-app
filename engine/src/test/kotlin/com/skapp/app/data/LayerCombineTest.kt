package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 组合冒烟：把拆开的四层按真实数据流串一遍，确认「分着能编译」不等于「合起来能用」。
 *
 * 组装顺序刻意与生产链路一致：
 *   core（模型/KBar/交易时段）→ net 的解析产物形状 → algo 的 12 引擎打分 → runtime 的推股产出
 *
 * 不打网络：K 线取仓库根 testdata/kline_fixtures.json（真实前复权日 K）。
 */
class LayerCombineTest {

    private fun fixtureFile(name: String): File? = listOfNotNull(
        System.getProperty("skapp.fixtures")?.let { File(it, name) },
        File("../testdata/$name"),
        File("testdata/$name"),
    ).firstOrNull { it.exists() }

    private fun barsOf(key: String): List<KBar> {
        val f = assertNotNull(fixtureFile("kline_fixtures.json"), "缺 kline_fixtures.json")
        val arr = JSONObject(f.readText()).getJSONObject(key).getJSONArray("bars")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KBar(
                date = o.getString("date"),
                open = o.getDouble("open"),
                close = o.getDouble("close"),
                high = o.getDouble("high"),
                low = o.getDouble("low"),
                vol = o.getDouble("vol"),
            )
        }
    }

    @Test
    fun `四层能拼成一条从K线到推股产物的链路`() {
        // core 层：代码归一化 + 时段判断
        assertEquals("600519", Code.normalize("sh600519"))
        val session = LocalPicks.sessionFor(MarketHours.Phase.PRE_OPEN)
        assertEquals(LocalPicks.Session.PREMARKET, session)

        // algo 层：真实 K 线跑 12 引擎
        val bars = barsOf("strong_688655")
        assertTrue(bars.size >= 60, "fixture 根数不足：${bars.size}")
        val engine = LocalEngine.analyze(bars)
        assertTrue(engine.engines.isNotEmpty(), "引擎没跑出任何一项")
        assertNotNull(engine.centralScore, "中枢评分缺失")

        // runtime 层：把引擎结果装进推股产物，口径标记必须是盘前
        val row = LocalScreener.Screened(
            code = "600519",
            name = "贵州茅台",
            price = bars.last().close,
            pctChg = 2.5,
            volRatio = 1.6,
            turnover = 1.0e9,
            pe = 22.0,
            mv = 2.0e12,
            engine = engine,
            centralScore = engine.centralScore,
            resonanceRatio = engine.resonanceRatio,
            centralSummary = engine.summary,
            detailStatus = "ok",
            buyable = true,
        )
        val artifact = LocalPicks.build(
            screened = listOf(row),
            session = LocalPicks.Session.PREMARKET,
            calcTime = "2026-10-27T09:10:00+08:00",
            totalSnapshot = 5000,
        )
        assertEquals("premarket", artifact.session)
        assertEquals(true, artifact.metadata["droppedLastBar"])
        // 分数是否达标取决于真实 K 线，这里只要求「不达标必须如实说、达标必须有内容」
        if (artifact.status == "ok") {
            assertTrue(artifact.picks.isNotEmpty())
            assertEquals("600519", artifact.picks.first().code)
            assertNotNull(artifact.picks.first().why.takeIf { it.isNotEmpty() }, "推股理由不能为空")
        } else {
            assertTrue(artifact.picks.isEmpty(), "未达标却给出了推荐")
            assertTrue(artifact.reason.orEmpty().contains("达标"), "未达标必须给出具体原因：${artifact.reason}")
        }
    }

    @Test
    fun `盘前与午盘口径在组合链路上不会串`() {
        val bars = barsOf("mid_002230")
        // 盘前口径 = 剔除当日未完成日 K
        val preBars = bars.dropLast(1)
        val pre = LocalEngine.analyze(preBars)
        val mid = LocalEngine.analyze(bars)
        assertNotNull(pre.centralScore)
        assertNotNull(mid.centralScore)
        // 两根 K 线差异必然反映到打分上（不相等才说明口径真的分开了）
        assertTrue(
            pre.centralScore != mid.centralScore || pre.barsUsed != mid.barsUsed,
            "剔除最后一根后指标没有任何变化，口径可能是假的",
        )
        assertEquals(bars.size, mid.barsUsed)
    }
}
