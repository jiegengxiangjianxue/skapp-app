package com.skapp.app.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 财报后漂移（PEAD）事件表 —— 解析云端 `pead_events.json`。
 *
 * 云端按公告日倒序只输出最近 90 天公告的事件（见 stocks-ai-view `scripts/pead_engine.py`
 * 的 `events_window`）。端上**不把它接进打分**：实测 SUE 分位与公告后 10 日收益方向与
 * 教科书相反（最高分位胜率 49.75%、最低分位 54.90%），且样本窗口只有两个月，给它开权重
 * 等于把方向未定的因子塞进综合分。这里只做「可核验的展示」。
 */
object PeadEvents {

    /**
     * 漂移窗口的自然日上限。
     *
     * CAR 按 5/10/20 个**交易日**算，20 个交易日的自然日跨度约 28 天，取 30 天作为
     * 「仍在窗口内」的粗判——端上没有交易日历，宁可放宽几天，也不把刚过窗口的事件说成有效。
     */
    const val WINDOW_CALENDAR_DAYS = 30L

    data class Book(
        val ts: String?,
        /** 按 symbol 索引；一只股票一条（云端只输出每条股票最近一次公告） */
        val events: Map<String, PeadEvent>,
        /** 云端事件窗口的截止日（早于它的公告不会出现） */
        val cutoff: String?,
        val nOutput: Int,
        /** 云端自带的告警（如「只输出最近 N 天」），转发给界面便于解释覆盖率 */
        val notes: List<String>,
    )

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun parse(json: JSONObject?): Book? {
        val arr = json?.optJSONArray("events") ?: return null
        val events = HashMap<String, PeadEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol").takeIf { it.isNotBlank() } ?: continue
            val announce = o.optString("announce_date").take(10)
            if (announce.length < 10) continue
            events[symbol] = PeadEvent(
                symbol = symbol,
                announceDate = announce,
                eps = o.numOrNull("eps"),
                sue = o.numOrNull("sue"),
                nQuarters = o.optInt("n_quarters", 0),
                car5d = o.numOrNull("car_5d"),
                car10d = o.numOrNull("car_10d"),
                car20d = o.numOrNull("car_20d"),
            )
        }
        val notes = buildList {
            val warnings = json.optJSONArray("warnings")
            if (warnings != null) {
                for (i in 0 until warnings.length()) {
                    warnings.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val window = json.optJSONObject("events_window")
        return Book(
            ts = json.optString("ts").takeIf { it.isNotBlank() },
            events = events,
            cutoff = window?.optString("cutoff")?.takeIf { it.isNotBlank() },
            nOutput = window?.optInt("n_output", events.size) ?: events.size,
            notes = notes,
        )
    }

    /** 公告距今自然日；公告日解析不出为 null（不拿 0 顶替，那会被读成「今天刚公告」）。 */
    fun daysSince(announceDate: String, now: Long = System.currentTimeMillis()): Long? {
        val date = runCatching { dayFmt.parse(announceDate.take(10)) }.getOrNull() ?: return null
        return (now - date.time) / 86_400_000L
    }

    /** 是否仍在漂移窗口内；公告日解析不出为 null。 */
    fun inWindow(event: PeadEvent, now: Long = System.currentTimeMillis()): Boolean? {
        val days = daysSince(event.announceDate, now) ?: return null
        return days in 0..WINDOW_CALENDAR_DAYS
    }

    /**
     * 展示用的一句话口径：窗口状态 + 样本方向提醒。
     *
     * 样本方向必须跟着数据一起给：单独看「SUE 1.56 很高」会让人以为该买，
     * 而实测最高分位的后 10 日胜率反而略低于 50%。
     */
    fun caveatOf(event: PeadEvent, now: Long = System.currentTimeMillis()): String {
        val days = daysSince(event.announceDate, now)
        val window = inWindow(event, now)
        val state = when {
            window == null -> "公告日无法解析"
            window -> "距今 $days 天，仍在漂移窗口内"
            else -> "距今 $days 天，漂移窗口已过（仅作历史参考）"
        }
        return "$state；口径：个股绝对收益（未剔除市场），不参与评分"
    }

    private fun JSONObject.numOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
