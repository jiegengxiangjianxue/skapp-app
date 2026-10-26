package com.skapp.app.data

/**
 * 玄学降权引擎 (云端 calendar_alpha 362万样本结论的端侧版)
 * 权重 0.2 参与评分, 无投票权 (用户定调)
 * 数据源: 日历(本地) + 天气 wttr.in(公开API, 无需token)
 */
object MysticEngine {
    // 云端 35 年统计: 2月最强 +0.203%/日, 1月最弱 -0.079%/日
    // 周二最强 +0.110%/日, 周五最弱 -0.015%/日
    fun dailyScore(dayOfWeek: Int, month: Int): Double {
        val cal = CloudEngines.calendarWeight(dayOfWeek, month)
        // 归一化到 0-100
        return ((cal - 0.5) / 1.5 * 100).coerceIn(0.0, 100.0)
    }

    // 天气-电力逻辑 (真实供需, 唯一过数据关的玄学联动)
    // 高温/寒冬 → 用电高峰 → 电力股情绪加成 (长江电力/华能水电等)
    suspend fun weatherPowerBoost(city: String = "beijing"): Pair<String, Double> {
        return try {
            val url = "https://wttr.in/$city?format=j1"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().readText()
            val temp = org.json.JSONObject(body).getJSONArray("current_condition")
                .getJSONObject(0).getDouble("temp_C")
            when {
                temp >= 35 -> "高温${temp}℃ 电力负荷高峰" to 75.0
                temp <= -5 -> "严寒${temp}℃ 供暖用电高峰" to 70.0
                else -> "气温${temp}℃ 电力中性" to 50.0
            }
        } catch (e: Exception) { "天气数据不可用" to 50.0 }
    }

    // 八字/星座/尾数: 云端证伪 (尾8+0.027%不显著, 尾4为幸存者偏差) → 仅文化展示, 不计分
    fun cultureNote(dayOfMonth: Int): String =
        if (dayOfMonth % 4 == 0) "尾4票历史溢价系退市偏差, 不计分" else "数理因子未通过数据关, 仅文化参考"
}
