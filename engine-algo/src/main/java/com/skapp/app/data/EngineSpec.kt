package com.skapp.app.data

import org.json.JSONObject

/**
 * 双端算法规格（云端 `stocks-ai-view/scripts/engine_spec.py` 生成的 `engine_spec.json`）。
 *
 * ## 为什么端上不自己写死参数
 *
 * 云端与端上曾各自维护一套指标、阈值与聚合公式：云端 21 票加权、端上 12 引擎加权平均，
 * 同一只票两边能差 20 分以上，界面上却都叫「引擎评分」。规格文件把「算法的事实」收敛成
 * 一份可机读声明：引擎清单、每个参数、聚合常数、端上流水线阶段。
 *
 * 端上只做两件事：读规格、按规格算。改阈值改云端那份，端上不需要改代码。
 *
 * ## 版本闸门
 *
 * [specVersion] 与内置资源不一致时（例如云端升了版、APK 还是旧的），
 * [EngineSpecLoader.loadBundled] 会返回 `drift=true`，引擎链把这件事标进 chain health，
 * 而不是继续用旧参数算出一份「看起来正常」的分数。
 */
data class EngineSpec(
    val specId: String,
    val specVersion: String,
    val aggregation: Aggregation,
    val runtime: RuntimePolicy,
    /** 校准权重（weight_calibrator 产物）：两端读同一份，缺失时等权。 */
    val weights: Map<String, Double>,
    val engines: List<VoteEngine>,
    val deviceEngines: List<String>,
    val cloudOnlyEngines: List<String>,
    val pipeline: List<PipelineStage>,
) {
    private val byId: Map<String, VoteEngine> = engines.associateBy { it.id }

    fun engine(id: String): VoteEngine? = byId[id]

    /** 取某个引擎的数值参数；规格里没写的用调用方给的默认值（并保持可追溯）。 */
    fun param(engineId: String, key: String, default: Double): Double =
        byId[engineId]?.params?.get(key) ?: default

    fun intParam(engineId: String, key: String, default: Int): Int =
        param(engineId, key, default.toDouble()).toInt()

    /** 该票在端上是否可算（云端独有的外部票不算）。 */
    fun deviceCanVote(engineId: String): Boolean = deviceEngines.contains(engineId)

    data class Aggregation(
        val kind: String,
        val scoreCenter: Double,
        val scoreScale: Double,
        val resonanceBase: Double,
        val resonanceSpan: Double,
        val signalBullish: Double,
        val signalBearish: Double,
        val roundScore: Int,
        val roundResonance: Int,
        val roundStrength: Int,
    ) {
        fun signalOf(score: Double): String = when {
            score >= signalBullish -> "bullish"
            score <= signalBearish -> "bearish"
            else -> "neutral"
        }

        /** score = center + scale · base · (resonanceBase + resonanceSpan · resonance) */
        fun score(base: Double, resonance: Double): Double =
            scoreCenter + scoreScale * base * (resonanceBase + resonanceSpan * resonance)
    }

    data class RuntimePolicy(
        val minBars: Int,
        val screenBars: Int,
        val snapshotStaleMs: Long,
        val engineCacheMs: Long,
        val missingVotePolicy: String,
        val aiPickBasis: String,
    )

    data class VoteEngine(
        val id: String,
        val family: String,
        val weightKey: String,
        val availability: List<String>,
        val params: Map<String, Double>,
        val voteRule: String,
        val strengthRule: String,
        val note: String,
    )

    data class PipelineStage(
        val id: String,
        val needs: List<String>,
        val produces: List<String>,
        val blocking: Boolean,
        val desc: String,
    )
}

/**
 * 规格读取。内置资源随 APK 一起打包（`engine-algo/src/main/resources/engine_spec.json`），
 * 与云端 `data/engine_spec.json` 同源，由云端脚本生成后同步过来。
 */
object EngineSpecLoader {

    const val RESOURCE = "/engine_spec.json"

    /** 编译进资源包的那份规格版本；与云端产物比对用。 */
    const val BUNDLED_VERSION = "3.2.1"

    class Loaded(val spec: EngineSpec, val drift: Boolean, val reason: String)

    fun loadBundled(): Loaded = try {
        val text = EngineSpec::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: return Loaded(fallback(), true, "内置规格资源缺失（$RESOURCE）")
        val spec = parse(text)
        val drift = spec.specVersion != BUNDLED_VERSION
        val reason = if (drift) {
            "规格版本 ${spec.specVersion} 与代码预期 $BUNDLED_VERSION 不一致"
        } else ""
        Loaded(spec, drift, reason)
    } catch (e: Exception) {
        Loaded(fallback(), true, "内置规格解析失败：${e.message}")
    }

    fun parse(text: String): EngineSpec = parse(JSONObject(text))

    fun parse(root: JSONObject): EngineSpec {
        val aggJson = root.optJSONObject("aggregation") ?: JSONObject()
        val roundJson = aggJson.optJSONObject("round") ?: JSONObject()
        val aggregation = EngineSpec.Aggregation(
            kind = aggJson.optString("kind", "weighted_vote"),
            scoreCenter = aggJson.optDouble("score_center", 50.0),
            scoreScale = aggJson.optDouble("score_scale", 50.0),
            resonanceBase = aggJson.optDouble("resonance_base", 0.6),
            resonanceSpan = aggJson.optDouble("resonance_span", 0.4),
            signalBullish = aggJson.optDouble("signal_bullish", 65.0),
            signalBearish = aggJson.optDouble("signal_bearish", 35.0),
            roundScore = roundJson.optInt("score", 1),
            roundResonance = roundJson.optInt("resonance", 3),
            roundStrength = roundJson.optInt("strength", 3),
        )
        val rtJson = root.optJSONObject("runtime") ?: JSONObject()
        val runtime = EngineSpec.RuntimePolicy(
            minBars = rtJson.optInt("min_bars", 30),
            screenBars = rtJson.optInt("screen_bars", 120),
            snapshotStaleMs = rtJson.optLong("snapshot_stale_ms", 300_000L),
            engineCacheMs = rtJson.optLong("engine_cache_ms", 45_000L),
            missingVotePolicy = rtJson.optString("missing_vote_policy", "drop"),
            aiPickBasis = rtJson.optString("ai_pick_basis", "local-score"),
        )
        val engines = root.optJSONArray("engines")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    EngineSpec.VoteEngine(
                        id = o.optString("id", ""),
                        family = o.optString("family", ""),
                        weightKey = o.optString("weight_key", o.optString("id", "")),
                        availability = o.optJSONArray("availability")?.let { a ->
                            (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
                        } ?: emptyList(),
                        params = o.optJSONObject("params")?.let { p ->
                            p.keys().asSequence().associateWith { k -> p.optDouble(k) }
                        } ?: emptyMap(),
                        voteRule = o.optString("vote_rule", ""),
                        strengthRule = o.optString("strength_rule", ""),
                        note = o.optString("note", ""),
                    )
                }
            } ?: emptyList()
        } ?: emptyList()
        val weights = root.optJSONObject("weights")?.let { w ->
            w.keys().asSequence().associateWith { k -> w.optDouble(k, 1.0) }
        } ?: emptyMap()
        val pipeline = root.optJSONArray("pipeline")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    EngineSpec.PipelineStage(
                        id = o.optString("id", ""),
                        needs = o.optJSONArray("needs")?.let { a ->
                            (0 until a.length()).map { a.optString(it, "") }
                        } ?: emptyList(),
                        produces = o.optJSONArray("produces")?.let { a ->
                            (0 until a.length()).map { a.optString(it, "") }
                        } ?: emptyList(),
                        blocking = o.optBoolean("blocking", false),
                        desc = o.optString("desc", ""),
                    )
                }
            } ?: emptyList()
        } ?: emptyList()
        return EngineSpec(
            specId = root.optString("spec_id", "skapp-engine-spec"),
            specVersion = root.optString("spec_version", "0.0.0"),
            aggregation = aggregation,
            runtime = runtime,
            weights = weights,
            engines = engines,
            deviceEngines = root.optJSONArray("device_engines")?.let { a ->
                (0 until a.length()).map { a.optString(it, "") }
            } ?: emptyList(),
            cloudOnlyEngines = root.optJSONArray("cloud_only_engines")?.let { a ->
                (0 until a.length()).map { a.optString(it, "") }
            } ?: emptyList(),
            pipeline = pipeline,
        )
    }

    /**
     * 资源缺失时的兜底：只保留聚合常数与最小运行时策略，引擎清单为空。
     * 这不是「能用」，而是让上层能明确报「规格不可用」而不是崩溃。
     */
    private fun fallback(): EngineSpec = EngineSpec(
        specId = "skapp-engine-spec",
        specVersion = "0.0.0",
        aggregation = EngineSpec.Aggregation(
            kind = "weighted_vote",
            scoreCenter = 50.0, scoreScale = 50.0,
            resonanceBase = 0.6, resonanceSpan = 0.4,
            signalBullish = 65.0, signalBearish = 35.0,
            roundScore = 1, roundResonance = 3, roundStrength = 3,
        ),
        runtime = EngineSpec.RuntimePolicy(
            minBars = 30, screenBars = 120,
            snapshotStaleMs = 300_000L, engineCacheMs = 45_000L,
            missingVotePolicy = "drop", aiPickBasis = "local-score",
        ),
        weights = emptyMap(),
        engines = emptyList(), deviceEngines = emptyList(),
        cloudOnlyEngines = emptyList(), pipeline = emptyList(),
    )
}
