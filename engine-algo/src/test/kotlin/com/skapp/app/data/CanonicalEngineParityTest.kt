package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 双端算法一致性执法测试。
 *
 * 夹具 `testdata/parity/golden_v5.json` 由云端
 * `python3 stocks-ai-view/scripts/engine_spec.py --golden …` 用 **engine_v5 本身**
 * 对真实 K 线算出来的逐票结果。这里把同一批 K 线喂给端上 [CanonicalEngine]，
 * 要求逐票 vote 相同、strength 误差 ≤1e-3、score 误差 ≤0.05。
 *
 * 失败通常意味着两件事之一：
 *  1. 端上改了指标口径却没同步云端（或反之）——必须两边一起改并重新导出 golden；
 *  2. golden 没跟着参考实现更新——跑 `engine_spec.py --verify` 会直接指出漂移位置。
 *
 * 这个测试**代替口头约定**：只要它绿，「两端同一套算法」这句话才有依据。
 */
class CanonicalEngineParityTest {

    private fun golden(): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "parity/golden_v5.json")
        if (!f.isFile) fail("缺少黄金向量：${f.absolutePath}（先跑 stocks-ai-view/scripts/engine_spec.py --golden）")
        return JSONObject(f.readText())
    }

    @Test
    fun `规格资源可加载且版本一致`() {
        val loaded = EngineSpecLoader.loadBundled()
        assertTrue(!loaded.drift, "规格漂移：${loaded.reason}")
        assertEquals(EngineSpecLoader.BUNDLED_VERSION, loaded.spec.specVersion)
        assertEquals(21, loaded.spec.engines.size, "引擎清单应与云端 spec 一致（21 票）")
        assertTrue(loaded.spec.deviceEngines.size >= 18, "端上至少能算 18 票")
        assertEquals("weighted_vote", loaded.spec.aggregation.kind)
        // 流水线阶段必须由 spec 声明，端上不再自己写死顺序
        assertTrue(loaded.spec.pipeline.any { it.id == "decision" })
    }

    @Test
    fun `逐票复现云端 engine_v5`() {
        val root = golden()
        val spec = EngineSpecLoader.loadBundled().spec
        val cases = root.optJSONArray("cases") ?: fail("golden 没有 cases")
        assertEquals(
            EngineSpecLoader.BUNDLED_VERSION,
            root.optString("spec_version"),
            "golden 的 spec_version 与端上资源不一致（云端升版后要重新同步 spec+golden）",
        )
        var checked = 0
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val code = case.optString("code")
            val barsJson = case.optJSONArray("bars") ?: fail("$code 缺 bars")
            val bars = (0 until barsJson.length()).map { i ->
                val b = barsJson.getJSONObject(i)
                KBar(
                    date = b.optString("d"),
                    open = b.optDouble("o"),
                    close = b.optDouble("c"),
                    high = b.optDouble("h"),
                    low = b.optDouble("l"),
                    vol = b.optDouble("v"),
                )
            }
            val expect = case.getJSONObject("expect")
            val result = CanonicalEngine.score(code, bars, spec)
                ?: fail("$code 端上引擎返回 null（${bars.size} 根 K 线）")

            val expectVotes = expect.getJSONArray("votes")
            assertEquals(expectVotes.length(), result.votes.size, "$code 票数不一致")
            for (i in 0 until expectVotes.length()) {
                val ev = expectVotes.getJSONObject(i)
                val name = ev.optString("indicator")
                val vote = ev.optInt("vote")
                val strength = ev.optDouble("strength")
                val got = result.votes[i]
                assertEquals(name, got.indicator, "$code 第 ${i + 1} 票的名称顺序不一致")
                assertEquals(vote, got.vote, "$code $name 方向不一致（云端 $vote / 端上 ${got.vote}）")
                assertTrue(
                    kotlin.math.abs(strength - got.strength) <= 1e-3,
                    "$code $name 强度偏差过大：云端 $strength / 端上 ${got.strength}",
                )
            }
            assertEquals(expect.optDouble("resonance"), result.resonance, 1e-3, "$code 共振不一致")
            assertEquals(expect.optDouble("score"), result.score, 0.05, "$code 综合分不一致")
            assertEquals(expect.optInt("bull"), result.bull, "$code 看多票数不一致")
            assertEquals(expect.optInt("bear"), result.bear, "$code 看空票数不一致")
            checked++
        }
        assertTrue(checked >= 3, "至少校验 3 个黄金样本，实际 $checked")
    }

    @Test
    fun `输入不足时不产票也不编造分数`() {
        val spec = EngineSpecLoader.loadBundled().spec
        val short = (1..10).map {
            KBar(date = "2026-01-%02d".format(it), open = 10.0, close = 10.0 + it, high = 11.0, low = 9.0, vol = 1000.0)
        }
        assertEquals(null, CanonicalEngine.score("000001", short, spec), "少于 min_bars 必须返回 null")
    }

    @Test
    fun `Python 舍入口径一致`() {
        // Python: round(2.675, 2) == 2.67（二进制精确值 + 半偶进位），Kotlin 必须一致
        assertEquals(2.67, CanonicalIndicators.pyRound(2.675, 2))
        assertEquals(0.0, CanonicalIndicators.pyRound(0.5, 0), "半偶进位：0.5 → 0（Python round(0.5)=0）")
        assertEquals(2.0, CanonicalIndicators.pyRound(2.5, 0), "半偶进位：2.5 → 2")
        assertEquals(4.0, CanonicalIndicators.pyRound(3.5, 0), "半偶进位：3.5 → 4")
    }
}
