package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 软件—云端端到端集成：**真实云端产物**（不是手写夹具）能否被端上解析并算出可用结果。
 *
 * ## 为什么与 parity 测试分开
 *
 * parity 用的是「云端参考实现算出的期望值」，验证的是**算法**是否一致；
 * 这里用的是云端**实际落盘的产物**（`testdata/cloud/` 下的产物文件，从 `stocks-ai-view/data/` 直接拷来），
 * 验证的是**契约**是否对得上：字段名改了、规格版本升了、产物结构变了，这些事故 parity 一个都抓不到——
 * 算法可以完全正确，而端上因为读不到字段直接空白。
 *
 * ## 覆盖的四个接缝
 *
 * 1. `engine_spec.json` → [EngineSpecLoader]：版本闸门不漂移、21 引擎齐全、状态引擎注册表可读
 * 2. `risk_budget.json` → 契约字段 + 数值自洽（与 spec 的 `products[].top_fields` 对齐）
 * 3. `limit_history` 形态 → [LimitHistory.parse]：云端历史能还原成端上日级快照
 * 4. `engine_v5.json` → 云端 Top 榜单字段可读（端上展示云端榜单的接缝）
 */
class CloudArtifactIntegrationTest {

    private fun cloud(name: String): File {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "cloud/$name")
        if (!f.isFile) fail("缺少云端产物夹具：${f.absolutePath}")
        return f
    }

    @Test
    fun `内置规格与云端产物版本一致且引擎齐全`() {
        val bundled = EngineSpecLoader.loadBundled()
        assertTrue(!bundled.drift, "内置规格漂移：${bundled.reason}")
        val spec = bundled.spec
        assertEquals(21, spec.engines.size, "21 票引擎清单不完整")
        assertTrue(
            spec.deviceEngines.contains("book"),
            "book 必须在 device_engines 里，否则书籍票无准入依据（历史坑：externalVotes 恒空）",
        )
        // book 权重必须显式登记（省键会让 CanonicalEngine fallback 到 1.0，等于误开）；
        // 值允许 0.0：2026-10-30 复盘后 book 无校准依据且按不变量反算已越线，置 0 停用投票、保留展示。
        assertTrue(
            spec.weights.containsKey("book"),
            "规格必须显式登记 book 权重（可为 0.0）：省键会让 CanonicalEngine fallback 到 1.0，等于误开",
        )
        assertEquals(19, spec.deviceEngines.size, "端上可实现引擎数应为 19（18 个技术指标 + 1 个三层书籍信号票 book）")

        val cloudSpec = cloud("engine_spec.json").readText()
        val cloud = EngineSpecLoader.parse(cloudSpec)
        assertEquals(cloud.specVersion, spec.specVersion, "内置规格与云端产物版本不一致")
        assertEquals(cloud.engines.map { it.id }, spec.engines.map { it.id }, "引擎清单顺序/命名不一致")
        assertEquals(cloud.weights, spec.weights, "权重表不一致")
    }

    @Test
    fun `云端规格含状态引擎注册表与校准依据`() {
        val root = JSONObject(cloud("engine_spec.json").readText())
        val state = root.optJSONArray("state_engines") ?: fail("云端规格缺 state_engines")
        val ids = (0 until state.length()).map { state.getJSONObject(it).getString("id") }
        assertEquals(listOf("flow", "tape", "rotation", "vol_target"), ids)
        for (i in 0 until state.length()) {
            val e = state.getJSONObject(i)
            assertTrue(e.has("params") && e.getJSONObject("params").length() > 0, "${e.getString("id")} 缺参数")
            assertTrue(e.getString("parity_golden").contains("golden_v2.json"), "${e.getString("id")} 缺黄金向量引用")
        }
        val calib = root.optJSONObject("calibration") ?: fail("云端规格缺 calibration")
        assertEquals("data/risk_regime_params.json", calib.getString("src"))
        assertTrue(calib.getJSONObject("decision").getBoolean("wire"), "接线判定应已被记录（wire=true）")
        val oos = calib.getJSONObject("oos")
        assertTrue(oos.getDouble("max_drawdown") > -0.15, "样本外回撤异常：${oos.getDouble("max_drawdown")}")
    }

    @Test
    fun `风险预算产物字段齐全且数值在硬约束内`() {
        val doc = JSONObject(cloud("risk_budget.json").readText())
        // 与 spec.products[risk_budget].top_fields 对齐
        for (k in listOf("ts", "engine", "E", "regime", "budget", "kelly", "vol_target", "gates", "meta")) {
            assertTrue(doc.has(k), "risk_budget.json 缺字段 $k")
        }
        val budget = doc.getDouble("budget")
        assertTrue(budget in 0.0..0.8, "仓位预算越界：$budget（max_pos 校准值 0.8）")
        val vt = doc.getJSONObject("vol_target")
        assertEquals(0.12, vt.getDouble("annual_target"), 1e-9, "产物目标波动率与校准值不一致")
        val scale = vt.getDouble("scale")
        assertTrue(scale in 0.2..1.0, "波动率系数越界：$scale")
        assertEquals("market_index_proxy", vt.getString("source"))
    }

    @Test
    fun `风控与融合产物可被端上读取`() {
        val fusion = JSONObject(cloud("engine_fusion.json").readText())
        for (k in listOf("ts", "E", "state", "agreement", "parts")) {
            assertTrue(fusion.has(k), "engine_fusion.json 缺字段 $k")
        }
        val phase = JSONObject(cloud("market_phase.json").readText())
        // phase_runner 写的规范格式：有 dates/recent/source，端上据此认定 phase 可信
        assertTrue(phase.has("recent") && phase.has("source"), "market_phase.json 不是 phase_runner 格式")
    }

    @Test
    fun `云端涨停历史能还原成端上日级快照序列`() {
        val doc = JSONObject(cloud("limit_history.json").readText())
        val snaps = LimitHistory.parse(doc)
        assertTrue(snaps.size >= 200, "云端历史只还原出 ${snaps.size} 天，端上连板/晋级率会断档")
        val dates = snaps.map { it.date }
        assertEquals(dates.sorted(), dates, "快照序列必须按日期升序（LimitBoardCalc 依赖相邻日）")
        val last = snaps.last()
        assertTrue(last.entries.isNotEmpty(), "末日快照没有涨停名单")
        // 云端只存「收盘封住的」，没有「摸过没封住」——这一位必须如实标记为不可得
        assertTrue(!last.hasTouchInfo, "云端名单不具备触板信息，不得声称有")
        assertTrue(!last.hasRangeInfo, "云端名单不带高低开，不得声称有")
        assertTrue(last.entries.all { it.pctChg == null && it.close == null }, "云端名单没有涨跌幅/收盘，不得拿 0 冒充")
    }

    @Test
    fun `云端全市场评分产物可被端上读取`() {
        val v5 = JSONObject(cloud("engine_v5.json").readText())
        assertTrue(v5.getJSONArray("top").length() > 0, "engine_v5.json top 为空")
        val first = v5.getJSONArray("top").getJSONObject(0)
        for (k in listOf("code", "score", "votes")) {
            assertTrue(first.has(k), "engine_v5 top 条目缺字段 $k")
        }
        assertTrue(first.getJSONArray("votes").length() >= 17, "云端榜单条目票数不足（应 >=17 技术票）")
    }
}
