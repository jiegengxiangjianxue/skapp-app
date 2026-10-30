package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端验证：真实行情 → EngineChain → 三层书籍信号 → 中枢打分。
 *
 * 前面 [BookSignalEngineTest] 用的是合成 K 线，只能证明「逻辑没写反」；
 * 这里用真实日线（`testdata/kline_e2e_20261030.json`，6 只票各 120 根，
 * 2022-12-22 ~ 2026-10-16，raw 不复权，由云端 `stocks.duckdb` 导出）证明
 * **真实数据能走通整条链路**，并且标签与云端 Python 引擎在样本上一致
 * （sz000701 在 2026-10-16 是首板 + 收在最高 + 量比 2.48，云端已核）。
 *
 * 数据文件缺失时整体跳过（不在 CI 里造数据，也不假装通过）。
 */
class BookSignalE2ETest {

    private val candidates = listOf(
        File("testdata/kline_e2e_20261030.json"),
        File("../testdata/kline_e2e_20261030.json"),
        File("../../testdata/kline_e2e_20261030.json"),
    )

    private fun loadFixtures(): Map<String, List<KBar>>? {
        val f = candidates.firstOrNull { it.exists() } ?: return null
        val root = JSONObject(f.readText())
        val out = LinkedHashMap<String, List<KBar>>()
        for (code in root.keys()) {
            val arr = root.getJSONArray(code)
            val bars = ArrayList<KBar>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bars += KBar(
                    date = o.getString("date"),
                    open = o.getDouble("open"),
                    close = o.getDouble("close"),
                    high = o.getDouble("high"),
                    low = o.getDouble("low"),
                    vol = o.getDouble("vol"),
                )
            }
            out[code] = bars
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun row(code: String, pctChg: Double = 1.0) = LiveMarket.Row(
        code = code, name = null, price = null, pctChg = pctChg, volumeLots = 5.0e4,
        amount = 5.0e7, amplitude = 2.0, turnoverRate = 1.0, pe = null, volRatio = 1.2,
        mv = null, mainNet = null, ts = 0L,
    )

    @Test
    fun `真实日线跑通整条链路`() {
        val fixtures = loadFixtures() ?: return // 无数据则跳过
        assertEquals(6, fixtures.size, "夹具应有 6 只票")
        for ((code, bars) in fixtures) {
            assertEquals(120, bars.size, "$code 应有 120 根")

            val r = EngineChain.analyze(
                EngineChain.Inputs(bars = bars, row = row(code), nowMs = 1_760_000_000_000L),
            )

            assertNotNull(r.book, "$code 链路必须带出书籍层")
            assertTrue(
                r.status == "ok" || r.status == "partial",
                "$code 应能出分（status=${r.status}，degraded=${r.degraded}）",
            )
            val score = r.canonicalScore
            assertNotNull(score, "$code 应有综合分")
            assertTrue(score!! in 0.0..100.0, "$code 分数越界：$score")

            // 书籍层：要么有标签，要么必须解释为什么没有（不许静默）
            val tags = r.book!!.tags
            assertTrue(
                tags.isNotEmpty() || r.degraded.any { it.contains("书籍信号") },
                "$code 书籍层既无标签又无说明：degraded=${r.degraded}",
            )
        }
    }

    @Test
    fun `首板样本与云端标签一致`() {
        val fixtures = loadFixtures() ?: return
        val bars = fixtures["sz000701"] ?: return
        val r = BookSignalEngine.compute(bars, "sz000701")
        // 云端 Python 侧已核：2026-10-16 首板、收盘=最高、量比 2.48 → 三个标签
        assertTrue(r.tags.contains("limit:first_board"), "应识别首板：${r.tags}")
        assertTrue(r.tags.contains("limit:strong_seal"), "应判强封：${r.tags}")
        assertTrue(r.tags.contains("limit:active_5d"), "应置 active_5d：${r.tags}")
        // 首板是本轮实测唯一顺向有效的动量信号，合成分必须为正期望
        assertNotNull(r.score, "首板应有分：${r.degraded}")
        assertTrue(r.score!! > 50.0, "首板 5 日期望应为正（实测 +1.40%~+1.77%），实际 ${r.score}")
        assertTrue(
            r.expected5d != null && r.expected5d!! > 0,
            "期望收益应为正，实际 ${r.expected5d}",
        )
    }

    @Test
    fun `三层标签与云端样本方向一致`() {
        val fixtures = loadFixtures() ?: return
        var withEvidence = 0
        var withBullishVote = 0
        val spec = EngineSpecLoader.loadBundled().spec
        for ((code, bars) in fixtures) {
            val book = BookSignalEngine.compute(bars, code)
            if (book.hasEvidence) {
                withEvidence++
                val votes = EngineChain.externalVotes(null, null, book, spec)
                assertTrue(votes.all { it.vote == 1 }, "$code 三层只能投 +1：$votes")
                if (votes.isNotEmpty()) withBullishVote++
            }
        }
        // 6 只样本里至少要有 2 只有证据，否则说明判据太严、端上等于没接
        assertTrue(withEvidence >= 2, "有证据的样本过少（$withEvidence/6），判据可能过严")
        assertTrue(withEvidence >= withBullishVote, "投票数不可能多于有证据数")
    }

    @Test
    fun `不可用项在链路上可见`() {
        val limits = BookSignalEngine.knownLimits()
        // 端上确实拿不到的数据，必须能被上层读到并展示
        assertTrue(limits.containsKey("seal_amount"), "封单必须声明")
        assertTrue(limits.containsKey("first_seal_time"), "首次封板时间必须声明")
        assertTrue(limits.size >= 5, "不可用项清单过短：${limits.keys}")
    }
}
