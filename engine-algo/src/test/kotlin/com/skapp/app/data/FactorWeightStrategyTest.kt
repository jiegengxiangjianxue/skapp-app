package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 算法验证：拿真实 K 线（腾讯前复权，140 根，2026-10-26 收盘）跑
 * 「云端 v3 标定权重 × 本地 16 指标」，检查结论方向是否说得通。
 *
 * 这不是覆盖率测试，是**方向测试**：权重是从 5~6 万样本校出来的，如果端上实现把
 * 方向搞反了（比如 Donchian 位置越靠上反而算空），结论会和行情明显矛盾，这里会挂。
 */
class FactorWeightStrategyTest {

    /** fixture 统一在仓库根 testdata/（由各模块 build 的 skapp.fixtures 系统属性指路） */
    private fun fixtureFile(name: String): File? = listOfNotNull(
        System.getProperty("skapp.fixtures")?.let { File(it, name) },
        File("../testdata/$name"),
        File("src/test/resources/$name"),
    ).firstOrNull { it.exists() }

    private val fixtures: JSONObject by lazy {
        val f = fixtureFile("kline_fixtures.json") ?: error("找不到 kline_fixtures.json")
        JSONObject(f.readText())
    }

    private fun resource(name: String): JSONObject? =
        fixtureFile(name)?.let { JSONObject(it.readText()) }

    private fun barsOf(key: String): List<KBar> {
        val arr = fixtures.getJSONObject(key).getJSONArray("bars")
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

    private fun calibration(): FactorWeightStrategy.Calibration {
        val json = assertNotNull(resource("weight_calibration.json"), "缺 weight_calibration fixture")
        return assertNotNull(FactorWeightStrategy.parseCalibration(json), "云端标定解析失败")
    }

    @Test
    fun `云端标定能解析出十六个指标且权重有区分度`() {
        val c = calibration()
        println("engine=${c.engine} horizon=${c.horizon} bull基准=${c.bullBaseRate} bear基准=${c.bearBaseRate} 建议=${c.marketAdvice}")
        c.specs.forEach { println("  ${it.name.padEnd(14)} w=${it.relWeight} edge=${it.edge} bull=${it.bullWin}") }

        assertEquals(16, c.specs.size, "云端标定应有 16 个指标")
        // 权重若被解析成等权（全 1 或全 0），说明读错字段了
        val weights = c.specs.map { it.relWeight }.toSet()
        assertTrue(weights.size > 5, "rel_weight 应有区分度，实际只有 ${weights.size} 种取值")

        val top = c.specs.maxByOrNull { it.relWeight }!!
        assertEquals("Donchian20", top.name, "云端 rel_weight 最高的是 Donchian20")
        val bottom = c.specs.minByOrNull { it.relWeight }!!
        assertEquals("RSI", bottom.name, "云端 rel_weight 最低的是 RSI")

        // 云端结论：看多基准胜率低于 50%，市况建议回避优先
        assertTrue((c.bullBaseRate ?: 100.0) < 50.0, "云端 5 日看多基准胜率应低于 50%")
        assertEquals("avoid_first", c.marketAdvice)
    }

    @Test
    fun `四只真实标的都能出分且结构自洽`() {
        val calib = calibration()
        for (key in listOf("strong_688655", "mid_002230", "heavy_600519", "weak_601398")) {
            val bars = barsOf(key)
            assertTrue(bars.size >= 60, "$key 数据不足")
            val s = assertNotNull(FactorWeightStrategy.score(key, bars, calib), "$key 打分返回 null")

            println(
                "$key ${bars.last().date} 收${bars.last().close} → ${s.verdict.label} " +
                    "net=${s.netScore} buy=${s.buyScore} avoid=${s.avoidScore} " +
                    "(多${s.bullCount}/空${s.bearCount}/中性${s.neutralCount})"
            )
            s.votes.sortedByDescending { it.relWeight }.take(4).forEach {
                println("    ${it.name.padEnd(14)} ${if (it.signal > 0) "多" else if (it.signal < 0) "空" else "—"} " +
                    "强度${"%.2f".format(it.strength)} ${it.value} · ${it.why}")
            }

            // 结构自洽：两路分数都在 [0,1]，且投票数不超过指标数
            assertTrue(s.buyScore in 0.0..1.0, "$key buyScore 越界 ${s.buyScore}")
            assertTrue(s.avoidScore in 0.0..1.0, "$key avoidScore 越界 ${s.avoidScore}")
            assertTrue(s.votes.size <= 16, "$key 投票数超过 16")
            assertEquals(s.votes.size, s.bullCount + s.bearCount + s.neutralCount)
            assertTrue(s.reasons.size <= 3, "理由最多 3 条")
        }
    }

    @Test
    fun `单边下跌的票必须被否决`() {
        val calib = calibration()
        // strong_688655 名称里带 strong 是它在 RPS 榜上的历史地位，但现在 20 日跌了 24%：
        // 正好用来验证实现没有把「均线空头排列」读成买点。
        val bars = barsOf("strong_688655")
        val s = assertNotNull(FactorWeightStrategy.score("688655", bars, calib))
        val chg20 = (bars.last().close / bars[bars.size - 21].close - 1) * 100
        println("688655 20日 ${"%.2f".format(chg20)}% → ${s.verdict.label} net=${s.netScore}")
        assertTrue(chg20 < 0, "fixture 前提：这只票 20 日为负")
        assertEquals(FactorWeightStrategy.Verdict.AVOID, s.verdict, "下跌趋势应被否决")
        assertTrue(s.bearCount >= 8, "下跌趋势应有多个空信号，实际 ${s.bearCount}")
        assertTrue(s.caveat.contains("否决"), "结果要带口径提醒")
    }

    @Test
    fun `数据不足六十根时返回空而不是硬算`() {
        val calib = calibration()
        val bars = barsOf("mid_002230").take(30)
        assertEquals(null, FactorWeightStrategy.score("002230", bars, calib))
    }

    @Test
    fun `样本外衰减会压低可信度且只减不增`() {
        val wf = assertNotNull(resource("walkforward.json"), "缺 walkforward fixture")
        val oos = assertNotNull(SignalCalibration.parseOos(wf))
        val regime = SignalCalibration.parseRegime(resource("weight_calibration.json"))
        val gate = SignalCalibration.build(oos, regime)

        println("OOS: verdict=${oos.verdict} IS=${oos.meanIs} OOS=${oos.meanOos} decay=${oos.decayPp}pp folds=${oos.foldsValid}")
        gate.notes.forEach { println("  · $it") }
        println("confidence=${gate.confidence}(${gate.confidenceLabel}) buyFactor=${gate.buyFactor} positionFactor=${gate.positionFactor}")

        assertTrue(gate.confidence in 0.5..1.0, "可信度应在 0.5~1.0")
        assertTrue(gate.buyFactor in 0.5..1.0, "买入折扣应在 0.5~1.0")
        assertTrue(gate.positionFactor <= gate.confidence, "仓位系数不得超过可信度（只减不增）")

        // 仓位上限只能被压低，不能放大
        val cap = 80.0
        val once = gate.applyCap(cap)
        assertTrue(once <= cap, "applyCap 不得放大仓位")
        assertEquals(cap * gate.positionFactor, once, 1e-9, "折扣应等于 positionFactor")
        // 注意：applyCap 是「基准上限 → 校准后上限」的一次映射，**不可叠加调用**。
        // 这里断言叠加后单调不增（而不是幂等），防止 UI 侧误以为可以连环打折。
        val twice = gate.applyCap(once)
        assertTrue(twice <= once, "重复调用只会更保守，不应回升")

        // 云端 verdict=stable 时不该给过拟合标签
        if (oos.verdict == "stable" && !oos.overfit) {
            assertTrue(gate.confidence >= 0.85, "稳定模型可信度不应低于 0.85")
        }
    }

    @Test
    fun `缺失任何一路云端数据都要能降级`() {
        val gate = SignalCalibration.build(null, null)
        println("无云端数据时: confidence=${gate.confidence} buyFactor=${gate.buyFactor} label=${gate.confidenceLabel}")
        assertTrue(gate.confidence in 0.5..1.0)
        assertTrue(gate.notes.isNotEmpty(), "降级也要说明原因")
        // 缺数据不该给出满血可信度
        assertTrue(gate.confidence < 1.0)
    }
}
