package com.skapp.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PickBacktestTest {
    private val dates = generateSequence(LocalDate.parse("2026-01-05")) { it.plusDays(1) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        .take(60).map { it.toString() }.toList() // synthetic fixture calendar, not real market evidence
    private val record = PickHistoryRepository.Record("600001", "fixture", "2026-01-02T10:00:00+08:00",
        "morning", 999.0, "chain", "fixture-v1", "fixture.json")
    private val bars = dates.map { KBar(it, 10.0, 9.0, 11.0, 8.0, 100.0) }
    private fun series() = PickBacktest.Series(bars, "fixture", "cnfin:hfq_factor:fixture", dates,
        bars, "chain", "fixture-v1", "same_engine_fixture", "cnfin:hfq_factor:fixture")

    @Test fun `只读旧档保留缺失字段不造版本`() {
        val result = PickHistoryRepository.parse("""{"rounds":[{"ts":"2026-01-02T10:00:00+08:00","session":"premarket","engine":"legacy","picks":[{"code":"600001","price":10}]}]}""", "fixture")
        val r = result.records.single()
        assertEquals("premarket", r.session)
        assertEquals(10.0, r.price)
        assertEquals("legacy", r.engine)
        assertEquals(null, r.version)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun `信号日最终价不作买入价回本和净收益分列`() {
        val r = PickBacktest.evaluate(record, series(), dates.last())
        assertEquals(dates.first(), r.entryDate)
        assertEquals(10.0, r.entryPrice)
        assertEquals(true, r.recoup22)
        assertEquals(true, r.recoup60)
        assertTrue(r.netReturn22!! < 0)
        assertTrue(r.netReturn60!! < 0)
        assertEquals("ok", r.status)
    }

    @Test fun `样本未成熟和复权缺失都不硬算`() {
        val young = PickBacktest.evaluate(record, series(), dates[4])
        assertEquals(null, young.recoup22)
        assertEquals(null, young.netReturn60)
        val unverified = PickBacktest.evaluate(record, series().copy(adjustmentEvidence = null), dates.last())
        assertEquals("insufficient", unverified.status)
        assertEquals(null, unverified.netReturn22)
    }

    @Test fun `不能跳过首个交易日缺失也不能混引擎基准`() {
        val gap = PickBacktest.evaluate(record, series().copy(bars = bars.drop(1)), dates.last())
        assertEquals("insufficient", gap.status)
        val other = PickBacktest.evaluate(record, series().copy(benchmarkEngine = "other"), dates.last())
        assertEquals(null, other.benchmarkNetReturn22)
        assertEquals("partial", other.status)
    }

    @Test fun `读取有界且坏源明确失败`() {
        assertTrue(PickHistoryRepository.parse("not json", "bad").failures.isNotEmpty())
        val body = """{"picks":[{"code":"600001"},{"code":"600002"}]}"""
        val r = PickHistoryRepository.parse(body, "fixture", 1)
        assertEquals(1, r.records.size)
        assertTrue(r.truncated)
    }
    @Test fun unknownSessionCannotContributeToPerformance() {
        val result = PickBacktest.evaluate(record.copy(session = null), series(), dates.last())
        assertEquals("insufficient", result.status)
        assertEquals(null, result.recoup22)
    }

    @Test fun archiveFormatVersionCannotMasqueradeAsEngineVersion() {
        val unknown = IntradayPicks.toRound(StructuredPickArtifact(session = "realtime"), "2026-11-02T20:00:00+08:00", "realtime")
        assertTrue(unknown.has("schemaVersion"))
        assertTrue(!unknown.has("version"))
        val known = IntradayPicks.toRound(StructuredPickArtifact(metadata = mapOf("engineVersion" to "spec-7")), "2026-11-02T20:00:00+08:00")
        assertEquals("spec-7", known.getString("version"))
    }

}
