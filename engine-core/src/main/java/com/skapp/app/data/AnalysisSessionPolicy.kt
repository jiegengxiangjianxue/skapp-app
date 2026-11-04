package com.skapp.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 日历由调用方提供；null 不等于交易日，不能把工作日猜成交易日。 */
object AnalysisSessionPolicy {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    data class Window(
        val session: String,
        val date: String,
        val updating: Boolean,
        val frozen: Boolean,
        val calendarKnown: Boolean,
        val reason: String,
    )

    /** REALTIME 独立于早午盘，15:00 至次日 09:00（含周末）持续修订。 */
    fun window(session: String, nowMs: Long, tradingDay: Boolean? = null): Window {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val minute = now.hour * 60 + now.minute
        val active = when (session) {
            "realtime" -> minute < 540 || minute >= 900 || tradingDay == false
            "morning" -> tradingDay == true && minute >= 540 && minute < 690
            "midday" -> tradingDay == true && minute >= 690 && minute < 900
            else -> false // premarket 仅供旧档读取，不再生成新记录
        }
        val ended = when (session) {
            "morning" -> minute >= 690
            "midday" -> minute >= 900
            else -> false
        }
        return Window(
            session, now.toLocalDate().toString(), active, ended && tradingDay == true,
            tradingDay != null,
            when {
                session == "premarket" -> "legacy_read_only"
                session == "realtime" -> "independent_realtime_window; calendar=${tradingDay ?: "unknown"}"
                tradingDay == null -> "unknown_trading_day"
                tradingDay == false -> "closed_day"
                ended -> "window_ended_frozen"
                active -> "window_updating"
                else -> "window_not_started"
            },
        )
    }

    fun activeSession(nowMs: Long, tradingDay: Boolean? = null): String? {
        val minute = Instant.ofEpochMilli(nowMs).atZone(zone).let { it.hour * 60 + it.minute }
        return when {
            minute < 540 || minute >= 900 || tradingDay == false -> "realtime"
            tradingDay != true -> null
            minute < 690 -> "morning"
            else -> "midday"
        }
    }

    fun mayPublish(session: String, startedAt: Long, finishedAt: Long, tradingDay: Boolean?): Boolean {
        if (finishedAt < startedAt) return false
        val start = window(session, startedAt, tradingDay)
        val end = window(session, finishedAt, tradingDay)
        return start.updating && end.updating && start.date == end.date
    }

    /** 按实际 bar 日期截取，周一盘前保留上周五；非法/未来 bar 不参与计算。 */
    fun acceptsBar(barDate: String, nowMs: Long, excludeToday: Boolean): Boolean {
        val day = parseDate(barDate) ?: return false
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (excludeToday) day < today else day <= today
    }

    private fun parseDate(value: String): LocalDate? = try {
        val text = value.take(10)
        if (text.length == 8 && text.all { it.isDigit() }) {
            LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        } else LocalDate.parse(text)
    } catch (_: java.time.DateTimeException) {
        null
    }
}
