package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MarketScene.kt — 市场场景枚举 (牛/熊/震荡/极端 四象限)
 * 移植自 scenario_engine.py
 */
enum class MarketScene(val label: String) {
    BULL("牛市进攻"),
    BEAR("熊市防御"),
    RANGE("震荡结构"),
    EXTREME("极端观望");

    companion object {
        /**
         * 根据市场数据判定当前场景
         * @param marketData market_now.json 中的 market 对象
         * @return Pair<MarketScene, 判定依据字符串>
         */
        fun detect(marketData: Map<String, Any?>?): Pair<MarketScene, String> {
            val m = marketData ?: emptyMap()
            val advance = (m["advance"] as? Number)?.toInt() ?: 0
            val decline = (m["decline"] as? Number)?.toInt() ?: 0
            val total = maxOf(1, advance + decline)
            val upPct = (m["up_pct"] as? Double)
                ?: (advance.toDouble() / total * 100)
            val limitUp = (m["limit_up"] as? Number)?.toInt() ?: 0
            val limitDown = (m["limit_down"] as? Number)?.toInt() ?: 0

            val reasons = buildList {
                add("上涨占比${upPct.toInt()}%")
                add("涨停$limitUp")
                add("跌停$limitDown")
            }

            // 极端优先
            if (limitUp >= 120 || limitDown >= 50 || upPct <= 25) {
                return EXTREME to (reasons + "极端值触发").joinToString(" + ")
            }
            // 牛市
            if (upPct >= 58 && limitUp >= 60) {
                return BULL to (reasons + "普涨+涨停潮").joinToString(" + ")
            }
            // 熊市
            if (upPct <= 42 || limitDown >= 20) {
                return BEAR to (reasons + "普跌或跌停潮").joinToString(" + ")
            }
            // 震荡
            return RANGE to (reasons + "结构分化").joinToString(" + ")
        }
    }
}

/**
 * SceneParams.kt — 场景参数数据类
 */
data class SceneParams(
    val scene: MarketScene,
    val label: String,
    val positionCap: Double,
    val minComposite: Int,
    val chgRange: Pair<Double, Double>,
    val pos240Max: Double,
    val vratioRange: Pair<Double, Double>,
    val dimWeights: Map<String, Double>,
    val prefer: String,
    val forbid: String,
    val detectReason: String,
) {
    companion object {
        private val ALL = mapOf(
            MarketScene.BULL to SceneParams(
                scene = MarketScene.BULL,
                label = "牛市进攻",
                positionCap = 0.9,
                minComposite = 55,
                chgRange = 0.5 to 8.0,
                pos240Max = 75.0,
                vratioRange = 1.2 to 3.5,
                dimWeights = mapOf(
                    "技术面" to 0.30, "资金面" to 0.20, "高低位" to 0.10,
                    "基本面" to 0.05, "板块面" to 0.15, "市场面" to 0.05,
                    "情绪面" to 0.05, "周期面" to 0.05, "消息面" to 0.05,
                ),
                prefer = "动量/趋势/板块联动",
                forbid = "不追已连板高位股 (pos240>75 且乖离>20% 排除)",
                detectReason = "",
            ),
            MarketScene.BEAR to SceneParams(
                scene = MarketScene.BEAR,
                label = "熊市防御",
                positionCap = 0.3,
                minComposite = 65,
                chgRange = -3.0 to 2.0,
                pos240Max = 35.0,
                vratioRange = 0.8 to 2.0,
                dimWeights = mapOf(
                    "技术面" to 0.10, "资金面" to 0.15, "高低位" to 0.30,
                    "基本面" to 0.20, "板块面" to 0.05, "市场面" to 0.05,
                    "情绪面" to 0.05, "周期面" to 0.05, "消息面" to 0.05,
                ),
                prefer = "低位+基本面+资金持续流入 (左侧分批)",
                forbid = "不接飞刀 (禁止 3日累计跌幅>15% 的票), 不抄绩差股",
                detectReason = "",
            ),
            MarketScene.RANGE to SceneParams(
                scene = MarketScene.RANGE,
                label = "震荡结构",
                positionCap = 0.6,
                minComposite = 50,
                chgRange = -2.0 to 8.0,
                pos240Max = 65.0,
                vratioRange = 1.0 to 3.0,
                dimWeights = mapOf(
                    "技术面" to 0.20, "资金面" to 0.20, "高低位" to 0.15,
                    "基本面" to 0.10, "板块面" to 0.15, "市场面" to 0.05,
                    "情绪面" to 0.05, "周期面" to 0.05, "消息面" to 0.05,
                ),
                prefer = "资金+板块+位置 (三者共振)",
                forbid = "不满仓单板块 (集中度>40% 预警)",
                detectReason = "",
            ),
            MarketScene.EXTREME to SceneParams(
                scene = MarketScene.EXTREME,
                label = "极端观望",
                positionCap = 0.1,
                minComposite = 72,
                chgRange = -2.0 to 3.0,
                pos240Max = 40.0,
                vratioRange = 1.0 to 2.2,
                dimWeights = mapOf(
                    "技术面" to 0.05, "资金面" to 0.25, "高低位" to 0.30,
                    "基本面" to 0.20, "板块面" to 0.05, "市场面" to 0.05,
                    "情绪面" to 0.05, "周期面" to 0.05, "消息面" to 0.00,
                ),
                prefer = "仅低位+资金+基本面三高, 宁缺毋滥",
                forbid = "禁止新开仓追热点, 只做观察名单",
                detectReason = "",
            ),
        )

        fun get(scene: MarketScene, detectReason: String = ""): SceneParams =
            ALL.getValue(scene).copy(detectReason = detectReason)
    }
}

/**
 * ScenarioEngine.kt — 根据 MarketState 自动分类场景 + 返回对应参数
 * 与现有 MarketStateEngine 兼容（复用其输出 E 分数映射到场景）
 */
object ScenarioEngine {

    /**
     * 从 MarketSnapshot.market 数据判定场景（主路径，与 Python detect_scenario 对齐）
     */
    suspend fun classifyFromMarket(marketData: Map<String, Any?>): Pair<MarketScene, String> =
        withContext(Dispatchers.IO) {
            MarketScene.detect(marketData)
        }

    /**
     * 从 MarketState 推断场景（兼容路径，当无法获取完整 market 数据时使用）
     * 映射规则：E>30 → BULL, E<-30 → BEAR, else → RANGE (极端需额外检查)
     */
    fun inferFromState(state: com.skapp.app.data.MarketState?): SceneParams? {
        val ms = state ?: return null
        val scene = when {
            ms.E > 30 -> MarketScene.BULL
            ms.E < -30 -> MarketScene.BEAR
            else -> MarketScene.RANGE
        }
        val reason = "由 MarketState E=${ms.E} 推断 (${ms.state})"
        return SceneParams.get(scene, reason)
    }

    /**
     * 对候选推股应用场景硬规则过滤（移植 apply_rules）
     * @return Pair(通过的列表, 被拒列表含原因)
     */
    fun applyRules(
        picks: List<Map<String, Any?>>,
        rules: Map<String, Any>,
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        val passed = mutableListOf<Map<String, Any?>>()
        val rejected = mutableListOf<Map<String, Any?>>()

        val chgLo = rules["chg_lo"] as? Double ?: -10.0
        val chgHi = rules["chg_hi"] as? Double ?: 10.0
        val posMax = rules["pos240_max"] as? Double ?: 100.0
        val vrLo = rules["vratio_lo"] as? Double ?: 0.0
        val vrHi = rules["vratio_hi"] as? Double ?: 10.0

        for (rec in picks) {
            val chg = rec["chg_pct"] as? Double
            if (chg != null && (chg < chgLo || chg > chgHi)) {
                rejected.add(rec + mapOf("reject_reason" to "涨幅${chg}% 不在 [${chgLo},${chgHi}]"))
                continue
            }
            val pos = rec["pos240_pct"] as? Double
            if (pos != null && pos > posMax) {
                rejected.add(rec + mapOf("reject_reason" to "位置${pos}% > ${posMax}%"))
                continue
            }
            val vr = rec["vratio"] as? Double
            if (vr != null && (vr < vrLo || vr > vrHi)) {
                rejected.add(rec + mapOf("reject_reason" to "量比${vr} 不在 [${vrLo},${vrHi}]"))
                continue
            }
            passed.add(rec)
        }
        return passed to rejected
    }

    /**
     * 按场景权重重新计算综合分（移植 rescore）
     */
    fun rescore(rec: Map<String, Any?>, dimWeights: Map<String, Double>): Double {
        val dims = rec["dims"] as? Map<*, *> ?: return (rec["composite"] as? Double) ?: 0.0
        val wsum = dimWeights.values.sum()
        if (wsum < 1e-9) return (rec["composite"] as? Double) ?: 0.0
        var total = 0.0
        for ((k, w) in dimWeights) {
            val v = dims[k] as? Double ?: 50.0
            total += v * w
        }
        return total / wsum
    }
}
