package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 书籍票「关 / 开」对中枢分的逐只对照报告。
 *
 * 为什么要有这个文件：书籍票只投 +1，直觉上「加一票只会抬分」，但中枢是
 * `score ≈ (Σ w·vote·strength) / (Σ w)`，新票的贡献是 `w·(s − base)/(den + w)`：
 * **当 `s < base`（票强度低于当前中枢原值）时，加这一票反而拉低分数**。
 * 所以「候选池只扩不缩」是错的直觉，必须逐只算出来看，尤其是在权重能云端调之后。
 *
 * 本测试不判定业务好坏（那是校准表与人工核对的事），只负责：
 * 1. 用同一份真实日线，给出每只票「book 关」与「book 开（w=0.6）」的中枢分与方向变化；
 * 2. 守住结构不变量：两版中枢票集只差 `book` 一票，开版多票时 bull 计数恰 +1；
 * 3. 把逐只表落到 `build/reports/book_weight_diff.md`，供人工核对留档。
 *
 * 数据文件缺失时整体跳过（不在 CI 里造数据，也不假装通过）。
 */
class BookTurnOnDiffTest {

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

    @Test
    fun `书籍票开关前后的中枢分逐只对照`() {
        val fixtures = loadFixtures() ?: return
        val base = EngineSpecLoader.loadBundled().spec
        val weightOn = 0.6
        val specOff = base.copy(weights = base.weights - "book")
        val specOn = base.copy(weights = base.weights + ("book" to weightOn))

        val rows = ArrayList<String>()
        var nWithVote = 0
        var nUp = 0
        var nDown = 0

        for ((code, bars) in fixtures) {
            val book = BookSignalEngine.compute(bars, code)
            val votesOn = EngineChain.externalVotes(null, null, book, specOn)
            val votesOff = EngineChain.externalVotes(null, null, book, specOff)
            assertTrue(votesOff.isEmpty(), "$code 缺权重时不该产票：$votesOff")

            val wOff = HashMap(base.weights)
            wOff["book"] = 0.0
            val wOn = HashMap(base.weights)
            wOn["book"] = weightOn

            val off = CanonicalEngine.score(code, bars, specOff, weights = wOff, externalVotes = votesOff)
            val on = CanonicalEngine.score(code, bars, specOn, weights = wOn, externalVotes = votesOn)
            if (off == null || on == null) {
                rows += "| $code | — | — | 中枢不可算（K 线不足/引擎全缺） |"
                continue
            }

            // 结构不变量：票集只差 book 一票；有票时 bull 恰 +1
            if (votesOn.isEmpty()) {
                assertEquals(off.score, on.score, "$code 无票时开关两版分数必须相同")
                assertEquals(off.bull, on.bull, "$code 无票时多空票数必须相同")
            } else {
                assertEquals(1, votesOn.size, "$code 书籍层只产一票")
                assertEquals("book", votesOn[0].indicator, "$code 票名")
                assertEquals(off.bull + 1, on.bull, "$code 有票时 bull 必须恰 +1")
                assertEquals(off.votes.size + 1, on.votes.size, "$code 票集只应多一张 book")
            }

            val delta = on.score - off.score
            if (votesOn.isNotEmpty()) nWithVote++
            if (delta > 0.05) nUp++ else if (delta < -0.05) nDown++
            val arrow = when {
                delta > 0.05 -> "↑ 抬分"
                delta < -0.05 -> "↓ **压分**"
                else -> "＝ 无影响"
            }
            rows += "| $code | ${off.score} | ${on.score} | ${"%+.2f".format(delta)} $arrow " +
                "| ${book?.score ?: "—"} | ${book?.tags?.joinToString(",") ?: ""} |"
        }

        val report = buildString {
            appendLine("# 书籍票 关/开 中枢分逐只对照（端上真实日线）")
            appendLine()
            appendLine("- 数据：`testdata/kline_e2e_20261030.json`（6 只 × 120 根，raw 不复权）")
            appendLine("- 关 = 显式摘除 `weights.book`（模拟云端把这一票关掉）；开 = 登记 0.6")
            appendLine("- 结论口径：`s < base` 时加票会**压分**，所以「只投 +1 ⇒ 只抬分」不成立")
            appendLine()
            appendLine("| 代码 | book 关 | book 开 | 变化 | 书籍层分 | 命中标签 |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { appendLine(it) }
            appendLine()
            appendLine("有 book 票：$nWithVote / ${fixtures.size}；抬分 $nUp 只；压分 $nDown 只。")
        }
        val out = File("build/reports").apply { mkdirs() }.resolve("book_weight_diff.md")
        out.writeText(report)
        println(report)
    }
}
