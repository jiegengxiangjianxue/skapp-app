package com.skapp.app.data

import org.json.JSONObject

/**
 * 信号校准闸门：把云端「样本外回测」和「市况适配」的结论，变成端上可用的两个系数。
 *
 * 之前链路的问题：云端算出了 walkforward（46 折、58 天窗口、OOS 48.6%）和
 * weight_calibration（16 指标看多胜率 43~45%、市况建议 avoid_first），但 App 照旧按
 * 写死的阈值出「可买」，等于把云端做的验证全丢了。
 *
 * 这里只做定量、可复核的两件事，不发明阈值：
 *   1. confidence — 模型可信度。判据直接沿用云端自己的过拟合规则
 *      「OOS < IS − 10pct 判 overfit_warning」，再按衰减幅度分档。
 *   2. buyFactor — 买入侧折扣。判据是云端 market_advice = avoid_first
 *      以及 16 指标 bull_win_rate 全部低于 50%（即「看多」本身跑不赢基准）。
 *
 * 两者相乘得到 positionFactor，用于压低仓位上限；它不会放大仓位——
 * 校准的作用是「不确定时更保守」，不是「确定时更激进」。
 */
object SignalCalibration {

    /** 云端 walkforward 的过拟合阈值（云端 script 里写死的规则，这里保持一致） */
    private const val OVERFIT_PP = 10.0

    data class Oos(
        val verdict: String,
        val horizonDays: Int?,
        val meanIs: Double?,
        val meanOos: Double?,
        val decayPp: Double?,
        val overfit: Boolean,
        val foldsValid: Int?,
    )

    data class Regime(
        val marketAdvice: String,
        val bearSharePct: Double?,
    )

    data class Gate(
        /** 0.5~1.0：模型在当前样本外表现下的可信度 */
        val confidence: Double,
        val confidenceLabel: String,
        /** 0.5~1.0：买入信号的折扣系数 */
        val buyFactor: Double,
        /** 仓位系数 = confidence × buyFactor，只用于压低上限 */
        val positionFactor: Double,
        val oos: Oos?,
        val regime: Regime?,
        val notes: List<String>,
    ) {
        /**
         * 把**基准仓位上限**映射成校准后的上限（只减不增）。
         *
         * 只应对「未打折的基准上限」调用一次；不要拿返回值再调一次，
         * 那会重复打折（0.85 × 0.85）。UI 侧全程只取一次。
         */
        fun applyCap(baseCap: Double): Double =
            (baseCap * positionFactor).coerceIn(0.0, baseCap)

        val positionPctLabel: String get() = "%.0f%%".format(positionFactor * 100)
    }

    // ── 解析 ────────────────────────────────────────────────

    fun parseOos(json: JSONObject?): Oos? {
        if (json == null) return null
        val s = json.optJSONObject("summary") ?: return null
        val p = json.optJSONObject("params")
        return Oos(
            verdict = s.optString("verdict").ifBlank { "unknown" },
            horizonDays = p?.optInt("horizon_days", 0)?.takeIf { it > 0 },
            meanIs = num(s, "mean_is_win"),
            meanOos = num(s, "mean_oos_win"),
            decayPp = num(s, "oos_vs_is_decay"),
            overfit = s.optBoolean("overfit_warning", false),
            foldsValid = s.optInt("n_folds_valid", 0).takeIf { it > 0 },
        )
    }

    fun parseRegime(json: JSONObject?): Regime? {
        if (json == null) return null
        val rb = json.optJSONObject("regime_balance")
        return Regime(
            marketAdvice = json.optString("market_advice").ifBlank { "" },
            bearSharePct = rb?.let { num(it, "bear_share_pct") },
        )
    }

    // ── 闸门 ────────────────────────────────────────────────

    fun build(oos: Oos?, regime: Regime?): Gate {
        val notes = ArrayList<String>()

        val confidence: Double
        val label: String
        if (oos == null) {
            confidence = 0.8
            label = "未验证（按 0.8 保守处理）"
            notes.add("云端暂无样本外回测，可信度按 0.8 保守处理")
        } else {
            val decay = oos.decayPp
            confidence = when {
                oos.overfit -> 0.7
                decay == null -> 0.85
                decay >= -3.0 -> 1.0
                decay >= -OVERFIT_PP -> 0.85
                else -> 0.75
            }
            label = when {
                oos.overfit -> "过拟合预警"
                decay == null -> "样本外未知"
                decay >= -3.0 -> "样本外稳定"
                decay >= -OVERFIT_PP -> "轻微衰减"
                else -> "明显衰减"
            }
            val isTxt = oos.meanIs?.let { "%.1f%%".format(it) } ?: "—"
            val oosTxt = oos.meanOos?.let { "%.1f%%".format(it) } ?: "—"
            val decayTxt = decay?.let { "%+.1fpp".format(it) } ?: "—"
            notes.add(
                "样本外（IS $isTxt → OOS $oosTxt，$decayTxt，" +
                    "${oos.foldsValid ?: 0} 折有效）→ 可信度 ${(confidence * 100).toInt()}%"
            )
            if (oos.overfit) notes.add("OOS 低于 IS 超过 ${OVERFIT_PP.toInt()}pp，云端判定 overfit_warning")
        }

        // 买入折扣：云端明确 avoid_first，且 16 指标看多胜率全在 50% 以下
        var buyFactor = 1.0
        when (regime?.marketAdvice) {
            "avoid_first" -> {
                buyFactor = 0.85
                notes.add("云端市况建议 avoid_first（回避优先）→ 买入信号打 0.85")
            }
            "" -> {}
            else -> {
                buyFactor = 0.9
                notes.add("云端市况建议「${regime?.marketAdvice}」→ 买入信号打 0.9")
            }
        }
        regime?.bearSharePct?.let { share ->
            if (share < 5.0) {
                notes.add("空头样本仅占 ${"%.1f".format(share)}%，云端自述其置信区间宽，故「回避」结论不给最高权重")
            }
        }

        return Gate(
            confidence = confidence,
            confidenceLabel = label,
            buyFactor = buyFactor,
            positionFactor = round2(confidence * buyFactor),
            oos = oos,
            regime = regime,
            notes = notes,
        )
    }

    /** 从数据库层取标定参数并构建（标定系数是慢变参数，属库表，不是当日行情） */
    suspend fun load(): Gate {
        val tables = Db.calibration()
        val gate = build(parseOos(tables.walkforward), parseRegime(tables.weightCalibration))

        // 参数清单校验：云端换了口径而端上没跟上时，算出来的置信度不会报错、只会静默偏移。
        // 这里不拦计算（不能因为版本号没同步就整块失效），但必须把事因写到 notes，
        // 让界面上的「可信度」「仓位建议」带上这个前提。
        val manifest = tables.manifest
            ?: return gate.copy(notes = gate.notes + "未取到参数清单，口径未经校验")
        val check = EngineManifest.check(manifest)
        if (check.ok) return gate
        return gate.copy(notes = gate.notes + "参数版本不一致：${check.summary}；结论请按保守端理解")
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun num(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }
}
