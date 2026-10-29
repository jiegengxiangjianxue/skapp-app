package com.skapp.app.data

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 参数清单校验（纯规则，无 IO）。
 *
 * 这几个用例守的是同一条边界：**「参数版本对不对」必须能被程序判定，而不是靠人记得**。
 * 之前端上直接吃云端标定，云端换了版本端上照样算，错得无声无息。这里把三种错法
 * （版本不一致 / 结构不符 / 参数过期）各钉一个用例，改坏了 CI 立刻红。
 */
class EngineManifestTest {

    private val expected = EngineManifest.EXPECTED_VERSIONS

    /** 造一份字段齐全的清单，可对单个引擎做变异。 */
    private fun manifest(
        schema: Int = EngineManifest.SCHEMA,
        override: (String) -> String? = { null },
        ageHours: Double = 1.0,
        maxAgeHours: Double = 48.0,
    ): JSONObject {
        val engines = JSONObject()
        expected.forEach { (id, version) ->
            val e = JSONObject()
            e.put("version", override(id) ?: version)
            e.put("sha256", "a".repeat(64))
            e.put("file", "data/$id.json")
            e.put("age_hours", ageHours)
            e.put("max_age_hours", maxAgeHours)
            e.put("baseline", JSONObject().put("n", 1))
            engines.put(id, e)
        }
        return JSONObject().put("schema", schema).put("engines", engines)
    }

    @Test
    fun `版本与结构一致时判定可用`() {
        val check = EngineManifest.check(manifest())
        assertTrue(check.ok, "应判定一致，实际: ${check.summary}")
        assertEquals(expected.size, check.engines.size)
        assertTrue(check.stale.isEmpty())
        assertEquals("引擎参数一致", check.summary)
    }

    @Test
    fun `云端升版而端上没跟上要报版本不一致`() {
        val check = EngineManifest.check(manifest(override = { id -> if (id == "weight_calibration") "weight_calibrator_v4" else null }))
        assertFalse(check.ok, "版本不一致不得判为通过")
        assertEquals(1, check.versionMismatch.size)
        assertTrue(check.versionMismatch.first().startsWith("weight_calibration"), check.versionMismatch.first())
        assertTrue(check.summary.contains("版本不一致"))
    }

    @Test
    fun `清单缺引擎要报缺`() {
        val full = manifest()
        val engines = full.getJSONObject("engines")
        engines.remove("decision")
        val check = EngineManifest.check(full)
        assertFalse(check.ok)
        assertEquals(listOf("decision"), check.missing)
    }

    @Test
    fun `schema 不符要报错`() {
        val check = EngineManifest.check(manifest(schema = 99))
        assertFalse(check.schemaOk)
        assertFalse(check.ok)
        assertTrue(check.summary.contains("schema"))
    }

    @Test
    fun `清单缺失不等于版本错误`() {
        // 离线/云端还没发布时 json 为 null：这批引擎都算「没拿到」，不能被当成版本不一致，
        // 否则调用点会误降级（SignalCalibration 据此决定是否丢掉标定）。
        val check = EngineManifest.check(null)
        assertFalse(check.ok)
        assertEquals(expected.keys.sorted(), check.missing)
        assertTrue(check.versionMismatch.isEmpty())
    }

    @Test
    fun `参数过期单独标记且不影响版本判定`() {
        val check = EngineManifest.check(manifest(ageHours = 100.0, maxAgeHours = 48.0))
        assertTrue(check.ok, "过期不算版本不一致")
        assertEquals(expected.size, check.stale.size)
        assertTrue(check.summary.contains("过期"))
        assertFalse(EngineManifest.usable(manifest(ageHours = 100.0, maxAgeHours = 48.0), "weight_calibration"))
    }

    @Test
    fun `usable 要求版本与新鲜度同时满足`() {
        assertTrue(EngineManifest.usable(manifest(), "weight_calibration"))
        assertFalse(EngineManifest.usable(manifest(override = { "broken" }), "weight_calibration"))
        assertFalse(EngineManifest.usable(null, "weight_calibration"), "清单缺失时不可用")
    }
}
