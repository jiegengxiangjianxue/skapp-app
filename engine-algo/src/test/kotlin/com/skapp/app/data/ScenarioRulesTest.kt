package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 场景硬规则必须真的拦得住。
 *
 * JSON 里 `"chg_pct": 8` 会解析成 Int，旧实现 `as? Double` 拿到 null 就**直接跳过规则**，
 * 于是场景过滤看起来在跑、其实一条都没拦。这类 bug 报表上看不见：
 * 被拒列表永远是空的，推股照旧发出去。
 */
class ScenarioRulesTest {

    private val rules: Map<String, Any> = mapOf(
        "chg_lo" to 0,        // Int！真实 JSON 里就是这样
        "chg_hi" to 5,
        "pos240_max" to 35,
        "vratio_lo" to 0.8,
        "vratio_hi" to 2.0,
    )

    @Test
    fun `整数型阈值下涨幅超过上限要被拒`() {
        val picks = listOf(
            mapOf<String, Any?>("code" to "600001", "chg_pct" to 8, "pos240_pct" to 20, "vratio" to 1.2),
            mapOf<String, Any?>("code" to "600002", "chg_pct" to 2.5, "pos240_pct" to 20, "vratio" to 1.2),
        )
        val (passed, rejected) = ScenarioEngine.applyRules(picks, rules)
        assertEquals(listOf("600002"), passed.map { it["code"] })
        assertEquals(1, rejected.size)
        assertTrue(rejected.first()["reject_reason"].toString().contains("涨幅"))
    }

    @Test
    fun `整数型记录值也要能参与判定`() {
        val picks = listOf(
            // 位置 60 > 35，整数值
            mapOf<String, Any?>("code" to "600003", "chg_pct" to 2, "pos240_pct" to 60, "vratio" to 1.2),
            // 量比 3.0（Double）超上限
            mapOf<String, Any?>("code" to "600004", "chg_pct" to 2, "pos240_pct" to 20, "vratio" to 3.0),
        )
        val (passed, rejected) = ScenarioEngine.applyRules(picks, rules)
        assertTrue(passed.isEmpty(), "两条都该被拦，实际通过 $passed")
        assertEquals(2, rejected.size)
        assertTrue(rejected[0]["reject_reason"].toString().contains("位置"))
        assertTrue(rejected[1]["reject_reason"].toString().contains("量比"))
    }

    @Test
    fun `缺字段的票放行而不是误杀`() {
        val picks = listOf(mapOf<String, Any?>("code" to "600005"))
        val (passed, rejected) = ScenarioEngine.applyRules(picks, rules)
        assertEquals(1, passed.size)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `重打分的维度分是整数时不能退成中性五十分`() {
        val rec = mapOf<String, Any?>(
            "composite" to 70,
            "dims" to mapOf<String, Any?>("trend" to 90, "momentum" to 80),  // Int 维度分
        )
        val score = ScenarioEngine.rescore(rec, mapOf("trend" to 0.5, "momentum" to 0.5))
        assertEquals(85.0, score, 1e-9, "维度分是 Int 就被当成 50，重打分直接失效")
    }
}
