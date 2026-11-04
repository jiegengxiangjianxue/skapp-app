package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.OffsetDateTime

/** 真实归档成绩，不以MA金叉/事后择股替代。百分比收益与回本布尔值分别公开。 */
object PickBacktest {
    data class Series(
        val bars: List<KBar>,
        val source: String,
        val adjustmentEvidence: String? = null,
        /** 必须来自真实交易日历，包含信号日后全部交易日；不能以有K线的日期冒充日历。 */
        val tradingDates: List<String> = emptyList(),
        val benchmarkBars: List<KBar> = emptyList(),
        val benchmarkEngine: String? = null,
        val benchmarkVersion: String? = null,
        val benchmarkSource: String? = null,
        val benchmarkAdjustmentEvidence: String? = null,
    )
    data class Evaluation(
        val record: PickHistoryRepository.Record,
        val status: String,
        val entryDate: String?,
        val entryPrice: Double?,
        val recoup22: Boolean?,
        val recoup60: Boolean?,
        val netReturn22: Double?,
        val netReturn60: Double?,
        val benchmarkNetReturn22: Double?,
        val benchmarkNetReturn60: Double?,
        val feePerSide: Double,
        val source: String,
        val adjustmentEvidence: String?,
        val reasons: List<String>,
    )
    data class Result(
        val rows: List<Evaluation>,
        val archive: PickHistoryRepository.LoadResult,
        val mature22: Int,
        val mature60: Int,
        val recoupRate22: Double?,
        val recoupRate60: Double?,
        val status: String,
        val reasons: List<String>,
    )

    fun evaluate(
        record: PickHistoryRepository.Record,
        series: Series,
        asOfDate: String = LocalDate.now(AnalysisSessionPolicy.zone).toString(),
        feePerSide: Double = 0.001,
    ): Evaluation {
        require(feePerSide.isFinite() && feePerSide >= 0 && feePerSide < 1)
        val reasons = mutableListOf<String>()
        val signalDay = try {
            OffsetDateTime.parse(record.ts).atZoneSameInstant(AnalysisSessionPolicy.zone).toLocalDate().toString()
        } catch (_: Exception) { null }
        if (record.session !in setOf("premarket", "realtime", "morning", "midday", "intraday")) reasons.add("归档时段未知，不计入成绩")
        if (signalDay == null) reasons.add("信号时间缺失或无时区证据")
        if (record.engine == null || record.version == null) reasons.add("归档引擎/版本缺失，不混算成绩")
        if (series.source.isBlank() || series.adjustmentEvidence?.startsWith("cnfin:hfq_factor:") != true) {
            reasons.add("同口径cnfin hfq_factor复权证据不足")
        }
        val asOf = try { LocalDate.parse(asOfDate).toString() } catch (_: Exception) { null }
        if (asOf == null) reasons.add("asOfDate非法")
        val dates = series.tradingDates
        val calendarValid = dates.isNotEmpty() && dates == dates.distinct().sorted() && dates.all {
            try { LocalDate.parse(it); true } catch (_: Exception) { false }
        }
        if (!calendarValid) reasons.add("真实连续交易日历缺失/非法，不能以K线根数冒充持有交易日")
        fun insufficient() = Evaluation(record, "insufficient", null, null, null, null, null, null,
            null, null, feePerSide, series.source, series.adjustmentEvidence, reasons.toList())
        if (reasons.isNotEmpty()) return insufficient()
        val sessions = dates.filter { it > signalDay!! && it <= asOf!! }
        if (sessions.isEmpty()) { reasons.add("信号后尚无可执行交易日"); return insufficient() }
        val bars = series.bars.associateBy { it.date }
        if (bars.size != series.bars.size) { reasons.add("重复日期K线"); return insufficient() }
        val first = bars[sessions.first()]
        if (first == null || !validBar(first)) { reasons.add("信号后首个交易日开盘价缺失/非法，不能跳过停牌/缺口假设成交"); return insufficient() }
        val entry = first.open
        fun net(price: Double, start: Double = entry): Double = ((price * (1 - feePerSide)) / (start * (1 + feePerSide)) - 1) * 100
        fun horizon(days: Int): Pair<Boolean?, Double?> {
            if (sessions.size < days) { reasons.add("${days}交易日样本未成熟"); return null to null }
            val window = sessions.take(days).map { bars[it] }
            if (window.any { it == null || !validBar(it) }) { reasons.add("${days}交易日K线有缺口/非法"); return null to null }
            val valid = window.filterNotNull()
            return valid.any { net(it.high) >= 0 } to net(valid.last().close)
        }
        val (recoup22, net22) = horizon(22)
        val (recoup60, net60) = horizon(60)
        val baselineVerified = series.benchmarkEngine == record.engine && series.benchmarkVersion == record.version &&
            !series.benchmarkSource.isNullOrBlank() && series.benchmarkAdjustmentEvidence == series.adjustmentEvidence
        if (!baselineVerified) reasons.add("缺少同引擎同版本同复权口径基准，基准收益为null")
        val benchmark = series.benchmarkBars.associateBy { it.date }
        fun benchmarkNet(days: Int): Double? {
            if (!baselineVerified || sessions.size < days || benchmark.size != series.benchmarkBars.size) return null
            val window = sessions.take(days).map { benchmark[it] }
            if (window.any { it == null || !validBar(it) }) return null
            val valid = window.filterNotNull()
            return net(valid.last().close, valid.first().open)
        }
        val b22 = benchmarkNet(22)
        val b60 = benchmarkNet(60)
        if (baselineVerified && ((net22 != null && b22 == null) || (net60 != null && b60 == null))) reasons.add("基准K线有缺口")
        reasons.add("执行假设：信号后首个真实交易日开盘买入；非实际成交；单边费用=$feePerSide；回本含双边费用，与期末净收益分列")
        return Evaluation(record,
            if (net22 == null && net60 == null) "insufficient" else if (net60 != null && b60 != null) "ok" else "partial",
            sessions.first(), entry, recoup22, recoup60, net22, net60, b22, b60,
            feePerSide, series.source, series.adjustmentEvidence, reasons,
        )
    }

    private fun validBar(bar: KBar): Boolean =
        listOf(bar.open, bar.close, bar.high, bar.low).all { it.isFinite() && it > 0 } &&
            bar.high >= maxOf(bar.open, bar.close) && bar.low <= minOf(bar.open, bar.close)

    /** 默认真实取数链缺复权/交易日历证据时明确insufficient，不联网补造财报或收益。 */
    suspend fun load(maxFiles: Int = 30, maxRecords: Int = 300): Result = coroutineScope {
        val archive = PickHistoryRepository.load(maxFiles, maxRecords.coerceIn(1, 500))
        val series = linkedMapOf<String, Series>()
        val failures = mutableListOf<String>()
        for (batch in archive.records.map { it.code }.distinct().chunked(4)) {
            val loaded = batch.map { code -> async(Dispatchers.IO) {
                try {
                    val bars = withTimeoutOrNull(20_000L) { DataCache.kline(code, "qfq", 320) }
                    code to Series(bars.orEmpty(), "DataCache:$code:qfq; actual_adjustment_unverified")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { code to Series(emptyList(), "DataCache:$code:failed") }
            } }.map { it.await() }
            loaded.forEach { (code, value) ->
                series[code] = value
                if (value.bars.isEmpty()) failures.add("DataCache:$code:timeout_empty_or_failed")
            }
        }
        val rows = archive.records.map { evaluate(it, series.getValue(it.code)) }
        // 每次修订是不同信号；必须逐引擎/版本/时段展示，不能把多个口径汇成一个胜率。
        val cohorts = rows.map { Triple(it.record.engine, it.record.version, it.record.session) }.distinct()
        val r22 = rows.mapNotNull { it.recoup22 }
        val r60 = rows.mapNotNull { it.recoup60 }
        Result(rows, archive, r22.size, r60.size,
            if (r22.isEmpty() || cohorts.size != 1) null else r22.count { it }.toDouble() / r22.size,
            if (r60.isEmpty() || cohorts.size != 1) null else r60.count { it }.toDouble() / r60.size,
            if (rows.isEmpty() || rows.all { it.status == "insufficient" }) "insufficient" else "partial",
            failures + listOf("每条归档修订独立留存；非独立股票样本，不与MA金叉胜率混用", "跨引擎/版本/时段不汇总胜率；默认取数缺复权与真实交易日历证据"),
        )
    }
}
