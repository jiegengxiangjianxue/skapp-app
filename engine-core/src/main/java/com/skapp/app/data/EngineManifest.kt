package com.skapp.app.data

import org.json.JSONObject

/**
 * 引擎参数清单的端上校验（对应云端 `data/engine_manifest.json`）。
 *
 * ## 为什么需要
 *
 * 端上 `FactorWeightStrategy` / `SignalCalibration` 直接吃云端算出来的参数，但此前
 * 没有任何机制确认「吃的这份参数」与「端上代码假设的口径」是同一版。云端换了阈值、
 * 版本号升了，端上照旧按旧假设解释——**算出来的分不会报错，只会悄悄错**。这类错误
 * 比崩溃难查得多：分数看着正常，只是系统性偏高或偏低。
 *
 * ## 这里只做端上做得到的事
 *
 * 1. 版本一致性：manifest 的 `version` 必须等于 [EXPECTED_VERSIONS] 里的键。
 * 2. 结构完整性：参数文件、sha256、基线字段齐全，缺项即视为不可用。
 * 3. 新鲜度：`age_hours > max_age_hours` 记为过期（与 [CloudFeed] 的 STALE_HOURS 呼应）。
 *
 * 不重算参数、不重跑回测——那需要全市场样本，端上做不到，由云端
 * `scripts/verify_engine_consistency.py` 负责。
 *
 * ## 不一致为什么不抛异常
 *
 * 离线、旧安装包、云端还没更新都是常态。抛异常等于把「参数换了一版」变成「App 打不开」。
 * 正确做法是返回 `ok=false`，调用方降级到内置默认值，并把原因显示在诊断页。
 */
object EngineManifest {

    const val SCHEMA = 1

    /**
     * 端上代码所假设的引擎版本。
     *
     * 改这里等于改口径：只有确认端上实现已跟上云端新版本时才更新。云端
     * `verify_engine_consistency.py` 会解析本表与 manifest 逐项比对，两边不一致 CI 直接红。
     */
    val EXPECTED_VERSIONS: Map<String, String> = mapOf(
        "weight_calibration" to "weight_calibrator_v3",
        "walkforward" to "walkforward_v1",
        "engine_fusion" to "engine_fusion_v1",
        "decision" to "decision v2",
        "market_phase" to "market_phase_v1",
        "strategy_card" to "strategy_card_v1",
    )

    data class Engine(
        val id: String,
        val version: String,
        val sha256: String,
        val file: String,
        val ageHours: Double?,
        val maxAgeHours: Double?,
        val baselineKeys: List<String>,
    ) {
        val stale: Boolean get() = ageHours != null && maxAgeHours != null && ageHours > maxAgeHours
    }

    data class Check(
        val schemaOk: Boolean,
        val missing: List<String>,
        val versionMismatch: List<String>,
        val stale: List<String>,
        val engines: Map<String, Engine>,
    ) {
        val ok: Boolean get() = schemaOk && missing.isEmpty() && versionMismatch.isEmpty()

        /** 一行结论，诊断页直接用。 */
        val summary: String
            get() = when {
                !schemaOk -> "参数清单 schema 不符（期望 $SCHEMA）"
                missing.isNotEmpty() -> "缺引擎参数 ${missing.joinToString("/")}"
                versionMismatch.isNotEmpty() -> "参数版本不一致 ${versionMismatch.joinToString("/")}"
                stale.isNotEmpty() -> "参数过期 ${stale.joinToString("/")}"
                else -> "引擎参数一致"
            }
    }

    /** 解析 manifest；结构不对返回空表，由 [check] 统一报缺。 */
    fun parse(json: JSONObject?): Map<String, Engine> {
        val obj = json?.optJSONObject("engines") ?: return emptyMap()
        val out = LinkedHashMap<String, Engine>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val e = obj.optJSONObject(id) ?: continue
            val baseline = e.optJSONObject("baseline")
            out[id] = Engine(
                id = id,
                version = e.optString("version"),
                sha256 = e.optString("sha256"),
                file = e.optString("file"),
                ageHours = e.optDouble("age_hours").takeUnless { it.isNaN() },
                maxAgeHours = e.optDouble("max_age_hours").takeUnless { it.isNaN() },
                baselineKeys = baseline?.keys()?.asSequence()?.toList() ?: emptyList(),
            )
        }
        return out
    }

    /** 校验 manifest 与端上期望是否一致。`json` 为 null（离线/未加载）时按 schema 不符处理。 */
    fun check(json: JSONObject?): Check {
        if (json == null) {
            // 与下面走了 parse 的分支保持同一顺序口径（按 id 排序），否则调用方拿到 missing
            // 的先后顺序会随「云端有没有发布清单」而变，diff/测试 / 界面都不稳定。
            return Check(false, EXPECTED_VERSIONS.keys.sorted(), emptyList(), emptyList(), emptyMap())
        }
        val schema = json.optInt("schema", -1)
        val engines = parse(json)
        val missing = EXPECTED_VERSIONS.keys.filter { it !in engines }.sorted()
        val mismatch = EXPECTED_VERSIONS
            .mapNotNull { (id, want) ->
                val got = engines[id]?.version ?: return@mapNotNull null
                if (got == want) null else "$id=$got(期望 $want)"
            }
            .sorted()
        val stale = engines.values.filter { it.stale }.map { it.id }.sorted()
        return Check(schema == SCHEMA, missing, mismatch, stale, engines)
    }

    /**
     * 端上真正按该引擎参数出结论前的闸门。
     *
     * 只有 `ok` 为真才允许把云端参数当作本版口径；否则调用方必须退回内置默认值。
     * 单独抽出来是因为「版本对上了」和「参数没用过期」是两件事，调用点往往只关心前者。
     */
    fun usable(json: JSONObject?, engineId: String): Boolean {
        val c = check(json)
        if (!c.ok) return false
        return engineId !in c.stale
    }
}
