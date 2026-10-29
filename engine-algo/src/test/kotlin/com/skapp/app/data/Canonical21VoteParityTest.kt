package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 21 票全量一致性执法（18 技术票 + 3 外部增强票）。
 *
 * `golden_v5.json` 只覆盖 18 票：板块联动 / 资金流一致性 / 涨停池这三票要云端外部数据，
 * 端上只能拿到代理口径，于是长期停在「18 票对齐、3 票没执法」——而这 3 票恰恰是两端
 * 各自演化、最容易悄悄漂的部分。
 *
 * 夹具 `testdata/parity/golden_21.json` 由云端参考实现本体产出（`scripts/golden_21.py`，
 * 外部数据夹具化后调 `engine_v5.build_votes`），本测试把同一批外部票注入端上
 * [CanonicalEngine.externalVote] → [CanonicalEngine.score]，比对全部 21 票与聚合结果。
 *
 * ## 比对口径
 *
 * - 逐票：以 `indicator` 为键比对 vote（严格相等）与 strength（容差 1e-9，两端都 round 到 3 位）。
 *   **不比对顺序**：云端把 below_ma60 放在最后，端上放在技术票段尾、外部票追加在后，
 *   顺序不同不影响聚合，硬比顺序只会制造假失败。
 * - 聚合：score 容差 0.05（云端 round 到 0.1）、resonance 容差 1e-3、bull/bear/neutral 严格相等。
 */
class Canonical21VoteParityTest {

    private fun fixture(name: String): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "parity/$name")
        if (!f.isFile) fail("缺少夹具：${f.absolutePath}")
        return JSONObject(f.readText())
    }

    private fun barsOf(arr: JSONArray): List<KBar> = (0 until arr.length()).map { i ->
        val b = arr.getJSONObject(i)
        KBar(
            date = b.getString("d"),
            open = b.getDouble("o"),
            close = b.getDouble("c"),
            high = b.getDouble("h"),
            low = b.getDouble("l"),
            vol = b.getDouble("v"),
        )
    }

    @Test
    fun `21 票逐票与聚合均与云端一致`() {
        val golden = fixture("golden_21.json")
        val spec = EngineSpecLoader.loadBundled().spec
        val codes = HashMap<String, List<KBar>>()
        val codeArr = golden.getJSONArray("codes")
        for (i in 0 until codeArr.length()) {
            val o = codeArr.getJSONObject(i)
            codes[o.getString("code")] = barsOf(o.getJSONArray("bars"))
        }
        val cases = golden.getJSONArray("cases")
        assertTrue(cases.length() >= 12, "21 票夹具用例太少：${cases.length()}")

        var checked21 = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val code = c.getString("code")
            val bars = codes[code] ?: fail("$id 缺少 K 线：$code")
            val extJson = c.getJSONArray("external")
            val external = (0 until extJson.length()).map { k ->
                val v = extJson.getJSONObject(k)
                CanonicalEngine.externalVote(
                    v.getString("indicator"), v.getInt("vote"), v.getDouble("strength"),
                    "夹具注入", spec,
                )
            }
            val expect = c.getJSONObject("expect")
            val expVotes = expect.getJSONArray("votes")

            val got = CanonicalEngine.score(code, bars, spec, externalVotes = external)
                ?: fail("$id 端上没有出分")
            assertEquals(expVotes.length(), got.votes.size, "$id 总票数不一致（云端应 ${expVotes.length()} 票）")
            if (expVotes.length() >= 21) checked21++

            val expMap = LinkedHashMap<String, Pair<Int, Double>>()
            for (k in 0 until expVotes.length()) {
                val v = expVotes.getJSONObject(k)
                expMap[v.getString("indicator")] = v.getInt("vote") to v.getDouble("strength")
            }
            val gotMap = got.votes.associate { it.indicator to (it.vote to it.strength) }
            assertEquals(expMap.keys, gotMap.keys, "$id 票名集合不一致")
            for ((ind, exp) in expMap) {
                val real = gotMap.getValue(ind)
                assertEquals(exp.first, real.first, "$id [$ind] 投票方向不一致")
                assertTrue(
                    kotlin.math.abs(exp.second - real.second) <= 1e-9,
                    "$id [$ind] 强度不一致：云端 ${exp.second} / 端上 ${real.second}",
                )
            }

            val diff = kotlin.math.abs(expect.getDouble("score") - got.score)
            assertTrue(diff <= 0.05, "$id 总分不一致：云端 ${expect.getDouble("score")} / 端上 ${got.score}")
            assertTrue(
                kotlin.math.abs(expect.getDouble("resonance") - got.resonance) <= 1e-3,
                "$id 共振度不一致：云端 ${expect.getDouble("resonance")} / 端上 ${got.resonance}",
            )
            assertEquals(expect.getInt("bull"), got.bull, "$id 多头票数不一致")
            assertEquals(expect.getInt("bear"), got.bear, "$id 空头票数不一致")
            assertEquals(expect.getInt("neutral"), got.neutral, "$id 中性票数不一致")
        }
        assertTrue(checked21 >= 6, "只有 $checked21 个用例真的凑满 21 票，外部票覆盖不足")
    }
}
