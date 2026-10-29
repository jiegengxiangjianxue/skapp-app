package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 界面渲染的数据契约：**用云端真实产物**跑一遍决策页会显示的那几个数，断言它们可渲染。
 *
 * ## 为什么需要它（真机验收缺位时的替代之一）
 *
 * 本环境没有 root / Shizuku / 无障碍，"装包逐屏截图"做不了。能静态证明的那一层是：
 * 界面拿到的数据必须**非空、在界面上有意义的范围内、格式化成文案后不是 null/NaN/Infinity**。
 * 真机才可能发现的（布局错位、颜色对比度、截断）这里一律不覆盖，别把这条当渲染验收。
 *
 * ## 覆盖的渲染路径
 *
 * 1. 决策页仓位上限：云端 `risk_budget.json` 的 `vol_target.scale` → [PositionCap] → 百分比文案
 * 2. 风险预算数字：`budget` → `%.1f%%`
 * 3. 市场状态：`engine_fusion.json` 的 `E` / `state` → 非空文案
 * 4. 波动率闸门说明：`vol_target` 的目标与实际 → 用户能看懂的句子
 */
class UiRenderContractTest {

    private fun cloud(name: String): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "cloud/$name")
        if (!f.isFile) fail("缺少云端产物夹具：${f.absolutePath}")
        return JSONObject(f.readText())
    }

    private fun pct0(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
    private fun pct1(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)

    private fun assertRenderable(label: String, text: String) {
        assertTrue(text.isNotBlank(), "$label 渲染为空")
        for (bad in listOf("null", "NaN", "Infinity")) {
            assertTrue(!text.contains(bad), "$label 含不可渲染片段「$bad」：$text")
        }
    }

    @Test
    fun `决策页仓位上限用云端波动率系数后仍可渲染`() {
        val budget = cloud("risk_budget.json")
        val scale = budget.getJSONObject("vol_target").getDouble("scale")
        assertTrue(scale in 0.2..1.0, "云端波动率系数越界：$scale")

        val cap = PositionCap.compute(
            breadth = 0.52, phaseScore = 10.0, limitUp = 35, limitDown = 8,
            heightToday = 3, heightPrev = 3, promo = 0.45, volScale = scale,
        )
        assertTrue(cap.cap in 0.0..PositionCap.MAX_CAP, "仓位上限越界：${cap.cap}")
        assertRenderable("仓位标签", cap.label)
        assertTrue(cap.reasons.isNotEmpty(), "仓位理由为空，界面会显示一片空白")
        cap.reasons.forEachIndexed { i, r -> assertRenderable("仓位理由#$i", r) }
        assertRenderable("仓位上限文案", pct0(cap.cap))
        if (scale < 0.999) {
            assertTrue(cap.reasons.any { it.contains("波动率闸门") }, "系数 $scale 生效但理由里没写")
        }
    }

    @Test
    fun `风险预算与市场状态数字可渲染`() {
        val budget = cloud("risk_budget.json")
        val b = budget.getDouble("budget")
        assertTrue(b in 0.0..0.8, "风险预算越界：$b")
        assertRenderable("风险预算文案", pct1(b))

        val fusion = cloud("engine_fusion.json")
        val e = fusion.getDouble("E")
        assertTrue(e in -100.0..100.0, "E 越界：$e")
        assertRenderable("E 文案", String.format(Locale.US, "%.1f", e))
        assertRenderable("市场状态文案", fusion.getString("state"))

        val vt = budget.getJSONObject("vol_target")
        val target = vt.getDouble("annual_target")
        val realized = vt.getDouble("realized")
        val scale = vt.getDouble("scale")
        val text = String.format(Locale.US, "年化波动 %.1f%% / 目标 %.1f%% → 仓位系数 %.2f",
            realized * 100, target * 100, scale)
        assertRenderable("波动率闸门文案", text)
        assertEquals(0.12, target, 1e-9, "目标波动率与校准值不一致（界面会说错话）")
    }

    @Test
    fun `闸门理由在任何系数下都不出现空串`() {
        for (scale in listOf(0.2, 0.5, 1.0)) {
            val cap = PositionCap.compute(
                breadth = 0.62, phaseScore = 35.0, limitUp = 60, limitDown = 3,
                heightToday = 5, heightPrev = 4, promo = 0.62, volScale = scale,
            )
            assertTrue(cap.reasons.size >= 3, "系数 $scale 下理由条数异常：${cap.reasons.size}")
            cap.reasons.forEach { assertRenderable("系数 $scale 的理由", it) }
            assertTrue(cap.cap <= PositionCap.MAX_CAP + 1e-9)
        }
    }
}
