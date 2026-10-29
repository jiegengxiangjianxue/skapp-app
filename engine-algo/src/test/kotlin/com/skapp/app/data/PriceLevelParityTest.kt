package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 价位引擎（POC / 分形聚类）的双端一致性执法测试。
 *
 * 夹具 `testdata/parity/price_levels.json` 由云端 `price_level_engine.py` 本身对同一批
 * K 线算出：分形点、聚类、POC、密集区、支撑/阻力、枢轴。端上必须逐值复现。
 */
class PriceLevelParityTest {

    private fun cases(): List<JSONObject> {
        val base = System.getProperty("skapp.fixtures") ?: "testdata"
        val f = File(base, "parity/price_levels.json")
        if (!f.isFile) fail("缺少夹具：${f.absolutePath}")
        val arr = JSONObject(f.readText()).getJSONArray("cases")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun barsOf(case: JSONObject): List<KBar> {
        val arr = case.getJSONArray("bars")
        return (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            KBar(
                date = b.getString("date"),
                open = b.getDouble("open"),
                close = b.getDouble("close"),
                high = b.getDouble("high"),
                low = b.getDouble("low"),
                vol = b.getDouble("vol"),
            )
        }
    }

    @Test
    fun `分形点逐点一致`() {
        for (case in cases()) {
            val name = case.getString("name")
            val bars = barsOf(case)
            val (fh, fl) = PriceLevelEngine.fractals(bars)
            val exp = case.getJSONObject("expect")
            assertPoints("$name/分形高", exp.getJSONArray("fractal_highs"), fh)
            assertPoints("$name/分形低", exp.getJSONArray("fractal_lows"), fl)
        }
    }

    private fun assertPoints(tag: String, expect: org.json.JSONArray, got: List<Pair<Int, Double>>) {
        assertEquals(expect.length(), got.size, "$tag 个数不一致")
        for (i in 0 until expect.length()) {
            val p = expect.getJSONArray(i)
            assertEquals(p.getInt(0), got[i].first, "$tag[$i] 下标")
            assertTrue(abs(p.getDouble(1) - got[i].second) <= 1e-6, "$tag[$i] 价格 ${p.getDouble(1)} vs ${got[i].second}")
        }
    }

    @Test
    fun `聚类与量价剖面一致`() {
        for (case in cases()) {
            val name = case.getString("name")
            val bars = barsOf(case)
            val exp = case.getJSONObject("expect")
            val (fh, _) = PriceLevelEngine.fractals(bars)
            val got = PriceLevelEngine.cluster(fh)
            val expC = exp.getJSONArray("high_clusters")
            assertEquals(expC.length(), got.size, "$name 高点簇个数")
            for (i in 0 until expC.length()) {
                val c = expC.getJSONObject(i)
                assertTrue(abs(c.getDouble("price") - got[i].price) <= 1e-6, "$name 簇价 ${c.getDouble("price")} vs ${got[i].price}")
                assertEquals(c.getInt("touches"), got[i].touches, "$name 簇触碰次数")
                assertEquals(c.getInt("last_touch_idx"), got[i].lastTouchIdx, "$name 簇最后触碰下标")
            }

            val (poc, zones) = PriceLevelEngine.volumeProfile(bars)
            if (!exp.isNull("poc")) {
                assertTrue(abs(exp.getDouble("poc") - (poc ?: fail("$name POC 为空"))) <= 1e-6,
                    "$name POC ${exp.getDouble("poc")} vs $poc")
            }
            val expZ = exp.getJSONArray("zones")
            assertEquals(expZ.length(), zones.size, "$name 密集区个数")
            for (i in 0 until expZ.length()) {
                val z = expZ.getJSONObject(i)
                assertTrue(abs(z.getDouble("vol_share") - zones[i].volShare) <= 1e-6, "$name 密集区占比")
                assertTrue(abs(z.getDouble("low") - zones[i].low) <= 1e-6, "$name 密集区下沿")
            }
        }
    }

    @Test
    fun `支撑阻力与枢轴一致`() {
        for (case in cases()) {
            val name = case.getString("name")
            val bars = barsOf(case)
            val exp = case.getJSONObject("expect")
            val r = PriceLevelEngine.levels(bars) ?: fail("$name levels 返回 null")

            val expSup = exp.getJSONArray("support")
            assertEquals(expSup.length(), r.supports.size, "$name 支撑个数")
            for (i in 0 until expSup.length()) {
                val s = expSup.getJSONObject(i)
                assertTrue(abs(s.getDouble("price") - r.supports[i].price) <= 1e-6,
                    "$name 支撑价 ${s.getDouble("price")} vs ${r.supports[i].price}")
            }
            val expRes = exp.getJSONArray("resistance")
            assertEquals(expRes.length(), r.resistances.size, "$name 阻力个数")
            for (i in 0 until expRes.length()) {
                val s = expRes.getJSONObject(i)
                assertTrue(abs(s.getDouble("price") - r.resistances[i].price) <= 1e-6,
                    "$name 阻力价 ${s.getDouble("price")} vs ${r.resistances[i].price}")
            }
            val pv = exp.getJSONObject("pivot")
            assertTrue(abs(pv.getDouble("P") - r.pivot.p) <= 1e-6, "$name 枢轴 P")
            assertTrue(abs(pv.getDouble("R1") - r.pivot.r1) <= 1e-6, "$name R1")
            assertTrue(abs(pv.getDouble("S1") - r.pivot.s1) <= 1e-6, "$name S1")
            assertTrue(abs(pv.getDouble("R2") - r.pivot.r2) <= 1e-6, "$name R2")
            assertTrue(abs(pv.getDouble("S2") - r.pivot.s2) <= 1e-6, "$name S2")
        }
    }

    @Test
    fun `K线不足时不给价位`() {
        val few = (0 until 20).map { KBar("d$it", 10.0, 10.0 + it, 10.5 + it, 9.5 + it, 1e6) }
        assertEquals(null, PriceLevelEngine.levels(few))
    }
}
