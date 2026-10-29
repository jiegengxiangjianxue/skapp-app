package com.skapp.app.data

/**
 * 引擎身份的单一来源（S1）。
 *
 * 之前同一批引擎在三处各自维护字符串：
 *  1. `LocalEngine.analyze` 的 `listOfNotNull(...)` —— 决定算哪几个引擎；
 *  2. `analyze` 里的 `weights` map —— 决定共振加权；
 *  3. `LocalEngine.familyOf` —— 决定族统计。
 * 三处拼错都不会报错：权重少一条按 1.0 兜底，族少一条被 `mapNotNull` 静默过滤
 * （`familiesOf` 里已留过这条坑的注释）。这里把 key / 族 / 默认权重收进一张表，
 * 新增引擎只改这一处，漏登记就编译或测试直接暴露。
 *
 * [defaultWeight] 是**本地默认口径**（与站点 multidim 手写权重对齐，不发明新值）；
 * 云端 `weight_calibration.json` 的 16 指标标定目前只走
 * [FactorWeightStrategy] / [SignalCalibration]，尚未映射到这张表——
 * 映射落地后 [EngineWeights.weights] 的返回值与 [LocalEngineResult.weightSource]
 * 需要一起改，单独改一边会出现「说过标定、其实没标定」的假象。
 */
enum class LocalEngineId(
    val key: String,
    val family: EngineFamily,
    val defaultWeight: Double,
) {
    TREND("trend", EngineFamily.TREND, 1.2),
    MOMENTUM("momentum", EngineFamily.MOMENTUM, 1.1),
    VOLUME("volume", EngineFamily.VOLUME, 1.0),
    BOLL("boll", EngineFamily.CHANNEL, 1.0),
    MACD("macd", EngineFamily.MOMENTUM, 1.1),
    SUPERTREND("supertrend", EngineFamily.TREND, 1.1),
    KELTNER("keltner", EngineFamily.CHANNEL, 1.0),
    CHOPPINESS("choppiness", EngineFamily.STRUCTURE, 0.9),
    MFI("mfi", EngineFamily.MOMENTUM, 1.0),
    VORTEX("vortex", EngineFamily.TREND, 1.0),
    OBV("obv", EngineFamily.VOLUME, 1.0),
    POSITION("position", EngineFamily.POSITION, 0.9),

    /** 以下两个不由 [LocalEngine.analyze] 产出，来自资金/盘口侧，只参与族统计。 */
    FLOW("flow", EngineFamily.VOLUME, 1.0),
    TAPE("tape", EngineFamily.STRUCTURE, 1.0),
    ;

    companion object {
        /** 中枢 12 引擎：`LocalEngine.analyze` 实际会产出的那 12 个。 */
        val CENTRAL: List<LocalEngineId> = listOf(
            TREND, MOMENTUM, VOLUME, BOLL, MACD, SUPERTREND,
            KELTNER, CHOPPINESS, MFI, VORTEX, OBV, POSITION,
        )
    }
}

/** 引擎权重/族的唯一出口，供 [LocalEngine] 与测试共用。 */
object EngineWeights {

    /** 本地默认权重口径标识，写进 [LocalEngineResult.weightSource] 供页面回放。 */
    const val SOURCE_DEFAULT = "local-default-v1"

    /** 未登记引擎的权重兜底：保持历史行为（不因新引擎出现而改变既有加权）。 */
    const val FALLBACK = 1.0

    private val byKey: Map<String, LocalEngineId> = LocalEngineId.entries.associateBy { it.key }

    fun weights(): Map<String, Double> = LocalEngineId.entries.associate { it.key to it.defaultWeight }

    fun familyOf(): Map<String, EngineFamily> = LocalEngineId.entries.associate { it.key to it.family }

    fun weightOf(engine: String?): Double = byKey[engine]?.defaultWeight ?: FALLBACK
}
