package com.skapp.app.data

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * A 股交易时段判断（本地运行，不依赖服务端）。
 *
 * 交易规则（沪深京，2026 年现行）：
 *   09:15-09:25  集合竞价（开盘竞价，可挂单 09:15-09:25，09:20-09:25 不可撤单）
 *   09:30-11:30  连续竞价（上午盘）
 *   13:00-15:00  连续竞价（下午盘）
 *   11:30-13:00  午间休市
 *   15:00-15:05  收盘集合竞价（深市）
 *   周末、法定节假日休市
 *
 * 说明：法定节假日按年份硬编码（国务院发布的放假安排），
 *       每年需更新；未知年份按「周六周日休市」保守处理。
 */
object MarketHours {

    const val TZ_CST = "Asia/Shanghai"

    enum class Phase {
        PRE_OPEN,      // 盘前（< 09:15）
        AUCTION_OPEN,  // 开盘集合竞价 09:15-09:25
        MORNING,       // 上午连续竞价 09:30-11:30
        LUNCH_BREAK,   // 午间休市 11:30-13:00
        AFTERNOON,     // 下午连续竞价 13:00-15:00
        CLOSING,       // 收盘竞价 15:00-15:05
        AFTER_HOURS,   // 盘后（> 15:05）
        CLOSED_DAY,    // 休市日（周末/节假日）
    }

    /** 法定节假日（沪深休市日）。每年按国务院公布安排维护。 */
    private val HOLIDAYS_2026 = setOf(
        // 元旦 1/1-1/3
        "2026-01-01", "2026-01-02", "2026-01-03",
        // 春节 2/15-2/21（除夕 2/16 法定）
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18",
        "2026-02-19", "2026-02-20", "2026-02-21",
        // 清明 4/4-4/6
        "2026-04-04", "2026-04-05", "2026-04-06",
        // 劳动节 5/1-5/5
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        // 端午 6/19-6/21
        "2026-06-19", "2026-06-20", "2026-06-21",
        // 国庆+中秋 10/1-10/8
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
        "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
    )

    /** 调休补班日（周末但需上班/交易）。 */
    private val MAKEUP_WORKDAYS_2026 = setOf(
        "2026-02-14",  // 春节前补班
        "2026-02-28",
        "2026-05-09",
        "2026-10-10",
    )

    private fun cstNow(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(TZ_CST))

    private fun dateKey(c: Calendar): String =
        String.format(
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        )

    /** 是否休市日（周末/节假日，补班日除外） */
    fun isClosedDay(date: Date = Date()): Boolean {
        val c = Calendar.getInstance(TimeZone.getTimeZone(TZ_CST)).apply { time = date }
        val key = dateKey(c)
        if (MAKEUP_WORKDAYS_2026.contains(key)) return false
        if (HOLIDAYS_2026.contains(key)) return true
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    /** 当前所处时段 */
    fun phase(date: Date = Date()): Phase {
        if (isClosedDay(date)) return Phase.CLOSED_DAY
        val c = Calendar.getInstance(TimeZone.getTimeZone(TZ_CST)).apply { time = date }
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return when {
            mins < 9 * 60 + 15 -> Phase.PRE_OPEN
            mins < 9 * 60 + 25 -> Phase.AUCTION_OPEN
            mins < 9 * 60 + 30 -> Phase.PRE_OPEN          // 09:25-09:30 竞价结束等待
            mins < 11 * 60 + 30 -> Phase.MORNING
            mins < 13 * 60 -> Phase.LUNCH_BREAK
            mins < 15 * 60 -> Phase.AFTERNOON
            mins < 15 * 60 + 5 -> Phase.CLOSING
            else -> Phase.AFTER_HOURS
        }
    }

    /** 是否在连续竞价时段（只有这时才值得自动刷新行情） */
    fun isTrading(date: Date = Date()): Boolean =
        when (phase(date)) {
            Phase.MORNING, Phase.AFTERNOON -> true
            else -> false
        }

    /** 是否在竞价时段（开盘/收盘集合竞价）—— 也视为需要刷新 */
    fun isAuction(date: Date = Date()): Boolean =
        when (phase(date)) {
            Phase.AUCTION_OPEN, Phase.CLOSING -> true
            else -> false
        }

    /** 给用户看的中文状态说明 */
    fun describe(date: Date = Date()): String = when (val p = phase(date)) {
        Phase.PRE_OPEN -> "盘前"
        Phase.AUCTION_OPEN -> "开盘竞价"
        Phase.MORNING -> "上午交易中"
        Phase.LUNCH_BREAK -> "午间休市"
        Phase.AFTERNOON -> "下午交易中"
        Phase.CLOSING -> "收盘竞价"
        Phase.AFTER_HOURS -> "已收盘"
        Phase.CLOSED_DAY -> "休市日"
    }

    /** 距离下一次开始自动刷新的毫秒数（休市时返回 null） */
    fun msUntilAutoRefresh(date: Date = Date()): Long? {
        if (isTrading(date) || isAuction(date)) return 0L
        val c = Calendar.getInstance(TimeZone.getTimeZone(TZ_CST)).apply { time = date }
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val targetMins = when {
            mins < 9 * 60 + 15 -> 9 * 60 + 15
            mins < 13 * 60 -> 13 * 60
            mins < 15 * 60 -> 15 * 60
            else -> 9 * 60 + 15 + 24 * 60   // 次日
        }
        val deltaMin = (targetMins - mins).coerceAtLeast(0)
        return deltaMin * 60_000L
    }
}
