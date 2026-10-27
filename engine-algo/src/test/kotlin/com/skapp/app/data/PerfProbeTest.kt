package com.skapp.app.data

import org.json.JSONObject
import java.io.File
import kotlin.test.Test

/**
 * 性能基线：单只票跑完 16 指标体系的耗时。
 * 用途是给「要不要上 Rust/NDK」这类决策提供数据——实测 0.2ms/只，纯计算不是瓶颈，
 * 真正卡顿来自线程调度，换语言解决不了。留作回归参照，不改断言（只打印不算失败）。
 */
class PerfProbeTest {
    @Test
    fun `单只票打分耗时`() {
        val root = listOfNotNull(
            System.getProperty("skapp.fixtures")?.let(::File),
            File("../testdata"),
            File("src/test/resources"),
        )
            .first { it.exists() }
        val calib = FactorWeightStrategy.parseCalibration(
            JSONObject(File(root, "weight_calibration.json").readText())
        )!!
        val arr = JSONObject(File(root, "kline_fixtures.json").readText())
            .getJSONObject("mid_002230").getJSONArray("bars")
        val bars = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KBar(o.getString("date"), o.getDouble("open"), o.getDouble("close"),
                o.getDouble("high"), o.getDouble("low"), o.getDouble("vol"))
        }
        repeat(200) { FactorWeightStrategy.score("x", bars, calib) }   // 预热 JIT
        val n = 500
        val t0 = System.nanoTime()
        repeat(n) { FactorWeightStrategy.score("x", bars, calib) }
        val msEach = (System.nanoTime() - t0) / 1e6 / n
        println("bars=" + bars.size + "  单只票 " + String.format("%.3f", msEach) + " ms  8 只合计 " + String.format("%.1f", msEach * 8) + " ms")
    }

    /**
     * JSON 解析基线：ADR-0002（端上数据交换格式）的量化依据。
     * 同一份真实产物反复 `JSONObject(text)`，输出 ms/MB，便于乘以线上实际体积。
     * 同样只打印不断言。
     */
    @Test
    fun `JSON 解析耗时`() {
        val root = listOfNotNull(
            System.getProperty("skapp.fixtures")?.let(::File),
            File("../testdata"),
            File("src/test/resources"),
        )
            .first { it.exists() }
        val f = File(root, "stock_sector_map.json")
        val text = f.readText()
        repeat(50) { JSONObject(text) }          // 预热 JIT
        val n = 200
        val t0 = System.nanoTime()
        repeat(n) { JSONObject(text) }
        val msEach = (System.nanoTime() - t0) / 1e6 / n
        val mb = text.length / 1024.0 / 1024.0
        println(
            "file=" + f.name + "  " + String.format("%.2f", mb) + " MB  " +
                "单次解析 " + String.format("%.3f", msEach) + " ms  " +
                "即 " + String.format("%.1f", msEach / mb) + " ms/MB"
        )
    }
}
