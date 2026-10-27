package com.skapp.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 北京时间口径的时间标签。**全 App 唯一入口**，别再各写 `SimpleDateFormat(...)`。
 *
 * 为什么必须显式指定时区：`SimpleDateFormat` 默认用设备时区，
 * - 用户在国外 / 模拟器跑 UTC 时，「今天」会偏成昨天或明天，RPS 的 date、
 *   周期池的样本区间、推股产物的交易日全部跟着错；
 * - 云端产物的 ts 是北京时间，本地拼的标签若按别的时区渲染，界面同一屏里会出现两种口径。
 *
 * A 股的所有「日」「时点」都指北京时间，所以这里固定 CST。
 */
object CstTime {

    fun fmt(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone(MarketHours.TZ_CST)
        }

    /** yyyy-MM-dd HH:mm:ss */
    fun iso(ts: Long = System.currentTimeMillis()): String = fmt("yyyy-MM-dd HH:mm:ss").format(Date(ts))

    /** yyyy-MM-dd */
    fun day(ts: Long = System.currentTimeMillis()): String = fmt("yyyy-MM-dd").format(Date(ts))

    /** 解析 yyyy-MM-dd HH:mm:ss；解析失败返回 null。 */
    fun parseIso(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.length < 10) return null
        return runCatching { fmt("yyyy-MM-dd HH:mm:ss").parse(t)?.time }
            .getOrNull()
            ?: runCatching { fmt("yyyy-MM-dd").parse(t.take(10))?.time }.getOrNull()
    }
}
