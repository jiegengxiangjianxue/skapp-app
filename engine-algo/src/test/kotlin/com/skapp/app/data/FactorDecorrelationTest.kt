package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 去相关的**性质测试**——验证的是「同一族的多个指标不会被当成多份证据」这件事本身，
 * 而不是某只票该不该买。
 *
 * 背景：这套打分一开始是逐指标加权求和，结果四只样本票里有三只出现「14 个偏多 / 0 个偏空」。
 * 16 个指标彼此高度共线（Donchian 位置、Keltner 位置、Pivot 位置都在说「价格站在区间哪一侧」），
 * 逐个加权等于把同一句话数了很多遍，分数自然虚高，股票之间也拉不开差距。
 *
 * 第一次改法（族内加权平均 × 族内权重占比）是**错的**：两级平均退化成恒等变换，
 * 四只票的分数与改之前完全一致。这些用例就是用来钉住这个坑的。
 */
class FactorDecorrelationTest {

    private fun vote(name: String, signal: Int, strength: Double = 1.0, w: Double = 0.28) =
        FactorWeightStrategy.Vote(
            name = name, relWeight = w, signal = signal, strength = strength, value = "", why = ""
        )

    private val trend = FactorWeightStrategy.Family.TREND.members      // 5 个
    private val volume = FactorWeightStrategy.Family.VOLUME.members     // 2 个

    @Test
    fun `族内指标数量不影响结论——一票就是一票`() {
        // A: 趋势族 5 个指标全看空；B: 趋势族只有 1 个看空。量能族同样看多。
        val manyBears = trend.map { vote(it, -1) } + volume.map { vote(it, +1) }
        val oneBear = listOf(vote(trend.first(), -1)) + volume.map { vote(it, +1) }

        val a = FactorWeightStrategy.aggregate(FactorWeightStrategy.buildFamilies(manyBears))
        val b = FactorWeightStrategy.aggregate(FactorWeightStrategy.buildFamilies(oneBear))

        println("5 个趋势指标看空 → buy=${a.buy} avoid=${a.avoid}")
        println("1 个趋势指标看空 → buy=${b.buy} avoid=${b.avoid}")

        assertEquals(a.avoid, b.avoid, 1e-9, "族内指标从 1 个变 5 个，结论不应改变")
        assertEquals(a.buy, b.buy, 1e-9)

        // 对照旧口径：逐指标加权时两者差 5 倍——这正是被去掉的偏差
        fun rawAvoid(vs: List<FactorWeightStrategy.Vote>): Double {
            val total = vs.sumOf { it.relWeight }
            return vs.filter { it.signal < 0 }.sumOf { it.relWeight * it.strength } / total
        }
        val rawA = rawAvoid(manyBears)
        val rawB = rawAvoid(oneBear)
        println("旧口径：5 个 → ${"%.3f".format(rawA)}，1 个 → ${"%.3f".format(rawB)}（差 ${"%.1f".format(if (rawB > 0) rawA / rawB else 0)} 倍）")
        assertTrue(rawA > rawB * 2, "旧口径确实会因为指标多而放大，本测试的前提成立")
    }

    @Test
    fun `族内分歧要让这一票变轻而不是两边都记一份`() {
        // 3 多 2 空：原口径会把 3 份算进 buy、2 份算进 avoid（同族意见被拆成两份相反证据）
        val split = listOf(
            vote(trend[0], +1), vote(trend[1], +1), vote(trend[2], +1),
            vote(trend[3], -1), vote(trend[4], -1),
        )
        val agreed = trend.map { vote(it, +1) }

        val splitFam = FactorWeightStrategy.buildFamilies(split).single()
        val agreedFam = FactorWeightStrategy.buildFamilies(agreed).single()

        println("3多2空: net=${"%.2f".format(splitFam.net)} 分歧度=${"%.2f".format(splitFam.dispersion)}")
        println("5多  : net=${"%.2f".format(agreedFam.net)} 分歧度=${"%.2f".format(agreedFam.dispersion)}")

        assertTrue(splitFam.net < agreedFam.net, "分歧时净方向应明显弱于一致时")
        assertTrue(splitFam.dispersion > 0.3, "3多2空的分歧度应能被识别出来")

        val a = FactorWeightStrategy.aggregate(listOf(splitFam))
        val b = FactorWeightStrategy.aggregate(listOf(agreedFam))
        println("合成后: 分歧 buy=${a.buy} / 一致 buy=${b.buy}")
        assertTrue(a.buy < b.buy * 0.5, "族内吵架时这一票应大幅打折，实际 ${a.buy} vs ${b.buy}")
    }

    @Test
    fun `真实标的的族级分解能说清分数从哪来`() {
        val root = listOfNotNull(
            System.getProperty("skapp.fixtures")?.let(::File),
            File("../testdata"),
            File("src/test/resources"),
        )
            .first { it.exists() }
        val fixtures = JSONObject(File(root, "kline_fixtures.json").readText())
        val calib = assertNotNull(
            FactorWeightStrategy.parseCalibration(
                JSONObject(File(root, "weight_calibration.json").readText())
            )
        )

        for (key in listOf("strong_688655", "mid_002230")) {
            val arr = fixtures.getJSONObject(key).getJSONArray("bars")
            val bars = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KBar(o.getString("date"), o.getDouble("open"), o.getDouble("close"),
                    o.getDouble("high"), o.getDouble("low"), o.getDouble("vol"))
            }
            val s = assertNotNull(FactorWeightStrategy.score(key, bars, calib))
            println("$key → ${s.verdict.label} 族级=${s.buyScore}/${s.avoidScore} 旧口径=${s.rawBuyScore}/${s.rawAvoidScore} 有方向族=${s.activeFamilies}/5")
            s.families.forEach {
                println("   ${it.family.label.padEnd(5)} net=${"%+.2f".format(it.net)} 分歧=${"%.2f".format(it.dispersion)} 参与=${it.participate}/${it.total}")
            }
            assertTrue(s.families.isNotEmpty(), "必须给出族级分解")
            assertTrue(s.activeFamilies <= 5, "族数上限 5")
            // 族级分数是族数归一后的结果，不会超过 1
            assertTrue(s.buyScore <= 1.0 && s.avoidScore <= 1.0)
        }
    }

    @Test
    fun `单族翻脸不足以否决`() {
        // 只有趋势族看空、其它族一律不表态：按新规则应停在 WATCH，不给 AVOID
        val onlyTrendBear = trend.map { vote(it, -1) } +
            volume.map { vote(it, 0, 0.0) } +
            FactorWeightStrategy.Family.CHANNEL.members.map { vote(it, 0, 0.0) } +
            FactorWeightStrategy.Family.MOMENTUM.members.map { vote(it, 0, 0.0) } +
            FactorWeightStrategy.Family.STRUCTURE.members.map { vote(it, 0, 0.0) }

        val fams = FactorWeightStrategy.buildFamilies(onlyTrendBear)
        val agg = FactorWeightStrategy.aggregate(fams)
        val bearFamilies = fams.count { it.dir < 0 }
        println("单族看空: avoid=${agg.avoid} 看空族数=$bearFamilies")
        assertEquals(1, bearFamilies, "只有趋势族表态")
        // 族间等权 → 单族满票的上限就是 1/5 = 0.2，够不到 AVOID 的 0.30 门槛，
        // 但会落进 WATCH（>=0.12）。这是有意的：单族翻脸值得存疑，但不值得直接否掉。
        assertTrue(agg.avoid <= 0.2 + 1e-9, "单族贡献上限是 1/5，实际 ${agg.avoid}")
        assertTrue(agg.avoid < 0.30, "单族不该触发否决线")
        assertTrue(agg.avoid >= 0.12, "但应足以进入存疑档")
    }
}
