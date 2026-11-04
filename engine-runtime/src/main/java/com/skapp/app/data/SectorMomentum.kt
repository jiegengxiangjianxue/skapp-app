package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** 横截面动量不叫轮动；只有可核验历史证据才输出跨日收益。 */
object SectorMomentum {
    data class Row(
        val name: String,
        val return1d: Double?,
        val return5d: Double?,
        val return20d: Double?,
        val mainNet: Double?,
        val breadth: Double?,
        val memberCount: Int,
        val sampleCount1d: Int,
        val sampleCount5d: Int,
        val sampleCount20d: Int,
        val flowSampleCount: Int,
        val sampleCodes: List<String>,
        val dataDate: String?,
        val adjustmentEvidence: String?,
        val status: String,
        val sampleCodes5d: List<String> = emptyList(),
        val sampleCodes20d: List<String> = emptyList(),
    )

    data class Result(
        val rows: List<Row>,
        val total: Int,
        val mapped: Int,
        val coverage: Double?,
        val snapshotAt: String?,
        val source: String,
        val status: String,
        val issues: List<String>,
    )

    /** adjustmentEvidence 必须指向 cnfin hfq_factor 的同一快照，不能以请求参数 qfq 代替。 */
    fun compute(
        snapshot: List<LiveMarket.Row>,
        sectorOf: (String) -> String?,
        barsByCode: Map<String, List<KBar>> = emptyMap(),
        adjustmentEvidence: Map<String, String> = emptyMap(),
        dataDate: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        source: String = "LiveMarket / SectorLink / DataCache",
        issues: List<String> = emptyList(),
    ): Result {
        val clean = snapshot.distinctBy { it.code }
        val agg = LocalSectors.aggregate(clean, sectorOf)
        val grouped = clean.mapNotNull { row ->
            sectorOf(row.code)?.takeIf { it.isNotBlank() }?.let { it to row }
        }.groupBy({ it.first }, { it.second })
        val rows = agg.rows.map { sector ->
            val members = grouped[sector.name].orEmpty()
            val fresh = members.filter { FreshnessPolicy.snapshotStatus(it.ts, nowMs) == "live" }
            val dayValues = fresh.mapNotNull { it.pctChg?.takeIf(Double::isFinite) }
            val trustedEvidence = members.mapNotNull { adjustmentEvidence[it.code] }
                .filter { it.startsWith("cnfin:hfq_factor:") }.distinct().singleOrNull()
            fun historical(days: Int): List<Pair<String, Double>> = members.mapNotNull { row ->
                val evidence = adjustmentEvidence[row.code]
                if (dataDate == null || trustedEvidence == null || evidence != trustedEvidence) return@mapNotNull null
                val raw = barsByCode[row.code].orEmpty()
                val bars = raw.sortedBy { it.date }.distinctBy { it.date }
                if (bars.size != raw.size || bars.lastOrNull()?.date != dataDate || bars.size <= days) return@mapNotNull null
                val end = bars.last().close
                val start = bars[bars.lastIndex - days].close
                if (!end.isFinite() || !start.isFinite() || end <= 0 || start <= 0) null
                else row.code to (end / start - 1.0) * 100.0
            }
            val five = historical(5)
            val twenty = historical(20)
            val flows = fresh.mapNotNull { it.mainNet?.takeIf(Double::isFinite) }
            val up = dayValues.count { it > 0 }
            val down = dayValues.count { it < 0 }
            val evidence = members.mapNotNull { adjustmentEvidence[it.code] }.distinct()
            Row(
                name = sector.name,
                return1d = dayValues.takeIf { it.isNotEmpty() }?.average(),
                return5d = five.takeIf { it.isNotEmpty() }?.map { it.second }?.average(),
                return20d = twenty.takeIf { it.isNotEmpty() }?.map { it.second }?.average(),
                mainNet = flows.takeIf { it.isNotEmpty() }?.sum(),
                breadth = if (up + down > 0) up.toDouble() / (up + down) else null,
                memberCount = members.size,
                sampleCount1d = dayValues.size,
                sampleCount5d = five.size,
                sampleCount20d = twenty.size,
                flowSampleCount = flows.size,
                sampleCodes = members.map { it.code }.sorted(),
                dataDate = dataDate,
                adjustmentEvidence = evidence.singleOrNull()?.takeIf { it.startsWith("cnfin:hfq_factor:") },
                status = if (dayValues.isEmpty()) "insufficient" else if (twenty.isEmpty()) "partial" else "ok",
                sampleCodes5d = five.map { it.first },
                sampleCodes20d = twenty.map { it.first },
            )
        }.sortedByDescending { it.return1d ?: Double.NEGATIVE_INFINITY }
        val gaps = buildList {
            addAll(issues)
            if (clean.isEmpty()) add("行情快照为空")
            if (agg.unmapped > 0) add("${agg.unmapped}只无板块映射，不纳入板块")
            if (dataDate == null) add("行情交易日期未知；snapshotAt是抓取时间，不冒充行情日期")
            if (rows.any { it.return5d == null || it.return20d == null }) {
                add("5/20日收益证据不足：DataCache qfq未携带cnfin hfq_factor，不能给可信长期结论")
            }
            add("等权样本收益；宽度=上涨/(上涨+下跌)，资金为已覆盖样本之和；单日横截面不代表轮动")
        }
        return Result(
            rows, clean.size, agg.mapped,
            if (clean.isEmpty()) null else agg.coverage,
            clean.minOfOrNull { it.ts }?.let { Instant.ofEpochMilli(it).toString() },
            source,
            if (rows.none { it.return1d != null }) "insufficient" else if (rows.all { it.status == "ok" } && dataDate != null) "ok" else "partial",
            gaps,
        )
    }

    /** 固定代码序样本，不用涨幅榜赢家冒充全板块；4并发、每源20秒，无本地数据库。 */
    suspend fun load(force: Boolean = false, maxStocks: Int = 120): Result = coroutineScope {
        val failures = mutableListOf<String>()
        suspend fun <T> fetch(label: String, block: suspend () -> T): T? = try {
            withTimeoutOrNull(20_000L) { block() }.also {
                if (it == null) failures.add("$label: timeout_or_missing")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failures.add("$label: failed")
            null
        }
        val snapshot = fetch("LiveMarket") { LiveMarket.snapshot(force) }.orEmpty()
        val board = fetch("SectorLink") { SectorLink.load(force) }
        val chosen = snapshot.filter { board?.sectorNameOf(it.code) != null }
            .sortedBy { it.code }.take(maxStocks.coerceIn(0, 300))
        val bars = linkedMapOf<String, List<KBar>>()
        for (batch in chosen.chunked(4)) {
            // 每个子任务返回失败信息，避免跨线程修改同一可变列表。
            val results = batch.map { row -> async(Dispatchers.IO) {
                try {
                    val value = withTimeoutOrNull(20_000L) { DataCache.kline(row.code, "qfq", 21, force) }
                    Triple(row.code, value.orEmpty(), if (value.isNullOrEmpty()) "DataCache ${row.code}: timeout_or_empty" else null)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Triple(row.code, emptyList<KBar>(), "DataCache ${row.code}: failed") }
            } }.map { it.await() }
            results.forEach { (code, value, failure) ->
                bars[code] = value
                if (failure != null) failures.add(failure)
            }
        }
        failures.add("历史样本=${chosen.size}/${snapshot.size}，固定代码序；未提供可核验复权证据")
        compute(snapshot, { board?.sectorNameOf(it) }, barsByCode = bars, issues = failures)
    }
}
