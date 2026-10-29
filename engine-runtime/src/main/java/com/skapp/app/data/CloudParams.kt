package com.skapp.app.data

import org.json.JSONObject

/**
 * L2 参数层：云端参数的**唯一读取口**。
 *
 * 三条硬规矩（改这个文件前先读）：
 *
 * 1. **端上只读值，不重算**。权重/系数一律取云端文件里的原始数值，不做归一化、
 *    不重新拟合、不和本地默认值取平均——一旦端上也"算一遍"，两边就有了第二套真相，
 *    以后 404 与 200 两种状态会给出两套结论，且无法判定谁对。
 * 2. **缺文件就回落并如实标记**：`coldStart = true` + `missing` 里写明哪个文件缺。
 *    绝不静默用默认值假装"云端参数已生效"。
 * 3. **不认识的键不猜**：云端新增指标名时进 `unknownKeys`，端上不映射、不硬塞。
 *
 * 实测（2026-10-29，raw.githubusercontent 直连）：
 *   `engine_weights.json` / `walkforward.json` / `anomaly_report.json` → 200；
 *   `central_orchestrator.json` / `tomorrow_candidate_engine.json` /
 *   `morning_pick_engine.json` / `regime_router.json` → 404（云端未发布）。
 *   所以后四项在当前版本上**必然** cold_start，这是设计内的降级，不是故障。
 */
object CloudParams {

    const val SOURCE_CLOUD = "cloud-engine_weights-v1"
    const val SOURCE_COLD_START = "cold_start-default"

    /** 参与参数板的文件键（与 [Db.paramDocs] 的 key 一一对应，顺序即 UI 展示顺序）。 */
    val DOCS: List<String> = listOf(
        "engine_weights",
        "walkforward",
        "central_review",
        "tomorrow_candidate",
        "morning_pick",
        "regime",
        "anomaly_report",
    )

    /**
     * 云端 `engine_weights.json` 的键 → `engine_spec.json` 的引擎 id。
     *
     * 云端用的是人读的名字（"MA5/MA20"、"A/D Line"），spec 用 id（"ma_golden"、"ad_line"）。
     * 这张表是唯一的映射真相，别在别处再写一份字符串判断。
     */
    val CLOUD_TO_ENGINE: Map<String, String> = linkedMapOf(
        "SuperTrend" to "supertrend",
        "MA5/MA20" to "ma_golden",
        "RSI" to "rsi",
        "MACD" to "macd",
        "KDJ(RSV)" to "kdj",
        "BOLL/Keltner" to "boll_keltner",
        "Donchian20" to "donchian",
        "CMO" to "cmo",
        "Vortex" to "vortex",
        "UltimateOsc" to "ultimate_osc",
        "Choppiness" to "choppiness",
        "HullMA" to "hull_ma",
        "ElderForce" to "elder_force",
        "ConnorsRSI" to "connors_rsi",
        "A/D Line" to "ad_line",
        "Pivot" to "pivot",
        "VWMA" to "vwma",
    )

    /** 云端样本外回测的口径视图；缺字段一律 null，不填 0。 */
    data class Walkforward(
        val isWin: Double?,
        val oosWin: Double?,
        val overfit: Boolean?,
        val verdict: String?,
    ) {
        /** 只有「样本外胜率高于样本内」时才算真可信；缺数据时返回 null（未知 ≠ 可信）。 */
        val oosBetterThanIs: Boolean?
            get() = if (isWin == null || oosWin == null) null else oosWin >= isWin
    }

    private fun doubleOf(vararg keys: String, o: JSONObject?): Double? {
        if (o == null) return null
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val d = o.optDouble(k, Double.NaN)
                if (!d.isNaN()) return d
            }
        }
        return null
    }

    fun walkforwardOf(doc: JSONObject?): Walkforward? {
        if (doc == null) return null
        val isObj = doc.optJSONObject("is") ?: doc.optJSONObject("in_sample")
        val oosObj = doc.optJSONObject("oos") ?: doc.optJSONObject("out_sample")
        val isWin = doubleOf("win_rate", "win", "hit_rate", o = isObj) ?: doubleOf("is_win", "is_win_rate", o = doc)
        val oosWin = doubleOf("win_rate", "win", "hit_rate", o = oosObj) ?: doubleOf("oos_win", "oos_win_rate", o = doc)
        val overfitRaw = when {
            doc.has("overfit") && !doc.isNull("overfit") -> doc.optBoolean("overfit")
            doc.has("is_overfit") && !doc.isNull("is_overfit") -> doc.optBoolean("is_overfit")
            else -> null
        }
        val verdict = doc.optString("verdict").takeIf { it.isNotBlank() }
        if (isWin == null && oosWin == null && overfitRaw == null && verdict == null) return null
        return Walkforward(isWin, oosWin, overfitRaw, verdict)
    }

    private fun gateOf(doc: JSONObject?): Double? {
        if (doc == null) return null
        doubleOf("gate", o = doc)?.let { return it }
        val gates = doc.optJSONObject("gates") ?: return null
        val regime = doc.optString("regime").takeIf { it.isNotBlank() } ?: return null
        return doubleOf(regime, o = gates)
    }

    data class DocState(val file: String, val ok: Boolean, val detail: String)

    data class Board(
        /** spec 引擎 id → 权重（云端原始值，未归一化）。 */
        val weights: Map<String, Double>,
        /** 云端原始键 → 权重，排查用。 */
        val raw: Map<String, Double>,
        val source: String,
        val coldStart: Boolean,
        val walkforward: Walkforward?,
        /** 云端 regime 给出的 gate（未发布则 null，交给端上 RegimeGate 自算）。 */
        val regimeGate: Double?,
        val docs: List<DocState>,
        val missing: List<String>,
        val unknownKeys: List<String>,
    ) {
        fun weightOf(engineId: String): Double? = weights[engineId]

        /** 界面/产物里的一句话口径，禁止把 cold_start 写成像云端一样。 */
        val regulation: String
            get() = if (coldStart) {
                "权重：端上默认（cold_start，云端 engine_weights 不可用）"
            } else {
                "权重：云端标定 ${weights.size} 项（$SOURCE_CLOUD）"
            }

        companion object {
            /** 全空板：cold_start，用于离线/测试基线。 */
            val COLD: Board = Board(
                weights = emptyMap(), raw = emptyMap(),
                source = SOURCE_COLD_START, coldStart = true,
                walkforward = null, regimeGate = null,
                docs = emptyList(), missing = listOf("engine_weights"),
                unknownKeys = emptyList(),
            )
        }
    }

    /**
     * 纯函数：从已取到的文档构造参数板。测试直接喂 JSONObject，不碰网络。
     *
     * @param states 各文件的取数状态文案（404/超时/ok），仅用于展示，不参与判定。
     */
    fun build(
        docs: Map<String, JSONObject?>,
        states: Map<String, String> = emptyMap(),
    ): Board {
        val weightDoc = docs["engine_weights"]
        val rawWeights = LinkedHashMap<String, Double>()
        weightDoc?.optJSONObject("weights")?.let { w ->
            val it = w.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = w.optDouble(k, Double.NaN)
                if (!v.isNaN() && v > 0.0) rawWeights[k] = v
            }
        }

        val mapped = LinkedHashMap<String, Double>()
        val unknown = mutableListOf<String>()
        for ((cloudKey, w) in rawWeights) {
            val id = CLOUD_TO_ENGINE[cloudKey]
            if (id == null) unknown += cloudKey else mapped[id] = w
        }

        val missing = mutableListOf<String>()
        if (rawWeights.isEmpty()) missing += "engine_weights"
        if (docs["walkforward"] == null) missing += "walkforward"
        if (docs["regime"] == null) missing += "regime_router"
        if (docs["central_review"] == null) missing += "central_orchestrator"
        if (docs["tomorrow_candidate"] == null) missing += "tomorrow_candidate_engine"
        if (docs["morning_pick"] == null) missing += "morning_pick_engine"

        val coldStart = mapped.isEmpty()
        return Board(
            weights = mapped,
            raw = rawWeights,
            source = if (coldStart) SOURCE_COLD_START else SOURCE_CLOUD,
            coldStart = coldStart,
            walkforward = walkforwardOf(docs["walkforward"]),
            regimeGate = gateOf(docs["regime"]),
            docs = DOCS.map { key ->
                DocState(key, docs[key] != null, states[key] ?: if (docs[key] != null) "ok" else "未取到")
            },
            missing = missing,
            unknownKeys = unknown,
        )
    }

    /**
     * 从云端拉参数板。任何单个文件失败都不影响其余；全部失败 = 全 cold_start。
     *
     * 注意：这些文件里有三项（central/tomorrow/morning/regime）云端未发布，
     * `load` 会返回 `json == null`，落到 [Board.missing]，属预期。
     */
    suspend fun fromCloud(force: Boolean = false): Board {
        // 必须经 Db 层进入云端（分层守卫 tools/layer_source_guard.py 的硬规则）
        val loaded = Db.paramDocs(force = force)
        val docs = DOCS.associateWith { loaded[it]?.json }
        val states = loaded.mapValues { (_, d) ->
            when {
                d.ok && d.stale -> "陈旧 ${d.tsText}"
                d.ok -> "ok（${d.tsText}）"
                else -> d.error ?: "不可用"
            }
        }
        return build(docs, states)
    }
}
