package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 聪明钱信号：宽基 ETF 份额跟踪（「盯份额抄作业」口径）。
 *
 * 数据合同（云端 scripts/collect_etf_shares.py 产出 data/etf_shares.json）：
 *   ts                  采集时点
 *   unit                "亿份"
 *   min_history_days    出仓位信号所需的最少历史天数（云端阈值，当前 20）
 *   window_days         百分位窗口（当前 250）
 *   smart_money         篮子仓位百分位；历史不足时为 null（云端不造假）
 *   basket[code]        name / market / latest{date,shares_yi,d1, d5, d20}
 *                       series[{date,shares_yi,source}]  ← 交易所日度真值（sse_etf_vol / szse_fund_size）
 *                       live_series[...]                 ← 旧的成交量/换手率估算，仅量级对照
 *
 * 本机只做「读取 + 展示 + 按规则调档」，不自己拟合份额：
 * - 主序列只认 series 里 source 为交易所真值的点（上交所 TOT_VOL / 深交所 current_size）；
 *   旧 live_series 是估算口径，量级接近但不可用于增减判断，只在真值缺失时兜底。
 * - 历史天数不足 min_history_days 时 ready=false：只展示可核验的日/5 日变动，不参与仓位调整。
 * - 净申购用「亿份」表达，不换算成金额（没有净值就不臆造金额）。
 */
object SmartMoney {

    private const val URL_ETF =
        "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data/etf_shares.json"

    private const val TIMEOUT = 12000

    /** 云端出仓位信号所需的最少历史天数；实际以 etf_shares.json 的 min_history_days 为准 */
    const val NEED_DAYS = 20

    /** 单只 ETF 的份额跟踪行 */
    data class EtfRow(
        val code: String,
        val name: String,
        val latestDate: String?,
        val sharesYi: Double?,
        val d1Pct: Double?,
        val d5Pct: Double?,
        val d20Pct: Double?,
        /** 最新一日的份额增减（亿份，正=净申购） */
        val d1NetYi: Double?,
        val d5NetYi: Double?,
        val liveDays: Int,
    )

    data class Signal(
        val ts: String? = null,
        val source: String? = null,
        val etfs: List<EtfRow> = emptyList(),
        /** 篮子内最长的 live 序列天数（决定信号是否成熟） */
        val liveDays: Int = 0,
        /** 云端 smart_money.position_pct（0~100）；未满 20 天时为 null */
        val positionPct: Double? = null,
        val positionNote: String? = null,
        /** 篮子当日净申购合计（亿份） */
        val d1NetYi: Double? = null,
        val d5NetYi: Double? = null,
        /** 参与百分位的 ETF 只数与窗口天数（云端口径） */
        val etfsUsed: Int = 0,
        val windowDays: Int = 0,
        val minHistoryDays: Int = NEED_DAYS,
    ) {
        val ready: Boolean get() = liveDays >= minHistoryDays && positionPct != null
        val progressText: String get() = "$liveDays/$minHistoryDays 天历史"

        /**
         * 仓位调档：只有信号成熟才动仓位。
         * +>=70 分位加一档（+10pp，上限 80%）；<=30 分位减一档（-10pp，下限 0）。
         * 返回 null 表示不调整（并给出原因，UI 直接展示）。
         */
        fun capAdjust(baseCap: Double?): Pair<Double?, String> {
            if (!ready) return null to "份额历史 $progressText，不足不出信号，本次不参与仓位调整"
            val p = positionPct ?: return null to "云端无仓位百分位，本次不参与仓位调整"
            val base = baseCap ?: return null to "基准仓位缺失，无法叠加 ETF 信号"
            return when {
                p >= 70.0 -> (base + 0.10).coerceAtMost(0.80) to "ETF 份额处 $p 分位（偏加仓）→ 上限上调至 ${pctText((base + 0.10).coerceAtMost(0.80))}"
                p <= 30.0 -> (base - 0.10).coerceAtLeast(0.0) to "ETF 份额处 $p 分位（偏减仓）→ 上限下调至 ${pctText((base - 0.10).coerceAtLeast(0.0))}"
                else -> base to "ETF 份额处 $p 分位（中性）→ 仓位上限不变"
            }
        }

        private fun pctText(v: Double): String = String.format("%.0f%%", v * 100)
    }

    suspend fun fetch(): Signal? = withContext(Dispatchers.IO) {
        val text = runCatching { httpGet(URL_ETF) }.getOrNull() ?: return@withContext null
        runCatching { parse(text) }.getOrNull()
    }

    private fun httpGet(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", "skapp-android/1.0")
            }
            check(conn.responseCode == 200) { "http ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parse(text: String): Signal {
        val root = JSONObject(text)
        val basket = root.optJSONObject("basket") ?: JSONObject()
        val rows = mutableListOf<EtfRow>()
        val codes = basket.keys().asSequence().sorted().toList()
        for (code in codes) {
            val o = basket.optJSONObject(code) ?: continue
            val latest = o.optJSONObject("latest")
            // 真值序列优先：只认交易所来源（sse_etf_vol / szse_fund_size）。
            // 旧 live_series 是成交量/换手率估算，量级接近但口径不同，混算会得出荒谬的日变动。
            val points = mutableListOf<Pair<String, Double>>()
            o.optJSONArray("series")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val src = p.optString("source")
                    if (!src.startsWith("sse_") && !src.startsWith("szse_")) continue
                    val dt = p.optString("date").takeIf { it.isNotBlank() } ?: continue
                    val v = p.optDouble("shares_yi", Double.NaN)
                    if (!v.isNaN()) points.add(dt to v)
                }
            }
            if (points.isEmpty()) {
                o.optJSONArray("live_series")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val dt = p.optString("date").takeIf { it.isNotBlank() } ?: continue
                        val v = p.optDouble("live_shares_yi", Double.NaN)
                        if (!v.isNaN()) points.add(dt to v)
                    }
                }
            }
            points.sortBy { it.first }
            val shares = points.lastOrNull()?.second ?: latest?.optDouble("shares_yi", Double.NaN)?.takeIf { !it.isNaN() }
            rows.add(
                EtfRow(
                    code = code,
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: code,
                    latestDate = latest?.optString("date")?.takeIf { it.isNotBlank() }
                        ?: points.lastOrNull()?.first,
                    sharesYi = shares,
                    d1Pct = numOrNull(latest, "d1_chg_pct"),
                    d5Pct = numOrNull(latest, "d5_chg_pct"),
                    d20Pct = numOrNull(latest, "d20_chg_pct"),
                    d1NetYi = delta(points, 1),
                    d5NetYi = delta(points, 5),
                    liveDays = points.size,
                )
            )
        }
        val sm = root.optJSONObject("smart_money")
        val minDays = root.optInt("min_history_days", NEED_DAYS).takeIf { it > 0 } ?: NEED_DAYS
        val signal = Signal(
            ts = root.optString("ts").takeIf { it.isNotBlank() },
            source = root.optString("source").takeIf { it.isNotBlank() },
            etfs = rows,
            liveDays = rows.maxOfOrNull { it.liveDays } ?: 0,
            positionPct = numOrNull(sm, "position_pct"),
            positionNote = sm?.optString("note")?.takeIf { it.isNotBlank() },
            d1NetYi = sumOrNull(rows.map { it.d1NetYi }),
            d5NetYi = sumOrNull(rows.map { it.d5NetYi }),
            etfsUsed = sm?.optInt("etfs_used") ?: 0,
            windowDays = sm?.optInt("window_days") ?: root.optInt("window_days", 0),
            minHistoryDays = minDays,
        )
        return signal
    }

    /** 最新值减 N 个交易日前（亿份）；样本不足返回 null，不用估算凑数 */
    private fun delta(points: List<Pair<String, Double>>, n: Int): Double? {
        if (points.size <= n) return null
        val last = points[points.size - 1].second
        val prev = points[points.size - 1 - n].second
        return Math.round((last - prev) * 100.0) / 100.0
    }

    private fun sumOrNull(values: List<Double?>): Double? {
        val v = values.filterNotNull()
        return if (v.isEmpty()) null else Math.round(v.sum() * 100.0) / 100.0
    }

    private fun numOrNull(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }
}
