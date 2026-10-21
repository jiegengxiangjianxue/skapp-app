package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 多源行情客户端 —— 站点 scripts/ 里 206 个端点中, app 直接用得动的那些。
 *
 * 之前 app 只接了腾讯 2 个端点, 所以「数据源没调动起来」。这里按用途分组,
 * 每个用途带主源 + 兜底源, 与站点 stock-detail.js / realtime.js 的取数链一致:
 *
 *   实时报价    腾讯 qt.gtimg.cn(GBK)          → 东财 push2 → 新浪 hq.sinajs
 *   日 K(前/后复权)  腾讯 web.ifzq fqkline(hfq/qfq) → 东财 push2his kline(fqt=1/2)
 *   分时/趋势   腾讯 minute/query              → 东财 push2his trends2
 *   资金流(历史)  东财 push2his fflow/daykline  → push2delay(仅当日)
 *   全球指数    东财 push2 行情 + 新浪 global
 *
 * 全部只读公开接口, 无需 key。编码按站点实测: 腾讯 GBK、东财/新浪 UTF-8。
 */
object MarketDataClient {

    private const val TIMEOUT = 15000

    private fun get(url: String, charset: String = "utf-8"): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
                setRequestProperty("Referer", "https://gu.qq.com/")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val cs = if (charset.equals("gbk", true)) Charset.forName("GBK") else Charsets.UTF_8
            BufferedReader(InputStreamReader(conn.inputStream, cs)).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject? =
        get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ── 代码格式 ────────────────────────────────────────────

    /** 腾讯格式: sh600519 / sz000001 / hk00700 */
    fun txCode(code: String): String = SkappApi.prefixed(code)

    /** 东财 secid: 1.600519 / 0.000001 / 116.00700 */
    fun secid(code: String): String {
        val c = code.lowercase()
        if (c.startsWith("hk")) {
            val d = c.filter { it.isDigit() }.padStart(5, '0')
            return "116.$d"
        }
        val n = c.filter { it.isDigit() }.padStart(6, '0')
        return when (n.take(2)) {
            "60", "68", "90", "11", "13" -> "1.$n"
            else -> "0.$n"
        }
    }

    // ── 实时报价（腾讯主源，东财/新浪兜底）────────────────

    suspend fun quote(code: String): Quote? = withContext(Dispatchers.IO) {
        val tx = txCode(code)
        // 1) 腾讯（含名称）
        get("https://qt.gtimg.cn/q=$tx", "gbk")?.let { txt ->
            val m = Regex("v_$tx=\"([^\"]*)\"").find(txt) ?: return@let null
            val f = m.groupValues[1].split("~")
            if (f.size < 40) null
            else Quote(
                code = code,
                name = f.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "",
                price = f.getOrNull(3)?.toDoubleOrNull(),
                pctChg = f.getOrNull(32)?.toDoubleOrNull(),
                ts = f.getOrNull(30)?.takeIf { it.isNotBlank() },
            )
        }?.let { return@withContext it }
        // 2) 东财
        getJson("https://push2.eastmoney.com/api/qt/stock/get?secid=${secid(code)}&fields=f43,f44,f45,f46,f57,f58,f169,f170,f86")
            ?.takeIf { it.optLong("rc") != null }
            ?.let { d ->
                val data = d.optJSONObject("data") ?: return@let null
                val price = data.opt("f43")?.toString()?.toDoubleOrNull()?.let { it / 100 }
                val pct = data.opt("f170")?.toString()?.toDoubleOrNull()?.let { it / 100 }
                Quote(code, data.optString("f58").takeIf { it.isNotEmpty() } ?: "", price, pct, null)
            }?.let { return@withContext it }
        // 3) 新浪
        get("https://hq.sinajs.cn/list=$tx", "gbk")?.let { txt ->
            val m = Regex("hq_str_$tx=\"([^\"]*)\"").find(txt) ?: return@let null
            val f = m.groupValues[1].split(",")
            if (f.size < 8) null
            else Quote(
                code = code,
                name = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "",
                price = f.getOrNull(3)?.toDoubleOrNull(),
                pctChg = f.getOrNull(3)?.toDoubleOrNull()?.let { p ->
                    val prev = f.getOrNull(2)?.toDoubleOrNull()
                    if (prev != null && prev > 0) (p - prev) / prev * 100 else null
                },
                ts = null,
            )
        }
    }

    // ── 日 K 线（前复权 qfq / 后复权 hfq）──────────────────

    /**
     * @param adjust "qfq" 前复权 / "hfq" 后复权 / "" 不复权
     * @return 按日期升序
     */
    suspend fun dayKline(
        code: String,
        count: Int = 250,
        adjust: String = "qfq",
    ): List<KBar> = withContext(Dispatchers.IO) {
        val tx = txCode(code)
        // 1) 腾讯 fqkline: param=<code>,day,,,<count>,qfq|hfq
        val tencentAdj = if (adjust == "hfq") "hfq" else if (adjust == "qfq") "qfq" else ""
        val tUrl = if (tencentAdj.isEmpty()) {
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tx,day,,,$count,"
        } else {
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tx,day,,,$count,$tencentAdj"
        }
        getJson(tUrl)?.let { root ->
            val node = root.optJSONObject("data")?.optJSONObject(tx)
            if (node != null) {
                val key = when (tencentAdj) {
                    "qfq" -> "qfqday"
                    "hfq" -> "hfqday"
                    else -> "day"
                }
                val arr = node.optJSONArray(key)
                    ?: node.optJSONArray(if (tencentAdj == "qfq") "day" else "qfqday")
                if (arr != null && arr.length() > 0) {
                    return@withContext parseBars(arr)
                }
            }
            null
        }?.let { return@withContext it }
        // 2) 东财 push2his: fqt=1 前复权 / 2 后复权 / 0 不复权
        val fqt = if (adjust == "hfq") 2 else if (adjust == "qfq") 1 else 0
        getJson(
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=${secid(code)}" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&klt=101&fqt=$fqt&end=20500101&lmt=$count"
        )?.let { d ->
            val klines = d.optJSONObject("data")?.optJSONArray("klines")
            if (klines != null && klines.length() > 0) {
                // "date,open,close,high,low,volume,amount,amplitude,pct,chg,turnover"
                val out = ArrayList<KBar>()
                for (i in 0 until klines.length()) {
                    val p = klines.optString(i).split(",")
                    if (p.size < 6) continue
                    out += KBar(
                        date = p[0],
                        open = p[1].toDoubleOrNull() ?: continue,
                        close = p[2].toDoubleOrNull() ?: continue,
                        high = p[3].toDoubleOrNull() ?: continue,
                        low = p[4].toDoubleOrNull() ?: continue,
                        vol = p[5].toDoubleOrNull() ?: 0.0,
                    )
                }
                out
            } else null
        } ?: emptyList()
    }

    private fun parseBars(arr: JSONArray): List<KBar> {
        val out = ArrayList<KBar>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            if (r.length() < 6) continue
            val date = r.optString(0).takeIf { it.isNotEmpty() } ?: continue
            out += KBar(
                date = date,
                open = r.optDouble(1),
                close = r.optDouble(2),
                high = r.optDouble(3),
                low = r.optDouble(4),
                vol = r.optDouble(5),
            )
        }
        return out
    }

    // ── 历史资金流（东财 push2his 主源，push2delay 兜底）────

    /**
     * 每日主力/超大单/大单/中单/小单净额。
     * @return [{date, main, super, large, medium, small}] 升序
     */
    suspend fun fundFlowHistory(code: String, days: Int = 60): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            val sid = secid(code)
            val parse = { txt: String? ->
                txt?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optJSONObject("data")?.optJSONArray("klines")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            // "date,主力净额,小单,中单,大单,超大单,主力占比,..."
                            val p = arr.optString(i).split(",")
                            if (p.size < 6) null
                            else mapOf<String, Any?>(
                                "date" to p[0],
                                "main" to p[1].toDoubleOrNull(),
                                "small" to p[2].toDoubleOrNull(),
                                "medium" to p[3].toDoubleOrNull(),
                                "large" to p[4].toDoubleOrNull(),
                                "super" to p[5].toDoubleOrNull(),
                            )
                        }
                    }
            }
            // push2his 有完整历史
            parse(get("https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get" +
                    "?secid=$sid&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                    "&lmt=$days"))
                ?: parse(get("https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get" +
                    "?secid=$sid&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                    "&lmt=$days"))
                ?: emptyList()
        }

    // ── 分时走势（腾讯 minute 主源，东财 trends2 兜底）──────

    suspend fun minuteTrend(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val tx = txCode(code)
        getJson("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$tx")?.let { d ->
            val arr = d.optJSONObject("data")?.optJSONObject(tx)?.optJSONObject("data")?.optJSONArray("data")
            if (arr != null && arr.length() > 0) {
                return@withContext (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optString(i).split(" ")
                    if (p.size < 3) null
                    else mapOf<String, Any?>(
                        "time" to p[0],
                        "price" to p[1].toDoubleOrNull(),
                        "vol" to p[2].toDoubleOrNull(),
                    )
                }
            }
            null
        }?.let { return@withContext it }
        // 东财 trends2: "时间,开盘,收盘,最高,最低,成交量,成交额,最新价"
        getJson("https://push2his.eastmoney.com/api/qt/stock/trends2/get?secid=${secid(code)}" +
                "&fields1=f1,f2,f3,f4,f5,f6,f7,f8&fields2=f51,f53,f56,f58&iscr=0&ndays=1")
            ?.optJSONObject("data")?.optJSONArray("trends")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optString(i).split(",")
                    if (p.size < 5) null
                    else mapOf<String, Any?>(
                        "time" to p[0],
                        "price" to p[4].toDoubleOrNull(),
                        "vol" to p[5].toDoubleOrNull(),
                    )
                }
            } ?: emptyList()
    }

    // ── 全球股市指数（东财 + 新浪）─────────────────────────

    /** 主要海外/全球指数：纳指、道指、恒指、日经、富时、德国DAX 等 */
    suspend fun globalIndices(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        // secid: 100.DJIA(道指) 100.NDX(纳指) 100.SPX 100.HSI(恒指) 100.N225(日经)
        //        100.FTSE 100.GDAXI 100.TWII 100.SENSEX
        val list = listOf(
            "100.DJIA", "100.NDX", "100.SPX", "100.HSI", "100.N225",
            "100.FTSE", "100.GDAXI", "100.TWII", "100.SENSEX", "100.KOSPI",
        )
        val fields = "f12,f13,f14,f2,f3,f4,f5,f6"
        val out = ArrayList<Map<String, Any?>>()
        // 东财批量
        getJson("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=${list.joinToString(",")}" +
                "&fields=$fields&fltt=2")?.let { d ->
            val arr = d.optJSONObject("data")?.optJSONArray("diff")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out += mapOf<String, Any?>(
                        "code" to (o.optString("f12").takeIf { it.isNotEmpty() } ?: o.optString("f14")),
                        "name" to (o.optString("f14").takeIf { it.isNotEmpty() } ?: "未知"),
                        "price" to o.opt("f2")?.toString()?.toDoubleOrNull(),
                        "pctChg" to o.opt("f3")?.toString()?.toDoubleOrNull(),
                        "chg" to o.opt("f4")?.toString()?.toDoubleOrNull(),
                        "source" to "eastmoney",
                    )
                }
            }
        }
        out
    }

    /** A 股指数（上证/深成/创业板/科创50/北证50） */
    suspend fun aShareIndices(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val list = listOf("1.000001", "0.399001", "0.399006", "1.000688", "0.899050", "1.000300")
        val fields = "f12,f13,f14,f2,f3,f4"
        getJson("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=${list.joinToString(",")}" +
                "&fields=$fields&fltt=2")?.optJSONObject("data")?.optJSONArray("diff")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    mapOf<String, Any?>(
                        "code" to (o.optString("f12").takeIf { it.isNotEmpty() } ?: o.optString("f14")),
                        "name" to (o.optString("f14").takeIf { it.isNotEmpty() } ?: "未知"),
                        "price" to o.opt("f2")?.toString()?.toDoubleOrNull(),
                        "pctChg" to o.opt("f3")?.toString()?.toDoubleOrNull(),
                        "chg" to o.opt("f4")?.toString()?.toDoubleOrNull(),
                        "source" to "eastmoney",
                    )
                }
            } ?: emptyList()
    }

    /** 所属板块（东财行业/概念）—— 个股详情页用 */
    suspend fun boardsOf(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        getJson("https://push2.eastmoney.com/api/qt/stock/get?secid=${secid(code)}" +
                "&fields=f57,f58,f127,f128,f129,f130,f131,f132,f133,f134,f135,f136")
            ?.optJSONObject("data")
            ?.let { d ->
                val names = listOf("f127", "f128", "f129", "f130")
                names.mapNotNull { k ->
                    val v = d.optString(k).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    mapOf<String, Any?>("name" to v, "type" to "industry/concept")
                }
            } ?: emptyList()
    }

    // ── 本地技术指标计算（不依赖任何远端）──────────────────

    /** MA(n) 收盘均值序列 */
    fun ma(bars: List<KBar>, n: Int): List<Double?> {
        if (bars.size < n) return List(bars.size) { null }
        val out = ArrayList<Double?>(bars.size)
        var sum = 0.0
        for (i in bars.indices) {
            sum += bars[i].close
            if (i >= n) sum -= bars[i - n].close
            out += if (i >= n - 1) sum / n else null
        }
        return out
    }

    /** MACD(12,26,9) → (dif, dea, hist) */
    fun macd(bars: List<KBar>): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val ema12 = ema(bars.map { it.close }, 12)
        val ema26 = ema(bars.map { it.close }, 26)
        val dif = bars.indices.map { i ->
            val a = ema12.getOrNull(i); val b = ema26.getOrNull(i)
            if (a != null && b != null) a - b else null
        }
        val valid = dif.mapNotNull { it }
        val deaAll = ema(valid, 9)
        // dea 从 dif 首次非 null 开始对齐
        val startIdx = dif.indexOfFirst { it != null }
        val dea = ArrayList<Double?>(bars.size)
        if (startIdx < 0) {
            repeat(bars.size) { dea += null }
        } else {
            repeat(startIdx) { dea += null }
            deaAll.forEach { dea += it }
            while (dea.size < bars.size) dea += null
        }
        val hist = bars.indices.map { i ->
            val d = dif.getOrNull(i); val e = dea.getOrNull(i)
            if (d != null && e != null) 2 * (d - e) else null
        }
        return Triple(dif, dea, hist)
    }

    private fun ema(values: List<Double>, n: Int): List<Double?> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double?>(values.size)
        var prev: Double? = null
        values.forEachIndexed { i, v ->
            prev = if (prev == null) v else v * k + prev!! * (1 - k)
            out += if (i >= n - 1) prev else null
        }
        return out
    }

    /** RSI(n) Wilder 平滑 */
    fun rsi(bars: List<KBar>, n: Int = 14): Double? {
        if (bars.size <= n) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) {
            val d = bars[i].close - bars[i - 1].close
            if (d >= 0) gain += d else loss -= d
        }
        var ag = gain / n; var al = loss / n
        for (i in n + 1 until bars.size) {
            val d = bars[i].close - bars[i - 1].close
            val g = if (d >= 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            ag = (ag * (n - 1) + g) / n
            al = (al * (n - 1) + l) / n
        }
        return if (al == 0.0) 100.0 else 100.0 - 100.0 / (1 + ag / al)
    }

    /** KDJ(9,3,3) */
    fun kdj(bars: List<KBar>, n: Int = 9): Triple<Double?, Double?, Double?> {
        if (bars.size < n) return Triple(null, null, null)
        val rsvList = ArrayList<Double>()
        for (i in n - 1 until bars.size) {
            val win = bars.subList(i - n + 1, i + 1)
            val hh = win.maxOf { it.high }
            val ll = win.minOf { it.low }
            rsvList += if (hh == ll) 50.0 else (bars[i].close - ll) / (hh - ll) * 100
        }
        var k = 50.0; var d = 50.0
        rsvList.forEach { rsv ->
            k = (2 * k + rsv) / 3
            d = (2 * d + k) / 3
        }
        return Triple(k, d, 3 * k - 2 * d)
    }

    /** 量比 = 现量 / 过去 N 日均量 */
    fun volumeRatio(bars: List<KBar>, n: Int = 5): Double? {
        if (bars.size < n + 1) return null
        val cur = bars.last().vol
        val avg = bars.dropLast(1).takeLast(n).map { it.vol }.average()
        return if (avg <= 0) null else cur / avg
    }

    /** 布林带(20, 2) */
    fun boll(bars: List<KBar>, n: Int = 20, k: Double = 2.0): Triple<Double?, Double?, Double?> {
        if (bars.size < n) return Triple(null, null, null)
        val win = bars.takeLast(n)
        val mid = win.map { it.close }.average()
        val sd = Math.sqrt(win.map { Math.pow(it.close - mid, 2.0) }.sum() / n)
        return Triple(mid - k * sd, mid, mid + k * sd)
    }
}

// ════════════════════════════════════════════════════════
//  周 K / 月 K 聚合（由日 K 本地聚合，无需额外请求）
// ════════════════════════════════════════════════════════

/** 周期类型 */
enum class KPeriod(val label: String) {
    DAY("日K"), WEEK("周K"), MONTH("月K")
}

/**
 * 把日 K 聚合为周 K / 月 K。
 * 周：自然周（周一为起始，按 ISO 周分组）
 * 月：自然月
 */
fun aggregateKline(daily: List<KBar>, period: KPeriod): List<KBar> {
    if (daily.isEmpty() || period == KPeriod.DAY) return daily
    val out = ArrayList<KBar>()
    var curKey = ""
    var open = 0.0; var close = 0.0; var high = 0.0; var low = 0.0; var vol = 0.0
    var first = true
    daily.forEach { b ->
        val key = when (period) {
            KPeriod.WEEK -> weekKey(b.date)
            KPeriod.MONTH -> b.date.take(7)
            else -> b.date
        }
        if (key != curKey) {
            if (!first) out += KBar(curKey, open, close, high, low, vol)
            curKey = key; open = b.open; close = b.close; high = b.high; low = b.low; vol = b.vol
            first = false
        } else {
            close = b.close
            high = maxOf(high, b.high)
            low = minOf(low, b.low)
            vol += b.vol
        }
    }
    if (!first) out += KBar(curKey, open, close, high, low, vol)
    return out
}

/** yyyy-MM-dd → ISO 周标识 yyyyWww */
private fun weekKey(date: String): String {
    return try {
        val parts = date.split("-")
        if (parts.size != 3) return date.take(7)
        val c = java.util.Calendar.getInstance().apply {
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
        val y = c.get(java.util.Calendar.YEAR)
        val w = c.get(java.util.Calendar.WEEK_OF_YEAR)
        String.format("%dW%02d", y, w)
    } catch (e: Exception) {
        date.take(7)
    }
}
