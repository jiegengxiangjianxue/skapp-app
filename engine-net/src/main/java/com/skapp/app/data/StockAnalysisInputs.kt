package com.skapp.app.data

import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

fun interface StockInputTransport {
    suspend fun get(url: String): String?
}

/** Partial evidence is retained for observation; absence is never converted to a zero. */
data class StockAnalysisInputs(
    val code: String,
    val eps: Double? = null,
    val dps: Double? = null,
    val reportPeriod: String? = null,
    val source: String? = null,
    val snapshotAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val bars: List<KBar> = emptyList(),
    val barsFingerprint: String? = null,
    val adjustmentEvidence: String? = null,
    val missing: List<String> = emptyList(),
    val barSources: Map<String, String> = emptyMap(),
    val financialFields: Map<String, Any?> = emptyMap(),
) {
    fun gaps(nowMs: Long): List<String> = buildList {
        addAll(missing)
        if (eps?.isFinite() != true) add("EPS")
        if (dps?.isFinite() != true) add("DPS")
        if (date(reportPeriod, nowMs) == null) add("report_period")
        if (source.isNullOrBlank()) add("source")
        if (snapshotAtMs == null || snapshotAtMs !in 1..nowMs) add("source_time")
        if (expiresAtMs == null || expiresAtMs < nowMs) add("source_stale")
        if (adjustmentEvidence == null) add("cnfin_benchmark_unverified：基于未复权数据")
        if (barsFingerprint == null || barsFingerprint != barsFingerprint(bars)) add("bars_binding")
    }.distinct()

    companion object {
        private val zone = ZoneId.of("Asia/Shanghai")
        // Source publication time, never fetch time, controls this conservative observation TTL.
        private const val SOURCE_TTL_MS = 24 * 60 * 60 * 1000L

        fun barsFingerprint(bars: List<KBar>): String = sha256(bars.joinToString("\n") {
            "${it.date}|${it.open.toBits()}|${it.close.toBits()}|${it.high.toBits()}|${it.low.toBits()}|${it.vol.toBits()}"
        }.toByteArray(Charsets.UTF_8))

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun date(value: String?, nowMs: Long): LocalDate? = try {
            value?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
                ?.let(LocalDate::parse)?.takeIf { it <= Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() }
        } catch (_: Exception) { null }

        private fun timestamp(value: String?, nowMs: Long): Long? {
            if (value.isNullOrBlank()) return null
            val parsed = try { Instant.parse(value).toEpochMilli() } catch (_: Exception) {
                try { LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) {
                    date(value, nowMs)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
                }
            }
            return parsed?.takeIf { it in 1..nowMs }
        }

        internal val benchmarkHashes = mapOf(
            "600519" to "b1a39e978bc37ae2a8fa3027acefaa6355f2fb145bdb5fbc07462a2261012400",
            "002242" to "4baf5881b6a571486737c593da15e9dede3b41f564905981fddfa07df98c8535",
        )

        /** Hash is checked on the original UTF-8 payload including BOM and line endings. */
        fun benchmark(code: String, text: String, nowMs: Long): StockAnalysisInputs? {
            val expected = benchmarkHashes[code] ?: return null
            if (sha256(text.toByteArray(Charsets.UTF_8)) != expected) return null
            val lines = text.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
            val header = lines.firstOrNull()?.split(',') ?: return null
            val bars = mutableListOf<KBar>()
            val sources = linkedMapOf<String, String>()
            var anchorFactor = 1.0
            for (line in lines.drop(1)) {
                val values = line.split(',')
                fun field(name: String): String? = header.indexOf(name).takeIf { it >= 0 }?.let { values.getOrNull(it) }
                fun number(name: String): Double? = field(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                val day = date(field("date"), nowMs)?.toString() ?: return null
                val source = field("source")?.takeIf { it in setOf("cnfin", "api", "csv_legacy") } ?: return null
                val factor = number("hfq_factor")?.takeIf { it > 0 } ?: return null
                fun adjusted(name: String): Double? {
                    val raw = number("${name}_price") ?: return null
                    val adjusted = number("${name}_hfq") ?: return null
                    val expectedPrice = raw * factor
                    if (kotlin.math.abs(adjusted - expectedPrice) > maxOf(1e-8, kotlin.math.abs(expectedPrice) * 1e-9)) return null
                    return adjusted
                }
                val bar = KBar(day, adjusted("open") ?: return null, adjusted("close") ?: return null,
                    adjusted("high") ?: return null, adjusted("low") ?: return null, number("volume") ?: return null)
                if (bar.low <= 0 || bar.high < maxOf(bar.open, bar.close) || bar.low > minOf(bar.open, bar.close) || bar.vol < 0) return null
                if (bars.lastOrNull()?.date?.let { it >= day } == true) return null
                bars += bar
                anchorFactor = factor
                sources[day] = source
            }
            val last = bars.lastOrNull() ?: return null
            val time = LocalDate.parse(last.date).atStartOfDay(zone).toInstant().toEpochMilli()
            val anchored = bars.map { it.copy(open = it.open / anchorFactor, close = it.close / anchorFactor,
                high = it.high / anchorFactor, low = it.low / anchorFactor) }
            return StockAnalysisInputs(code, source = RemoteSources.stockBenchmark(code), snapshotAtMs = time,
                expiresAtMs = time + SOURCE_TTL_MS, bars = anchored, barsFingerprint = barsFingerprint(anchored),
                adjustmentEvidence = "cnfin:hfq_factor:approved_benchmark:$expected", barSources = sources)
        }

        private data class Report(val period: String, val eps: Double?, val dps: Double?, val source: String, val time: Long?, val fields: Map<String, Any?>)

        fun financial(code: String, api: JSONObject?, holo: JSONObject?, nowMs: Long): StockAnalysisInputs {
            val reports = mutableListOf<Report>()
            fun collect(item: JSONObject?, root: JSONObject, source: String) {
                if (item == null) return
                val identity = item.optString("symbol").takeIf { it.isNotBlank() && it != "null" }
                if (identity != null && Code.normalize(identity) != code) return
                val period = date(item.optString("report_date"), nowMs)?.toString() ?: return
                val announced = item.optString("announce_date").takeIf { it.isNotBlank() && it != "null" }
                if (announced != null && date(announced, nowMs) == null) return
                val rawTime = item.optString("ts").takeIf { it.isNotBlank() && it != "null" } ?: root.optString("ts")
                if (rawTime.isNotBlank() && rawTime != "null" && timestamp(rawTime, nowMs) == null) return
                fun number(key: String): Double? = if (item.isNull(key)) null else item.optDouble(key, Double.NaN).takeIf { it.isFinite() }
                reports += Report(period, number("eps"), number("dps"), source, timestamp(rawTime, nowMs), item.toPlainMap())
            }
            if (api != null) {
                val detail = api.optJSONObject("detail")
                val symbol = api.optString("code").takeIf { it.isNotBlank() && it != "null" }
                    ?: detail?.optString("code")
                val detailCode = detail?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }
                if (symbol != null && Code.normalize(symbol) == code && (detailCode == null || Code.normalize(detailCode) == code)) {
                    val timeRoot = if (detail?.optString("ts")?.takeIf { it.isNotBlank() && it != "null" } != null) detail else api
                    collect(detail?.optJSONObject("financial_latest"), timeRoot ?: api,
                        RemoteSources.apiStock(RemoteSources.OWNER_A, RemoteSources.REPO_A, code))
                }
            }
            if (holo != null && Code.normalize(holo.optString("symbol")) == code) {
                val array = holo.optJSONArray("reports")
                for (i in 0 until (array?.length() ?: 0)) collect(array?.optJSONObject(i), holo, RemoteSources.stockFinance(code))
            }
            val period = reports.maxOfOrNull { it.period }
            val matching = reports.filter { it.period == period }
            val used = matching.filter { it.eps != null || it.dps != null }.ifEmpty { matching }
            val time = if (used.isNotEmpty() && used.all { it.time != null }) used.minOf { it.time!! } else null
            return StockAnalysisInputs(code, matching.firstNotNullOfOrNull { it.eps }, matching.firstNotNullOfOrNull { it.dps }, period,
                used.map { it.source }.distinct().joinToString(";").takeIf { it.isNotEmpty() }, time, time?.plus(SOURCE_TTL_MS), financialFields = matching.reversed().flatMap { it.fields.entries.map { e -> e.key to e.value } }.toMap())
        }
    }
}
