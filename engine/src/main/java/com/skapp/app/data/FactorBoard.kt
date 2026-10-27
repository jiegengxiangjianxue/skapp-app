package com.skapp.app.data

/**
 * 因子否决板的展示模型（引擎层持有，UI 只读）。
 *
 * 分成「校准」和「逐只结论」两块：
 *   - calib/gate 是全局的（云端一天一版），解释「凭什么这样判」；
 *   - rows 是逐只的，只带结论和最强的那几条空信号，不把 16 个指标全铺给用户。
 */
data class FactorRow(
    val code: String,
    val name: String,
    val score: FactorWeightStrategy.Score?,
)

data class FactorBoard(
    val calib: FactorWeightStrategy.Calibration? = null,
    val gate: SignalCalibration.Gate? = null,
    val rows: List<FactorRow> = emptyList(),
    /** 云端数据健康度（几个可用/陈旧/失败） */
    val dataNote: String = "",
    val lastError: String? = null,
) {
    val scored: List<FactorRow> get() = rows.filter { it.score != null }
    val avoidCount: Int get() = scored.count { it.score?.verdict == FactorWeightStrategy.Verdict.AVOID }
    val watchCount: Int get() = scored.count { it.score?.verdict == FactorWeightStrategy.Verdict.WATCH }
    val passCount: Int get() = scored.count { it.score?.verdict == FactorWeightStrategy.Verdict.PASS }

    /** 一句话总结，放卡片标题下面 */
    val summary: String
        get() = when {
            scored.isEmpty() -> if (lastError != null) "加载失败：$lastError" else "暂无可用标的（需 ≥60 根日 K）"
            avoidCount > 0 -> "$avoidCount 只被否决 · $watchCount 只存疑 · $passCount 只无否决"
            else -> "无标的被否决 · $watchCount 只存疑 · $passCount 只通过"
        }
}
