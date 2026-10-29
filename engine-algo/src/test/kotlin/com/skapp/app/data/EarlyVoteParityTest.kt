package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 早盘 / 明日候选 / regime / 分位 的双端一致性执法测试。
 *
 * 夹具 `testdata/parity/early_votes.json` 由云端引擎本体现算生成：
 * `tomorrow_candidate_engine.score_stock` 与 `morning_pick_engine.score_stock`
 * 对同一批 K 线的 (score, 逐票 vote/strength)。端上 [EarlyVoteEngine] 必须逐票复现，
 * 否则就是「同一套算法」这句话不成立。
 *
 * regime 的期望值来自 `regime_router.judge_regime` 对三条确定性序列的输出，
 * 这里用同一构造式在端上重建序列（不落 300 个数的夹具，避免夹具与生成式两份真相）。
 */
class EarlyVoteParityTest {

    private fun fixture(): JSONObject {
        val base = System.getProperty("skapp.fixtures") ?: "testdata"
        val f = File(base, "parity/early_votes.json")
        if (!f.isFile) fail("缺少夹具：${f.absolutePath}（由 stocks-ai-view/scripts/engines 下两个引擎现算生成）")
        return JSONObject(f.readText())
    }

    private fun barsOf(case: JSONObject): List<KBar> {
        val arr = case.getJSONArray("bars")
        return (0 until arr.length()).map { i ->
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
    }

    private fun assertVotes(tag: String, expect: JSONObject, votes: List<EarlyVoteEngine.Vote>) {
        val ev = expect.getJSONArray("votes")
        assertEquals(ev.length(), votes.size, "$tag 票数不一致（云端 ${ev.length()} / 端上 ${votes.size}）")
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            val name = e.getString("i")
            val got = votes.firstOrNull { it.indicator == name }
                ?: fail("$tag 端上缺少票 $name（实际：${votes.map { it.indicator }}）")
            assertEquals(e.getInt("v"), got.vote, "$tag/$name 方向不一致")
            assertTrue(abs(e.getDouble("s") - got.strength) <= 1e-3,
                "$tag/$name strength 偏差 ${e.getDouble("s")} vs ${got.strength}")
        }
    }

    @Test
    fun `逐票复现云端明日候选 6 维`() {
        val cases = fixture().getJSONArray("cases")
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val name = case.getString("name")
            val indexCloses = case.getJSONArray("index_closes").let { arr ->
                (0 until arr.length()).map { arr.getJSONArray(it).let { p -> p.getString(0) to p.getDouble(1) } }
            }
            val ctx = EarlyVoteEngine.Context(
                industryChg5 = case.getDouble("industry_chg5"),
                indexCloses = indexCloses,
                globalAvgChg = case.getDouble("global_avg_chg"),
                mainNetToday = case.getDouble("main_net_today"),
            )
            val r = EarlyVoteEngine.tomorrow(barsOf(case), ctx)
                ?: fail("$name 端上明日候选返回 null")
            val expect = case.getJSONObject("expect").getJSONObject("tomorrow")
            assertVotes("$name/tomorrow", expect, r.votes)
            assertTrue(abs(expect.getDouble("score") - r.score) <= 0.05,
                "$name 明日候选分偏差 ${expect.getDouble("score")} vs ${r.score}")
        }
    }

    @Test
    fun `逐票复现云端早盘 6 维`() {
        val cases = fixture().getJSONArray("cases")
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val name = case.getString("name")
            val ctx = EarlyVoteEngine.Context(
                globalAvgChg = case.getDouble("global_avg_chg"),
                mainNetToday = case.getDouble("main_net_today"),
            )
            val r = EarlyVoteEngine.morning(barsOf(case), ctx)
                ?: fail("$name 端上早盘返回 null")
            val expect = case.getJSONObject("expect").getJSONObject("morning")
            assertVotes("$name/morning", expect, r.votes)
            assertTrue(abs(expect.getDouble("score") - r.score) <= 0.05,
                "$name 早盘分偏差 ${expect.getDouble("score")} vs ${r.score}")
        }
    }

    @Test
    fun `缺料时不产生该维票而不是给中性票`() {
        val case = fixture().getJSONArray("cases").getJSONObject(0)
        val bars = barsOf(case)
        val bare = EarlyVoteEngine.tomorrow(bars, EarlyVoteEngine.Context())
            ?: fail("纯 K 线也应能出票（ma/rsi/macd/volume）")
        assertTrue(bare.votes.all { it.indicator !in setOf("sector", "rel_strength") },
            "缺行业/指数时不应出现这两票：${bare.votes.map { it.indicator }}")
        assertTrue(bare.missing.any { it.contains("sector") } && bare.missing.any { it.contains("rel_strength") },
            "缺料必须写进 missing：${bare.missing}")
    }

    @Test
    fun `regime 三条序列与云端判定一致`() {
        val bull = (0 until 300).map { 1000 * Math.pow(1.002, it.toDouble()) }
        val bear = (0 until 300).map { 1000 * Math.pow(0.998, it.toDouble()) }
        val flat = (0 until 300).map { 1000 * (1 + 0.0005 * ((it % 7) - 3)) }

        val rb = RegimeGate.judge(bull) ?: fail("bull 序列不应返回 null")
        assertEquals(RegimeGate.Regime.BULL, rb.regime, "bull 序列被判成 ${rb.regime}")
        assertTrue(abs(5.5 - rb.score) <= 0.05, "bull 分 ${rb.score} 应≈5.5")
        assertEquals(1.0, rb.gate)

        val rr = RegimeGate.judge(bear) ?: fail("bear 序列不应返回 null")
        assertEquals(RegimeGate.Regime.BEAR, rr.regime, "bear 序列被判成 ${rr.regime}")
        assertTrue(abs(-5.5 - rr.score) <= 0.05, "bear 分 ${rr.score} 应≈-5.5")
        assertEquals(0.5, rr.gate)

        val rf = RegimeGate.judge(flat) ?: fail("flat 序列不应返回 null")
        assertEquals(RegimeGate.Regime.BULL, rf.regime, "横盘微涨在云端口径下也是 bull（score≈2.5）")
        assertTrue(abs(2.5 - rf.score) <= 0.05, "flat 分 ${rf.score} 应≈2.5")
        assertTrue(abs(0.755 - rf.confidence) <= 0.01, "flat 置信 ${rf.confidence} 应≈0.755")
    }

    @Test
    fun `数据不足时不猜 regime`() {
        assertEquals(null, RegimeGate.judge(emptyList()))
        assertEquals(null, RegimeGate.judge((0 until 10).map { 1000.0 + it }))
    }

    @Test
    fun `gate 只缩放对中性 50 的偏离`() {
        assertEquals(70.0, RegimeGate.applyGate(70.0, 1.0))
        assertEquals(60.0, RegimeGate.applyGate(70.0, 0.5))
        assertEquals(50.0, RegimeGate.applyGate(50.0, 0.5))
        assertEquals(40.0, RegimeGate.applyGate(30.0, 0.5))
        assertEquals(null, RegimeGate.applyGate(null, 0.8))
    }

    @Test
    fun `横截面分位与云端一致`() {
        val p = CrossSectionRank.percentiles(listOf(10.0, 20.0, 20.0, 40.0))
        assertEquals(listOf(0.0, 0.5, 0.5, 1.0), p)
        assertEquals(listOf("弱", "偏强", "偏强", "极强"), p.map { CrossSectionRank.tier(it) })

        val p2 = CrossSectionRank.percentiles(listOf(5.0, 1.0, 3.0))
        assertEquals(listOf(1.0, 0.0, 0.5), p2)

        assertEquals(listOf(0.5), CrossSectionRank.percentiles(listOf(7.0)))
        assertEquals(emptyList(), CrossSectionRank.percentiles(emptyList()))
    }

    @Test
    fun `缺值用中位数填补而不是丢样本`() {
        val p = CrossSectionRank.percentilesWithMedianFill(listOf(10.0, null, 20.0, 40.0))
        assertEquals(4, p.size)
        // 中位数取 sorted[2] = 20 → 该样本按 20 参与排序
        assertEquals(listOf(0.0, 0.5, 0.5, 1.0), p)
        assertEquals(listOf(0.5, 0.5), CrossSectionRank.percentilesWithMedianFill(listOf(null, null)))
    }
}
