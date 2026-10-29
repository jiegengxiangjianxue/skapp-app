package com.skapp.app.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L2 参数层的口径测试（不联网，直接喂 JSONObject）。
 *
 * 三条要钉住的事：
 * 1. 缺文件必须 cold_start，且说得出缺的是哪个；
 * 2. 有文件时权重是**云端原值**（不归一化、不改写）；
 * 3. 云端新键不猜映射，进 unknownKeys。
 */
class CloudParamsTest {

    private fun weightsDoc(extra: Map<String, Double> = emptyMap()): JSONObject {
        val w = JSONObject()
        w.put("SuperTrend", 1.0)
        w.put("Choppiness", 0.6)
        w.put("BOLL/Keltner", 0.6)
        w.put("A/D Line", 1.0)
        extra.forEach { (k, v) -> w.put(k, v) }
        return JSONObject().put("weights", w)
    }

    @Test
    fun `全缺时 cold_start 且写明缺哪个文件`() {
        val b = CloudParams.build(emptyMap())
        assertTrue(b.coldStart)
        assertEquals(CloudParams.SOURCE_COLD_START, b.source)
        assertTrue(b.weights.isEmpty())
        assertTrue(b.missing.contains("engine_weights"), "missing=${b.missing}")
        assertTrue(b.missing.contains("regime_router"))
        assertTrue(b.regulation.contains("cold_start"), "文案必须自报 cold_start：${b.regulation}")
    }

    @Test
    fun `权重取云端原值不重算`() {
        val b = CloudParams.build(mapOf("engine_weights" to weightsDoc()))
        assertFalse(b.coldStart)
        assertEquals(CloudParams.SOURCE_CLOUD, b.source)
        // 原值：0.6 不能变成 1.0，也不能被归一化成 0.5 之类
        assertEquals(0.6, b.weightOf("choppiness"))
        assertEquals(0.6, b.weightOf("boll_keltner"))
        assertEquals(1.0, b.weightOf("supertrend"))
        assertEquals(1.0, b.weightOf("ad_line"))
        assertEquals(4, b.raw.size)
        assertTrue(b.unknownKeys.isEmpty())
        assertFalse(b.regulation.contains("cold_start"), b.regulation)
    }

    @Test
    fun `云端新键不猜映射`() {
        val b = CloudParams.build(
            mapOf("engine_weights" to weightsDoc(mapOf("BrandNewIndex" to 1.3))),
        )
        assertEquals(listOf("BrandNewIndex"), b.unknownKeys)
        assertEquals(null, b.weightOf("BrandNewIndex"))
        // 已登记的照常生效
        assertEquals(0.6, b.weightOf("choppiness"))
    }

    @Test
    fun `walkforward 解析缺字段时留 null`() {
        val wf = JSONObject()
            .put("is", JSONObject().put("win_rate", 0.55))
            .put("oos", JSONObject().put("win_rate", 0.51))
            .put("overfit", false)
            .put("verdict", "指标有效性弱, 维持等权观察")
        val b = CloudParams.build(mapOf("walkforward" to wf))
        val v = b.walkforward ?: error("walkforward 未解析")
        assertEquals(0.55, v.isWin)
        assertEquals(0.51, v.oosWin)
        assertEquals(false, v.oosBetterThanIs)
        assertEquals("指标有效性弱, 维持等权观察", v.verdict)

        val partial = CloudParams.build(
            mapOf("walkforward" to JSONObject().put("overturned", true)),
        )
        assertEquals(null, partial.walkforward, "没有可识别字段时应为 null，不能编造")
    }

    @Test
    fun `云端发布 gate 时优先于端上自算`() {
        val regimeDoc = JSONObject().put("regime", "range").put("gates", JSONObject().put("range", 0.8))
        val flat = CloudParams.build(mapOf("regime" to regimeDoc))
        assertEquals(0.8, flat.regimeGate)

        val direct = CloudParams.build(mapOf("regime" to JSONObject().put("gate", 0.5)))
        assertEquals(0.5, direct.regimeGate)
    }

    @Test
    fun `本地默认权重与云端冲突时以云端为准`() {
        val board = CloudParams.build(
            mapOf("engine_weights" to weightsDoc(mapOf("MACD" to 1.1))),
        )
        assertEquals(1.1, board.weightOf("macd"))
        // 端上 EngineWeights 的默认值只作为「没有云端参数时」的兜底，不参与合并/平均
        assertEquals(1.1, board.weightOf("macd"))
    }
}
