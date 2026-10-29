package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 波动率目标闸门的双端一致性（[VolTargetGate] vs 云端 `engines_v2.vol_target_compute`）。
 *
 * 这条闸门会**直接改变用户看到的仓位上限**，所以三件事必须锁死：
 *
 * 1. 参数默认值 = 回测校准值（0.12 / 60 / 0.2 / 0.8），改了必须重新校准、重新生成黄金；
 * 2. 样本不足时 `scale=1.0` 且 `status=insufficient`——不许拿中性值偷偷压仓；
 * 3. 波动率算的是**总体标准差**（除以 n，不是 n−1），两端一致，否则高波动期系数会差几个点。
 */
class VolTargetParityTest {

    private fun golden(): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "parity/golden_v2.json")
        if (!f.isFile) fail("缺少 v2 黄金向量：${f.absolutePath}")
        return JSONObject(f.readText())
    }

    @Test
    fun `参数默认值等于回测校准值`() {
        val p = golden().getJSONObject("vol_target").getJSONObject("params")
        val d = VolTargetGate.Params()
        assertEquals(p.getDouble("volTargetAnnual"), d.volTargetAnnual, "波动率目标漂了（需重新校准）")
        assertEquals(p.getInt("window"), d.window)
        assertEquals(p.getDouble("floor"), d.floor)
        assertEquals(p.getDouble("maxPos"), d.maxPos)
    }

    @Test
    fun `四个场景系数与状态一致`() {
        val cases = golden().getJSONObject("vol_target").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val retsJson = c.getJSONArray("returns")
            val rets = (0 until retsJson.length()).map { retsJson.getDouble(it) }
            val e = c.getJSONObject("expect")
            val got = VolTargetGate.compute(rets)
            assertEquals(e.getString("status"), got.status, "$id 状态不一致")
            assertEquals(e.getInt("sampleDays"), got.sampleDays, "$id 样本天数不一致")
            val expScale = e.getDouble("scale")
            assertTrue(
                kotlin.math.abs(expScale - got.scale) <= 1e-9,
                "$id 系数不一致：云端 $expScale / 端上 ${got.scale}",
            )
            val expVol = if (e.isNull("realizedVol")) null else e.getDouble("realizedVol")
            if (expVol == null) {
                assertEquals(null, got.realizedVol, "$id 云端不可得而端上给了值")
            } else {
                assertTrue(
                    kotlin.math.abs(expVol - (got.realizedVol ?: 0.0)) <= 1e-9,
                    "$id 波动率不一致：云端 $expVol / 端上 ${got.realizedVol}",
                )
            }
            // 只降不升：系数必须在 [floor, 1.0] 内
            assertTrue(got.scale in 0.2..1.0, "$id 系数越界：${got.scale}")
        }
    }

    @Test
    fun `仓位上限乘上系数后不高于原值与硬约束`() {
        val base = PositionCap.compute(
            breadth = 0.55, phaseScore = 20.0, limitUp = 40, limitDown = 5,
        )
        val gated = PositionCap.compute(
            breadth = 0.55, phaseScore = 20.0, limitUp = 40, limitDown = 5, volScale = 0.5,
        )
        assertTrue(gated.cap <= base.cap + 1e-9, "系数 0.5 反而提高了上限：${gated.cap} > ${base.cap}")
        assertTrue(gated.cap <= PositionCap.MAX_CAP + 1e-9, "上限超过 MAX_CAP")
        assertTrue(
            gated.reasons.any { it.contains("波动率闸门") },
            "压仓后必须在理由里写明是哪条闸门，否则用户看到 cap 变小不知道为什么",
        )
        // 系数 1.0 等于不压仓
        val neutral = PositionCap.compute(
            breadth = 0.55, phaseScore = 20.0, limitUp = 40, limitDown = 5, volScale = 1.0,
        )
        assertEquals(base.cap, neutral.cap, 1e-9)
    }
}
