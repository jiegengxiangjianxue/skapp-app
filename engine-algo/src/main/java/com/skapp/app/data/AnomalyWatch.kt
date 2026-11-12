package com.skapp.app.data

import java.time.Instant
import java.time.ZoneId

/** Project experiment thresholds, not book calibration and not a probability model. */
object AnomalyWatch {
    const val SUMMARY = "试验前兆，未验证预测准确率，观察非交易指令"
    const val MAX_AGE_MS = 5 * 60_000L
    private const val MAX_INTERVAL_MS = 2 * 60_000L
    private const val MAX_RECORDS = 2000
    private val zone = ZoneId.of("Asia/Shanghai")

    data class Quote(
        val code: String, val name: String, val price: Double?, val pctChg: Double?,
        val high: Double?, val low: Double?, val volume: Double?, val volRatio: Double?,
        val mainNet: Double?, val sourceAtMs: Long, val source: String,
    )
    data class State(
        val day: String = "", val previous: Map<String, Quote> = emptyMap(),
        val records: List<AnomalyAlert> = emptyList(),
    )

    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun session(at: Long): String? {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        if (local.dayOfWeek.value > 5) return null
        val minute = local.hour * 60 + local.minute
        return when (minute) {
            in 570..689 -> "${local.toLocalDate()}:am"
            in 780..899 -> "${local.toLocalDate()}:pm"
            else -> null
        }
    }
    fun fresh(q: Quote, nowMs: Long): Boolean = q.sourceAtMs in 1..nowMs &&
        nowMs - q.sourceAtMs <= MAX_AGE_MS && day(q.sourceAtMs) == day(nowMs) &&
        q.source.isNotBlank() && q.price?.let { it.isFinite() && it > 0 } == true

    fun update(input: State, quotes: List<Quote>, nowMs: Long): State {
        val today = day(nowMs)
        val state = if (input.day == today) input else State(day = today)
        val previous = state.previous.toMutableMap()
        val records = state.records.toMutableList()
        for (q in quotes.sortedBy { it.sourceAtMs }) {
            if (!fresh(q, nowMs)) continue
            val currentPrice = q.price ?: continue
            val prev = previous[q.code]
            if (prev != null && q.sourceAtMs <= prev.sourceAtMs) continue
            previous[q.code] = q
            val active = records.indexOfLast { it.code == q.code && it.status == "pending" }
            if (active >= 0) {
                val alert = records[active]
                val status = when {
                    q.sourceAtMs > alert.expiresAtMs -> "expired"
                    session(q.sourceAtMs) != session(alert.triggerAtMs) || q.source != alert.source -> "unproven"
                    currentPrice < alert.invalidBelow || q.low?.let { it.isFinite() && it < alert.invalidBelow } == true -> "invalidated"
                    q.low?.let { it.isFinite() && it > 0 && it <= currentPrice } != true -> "unproven"
                    currentPrice > alert.frozenBreakoutPrice && q.sourceAtMs > alert.triggerAtMs -> "fulfilled"
                    else -> null
                }
                if (status != null) records[active] = alert.copy(status = status, resultAtMs = q.sourceAtMs,
                    resultReason = when (status) {
                        "expired" -> "超过固定5分钟期限，晚到上涨不算命中"
                        "unproven" -> "sessiongap、源切换或缺少有效低点，无法完整验证"
                        "invalidated" -> "跌破触发时低点，失效优先于突破"
                        else -> "后续源报价越过触发前已冻结的前高"
                    })
                continue
            }
            // One observation per symbol/day also prevents a terminal record being repeatedly re-armed.
            if (records.any { it.code == q.code } || records.size >= MAX_RECORDS || prev == null) continue
            if (!Code.isMainBoard(q.code) || q.name.isBlank() || q.name.trim().lowercase() in setOf("null", "--") ||
                NameRules.isSTOrDelisting(q.name) || session(q.sourceAtMs) == null ||
                session(q.sourceAtMs) != session(prev.sourceAtMs) || q.source != prev.source ||
                q.sourceAtMs - prev.sourceAtMs !in 1..MAX_INTERVAL_MS) continue
            val target = prev.high?.takeIf { it.isFinite() && it > 0 } ?: continue
            val price = currentPrice
            val priorPrice = prev.price ?: continue
            val pct = q.pctChg?.takeIf { it.isFinite() } ?: continue
            val low = q.low?.takeIf { it.isFinite() && it > 0 && it <= price } ?: continue
            val volume = q.volume?.takeIf { it.isFinite() && it > 0 } ?: continue
            val priorVolume = prev.volume?.takeIf { it.isFinite() && it >= 0 } ?: continue
            if (priorPrice > target || price > target || (target - price) / target > 0.005 ||
                pct < 0 || pct >= 3.0 || volume <= priorVolume ||
                q.high?.let { !it.isFinite() || it > target || it < price } != false) continue
            val volumeEvidence = q.volRatio?.let { it.isFinite() && it >= 1.5 } == true
            val flowEvidence = q.mainNet?.let { it.isFinite() && it > 0 } == true
            if (!volumeEvidence && !flowEvidence) continue
            val expiry = q.sourceAtMs + MAX_AGE_MS
            val sessionGap = session(expiry) != session(q.sourceAtMs)
            records += AnomalyAlert(q.code, q.name, q.sourceAtMs, price, target, expiry, low,
                "项目试验参数：距前高≤0.5%、涨幅[0,3%)、源间隔≤2分钟、累计量增加、" +
                    if (volumeEvidence) "量比≥1.5" else "真实主力净额为正",
                q.source, if (sessionGap) "unproven" else "pending",
                if (sessionGap) q.sourceAtMs else null,
                if (sessionGap) "sessiongap：5分钟窗口跨午休或收盘" else "待后续源报价验证")
        }
        // Missing quotes cannot keep an observation alive beyond its frozen deadline.
        for (i in records.indices) {
            val alert = records[i]
            if (alert.status == "pending" && nowMs > alert.expiresAtMs) records[i] = alert.copy(
                status = "expired", resultAtMs = alert.expiresAtMs,
                resultReason = "固定期限内未取得有效突破证据")
        }
        return State(today, previous.toMap(), records.toList())
    }
}
