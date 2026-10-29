package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    /** 统一走 [Http]（gzip/UA/Referer/超时收敛到一份实现）。 */
    private fun get(url: String, charset: String = "utf-8", referer: String = "https://gu.qq.com/"): String? =
        Http.getText(
            url = url,
            charset = Http.charset(charset),
            timeoutMs = TIMEOUT,
            referer = referer,
            accept = "*/*",
        )

    private fun getJson(url: String): JSONObject? =
        get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 东财接口：HTTPS 在部分网络（含本机容器、部分运营商）会被连接重置，
     * 实测同一路径 HTTP 明文可正常返回。因此 HTTPS 失败后立刻回退 HTTP，
     * 而不是把「拿不到」报成「没有数据」。
     */
    /** 同 getJsonEm，返回原始文本（东财资金流接口用）。 */
    private fun getEm(url: String): String? {
        get(url)?.let { return it }
        if (url.startsWith("https://")) return get("http://" + url.removePrefix("https://"))
        return null
    }

    private fun getJsonEm(url: String): JSONObject? {
        getJson(url)?.let { return it }
        if (url.startsWith("https://")) return getJson("http://" + url.removePrefix("https://"))
        return null
    }

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
                bid1 = f.getOrNull(9)?.toDoubleOrNull(),
                bid1Vol = f.getOrNull(10)?.toDoubleOrNull(),
                ask1 = f.getOrNull(19)?.toDoubleOrNull(),
                ask1Vol = f.getOrNull(20)?.toDoubleOrNull(),
            )
        }?.let { return@withContext it }
        // 2) 东财
        getJsonEm("https://push2.eastmoney.com/api/qt/stock/get?secid=${secid(code)}&fields=f43,f44,f45,f46,f57,f58,f169,f170,f86")
            // optLong 有默认值、永不返回 null，「!= null」等于恒真——任何响应都会被当成成功，
            // 包括东财的错误体（rc 非 0 时 data 为 null）。按 rc == 0 判成功。
            ?.takeIf { it.optInt("rc", -1) == 0 }
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

    /**
     * 东财 SSE 实时推送（wap2 页面实际使用的接口）。
     * 来源：F12 观察 wap.eastmoney.com 的 XHR，接口为 /api/qt/stock/sse?forcect=1。
     * 字段口径未在沙箱实测（出口被网关拦），因此全部做容错：解析失败就跳过，
     * 由上层轮询兜底，不伪造数据。
     */
    fun sseQuote(code: String): Flow<Quote> = flow {
        val url = "https://push2.eastmoney.com/api/qt/stock/sse?secid=${secid(code)}" +
            "&forcect=1&fltt=2&invt=2" +
            "&fields=f43,f44,f45,f46,f47,f48,f57,f58,f60,f19,f20,f31,f32,f169,f170" +
            "&ut=f057cbcbce2a86e2866ab8877db1d059"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
                setRequestProperty("Referer", "https://wap.eastmoney.com/")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@flow
            conn.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val payload = line.trim().removePrefix("data:").trim()
                    if (payload.isEmpty() || !payload.startsWith("{")) return@forEach
                    parseSseQuote(code, payload)?.let { emit(it) }
                }
            }
        } catch (e: Exception) {
            // 接口不可达/被拦：静默退出，交给轮询兜底
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSseQuote(code: String, payload: String): Quote? {
        val data = runCatching { JSONObject(payload).optJSONObject("data") }.getOrNull() ?: return null
        fun n(key: String): Double? = when (val v = data.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        val price = n("f43")?.takeIf { it > 0 } ?: return null
        var pct = n("f170")
        // fltt=2 本应返回小数；若服务端忽略该参数会返回 ×100，这里做量级修正
        if (pct != null && kotlin.math.abs(pct) > 50) pct /= 100.0
        return Quote(
            code = code,
            name = data.optString("f58").takeIf { it.isNotBlank() } ?: "",
            price = price,
            pctChg = pct,
            ts = java.time.LocalTime.now().withNano(0).toString(),
            bid1 = n("f19")?.takeIf { it > 0 },
            bid1Vol = n("f20"),
            ask1 = n("f31")?.takeIf { it > 0 },
            ask1Vol = n("f32"),
        )
    }

    // ── 日 K 线（前复权 qfq / 后复权 hfq）──────────────────

    /** 最近一次日 K 拉取实际生效的来源与复权口径（供 UI 标注用，不做持久化）。 */
    @Volatile
    var lastKlineSource: String? = null
        private set

    /**
     * 日 K 线。复权口径严格按请求返回，绝不拿别的口径顶替。
     *
     * 源顺序（实测结论 2026-10-26）：
     *   hfq 后复权 → 东财 push2his fqt=2 为主源（实测返回真后复权价，如 600519 收 8389）；
     *                腾讯 fqkline hfq 只在东财失败时兜底（腾讯侧常被 WAF 拦且 hfq 数组常缺失）。
     *   qfq 前复权 / 不复权 → 腾讯 fqkline 为主源，东财 fqt=1/0 兜底。
     *
     * @param adjust "qfq" 前复权 / "hfq" 后复权 / "none" 或 "" 不复权
     * @return 按日期升序；两个源都失败时返回空表（UI 显示暂无，不伪造）
     */
    suspend fun dayKline(
        code: String,
        count: Int = 250,
        adjust: String = "qfq",
    ): List<KBar> = withContext(Dispatchers.IO) {
        val tx = txCode(code)
        // 后复权：东财 fqt=2 为主 → 同花顺 02 档（非东财系交叉验证）→ 腾讯 hfq。
        // 三家复权基准日不同，绝对价不可比，但任一家单源内部一致即可用于画图；UI 会标明实际用的哪家。
        val order = if (adjust == "hfq") listOf("eastmoney", "ths", "tencent")
        else listOf("tencent", "eastmoney", "ths")
        for (src in order) {
            val bars = when (src) {
                "tencent" -> tencentKline(tx, count, adjust)
                "ths" -> thsKline(code, count, adjust)
                else -> eastmoneyKline(code, count, adjust)
            }
            if (bars.size >= 2) {
                lastKlineSource = when (src) {
                    "tencent" -> "腾讯 fqkline " + when (adjust) {
                        "hfq" -> "hfq"
                        "qfq" -> "qfq"
                        else -> "不复权"
                    }
                    "ths" -> "同花顺 v6/line " + when (adjust) {
                        "hfq" -> "02（后复权）"
                        "qfq" -> "01（前复权）"
                        else -> "00（不复权）"
                    }
                    else -> "东财 push2his fqt=" + when (adjust) {
                        "hfq" -> "2（后复权）"
                        "qfq" -> "1（前复权）"
                        else -> "0（不复权）"
                    }
                }
                return@withContext bars
            }
        }
        lastKlineSource = null
        emptyList()
    }

    /** 腾讯日 K。严格按请求口径取数组：hfq 只认 hfqday，不拿 qfqday/day 顶替。 */
    private fun tencentKline(tx: String, count: Int, adjust: String): List<KBar> {
        val adj = when (adjust) {
            "hfq" -> "hfq"
            "qfq" -> "qfq"
            else -> ""
        }
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tx,day,,,$count,$adj"
        val node = getJson(url)?.optJSONObject("data")?.optJSONObject(tx) ?: return emptyList()
        val arr = node.optJSONArray(
            when (adj) {
                "qfq" -> "qfqday"
                "hfq" -> "hfqday"
                else -> "day"
            }
        ) ?: return emptyList()
        return parseBars(arr)
    }

    /**
     * 同花顺日 K —— 用来补「非东财系」的后复权交叉验证源。
     *
     * 实测（2026-10-26）：`d.10jqka.com.cn/v6/line/hs_<code>/<档>/last.js`
     *   档位 00=不复权、01=前复权、02=后复权（当日 600519 后复权收 9356.19，与东财量级不同：
     *   两家复权基准日不同，只能比「日收益率」，不能比绝对价）。
     *   必须带 Referer，否则连接被重置。
     * data 形如 "日期,开,高,低,收,量,额,换手,,,0;"，条目用 ; 分隔。
     */
    private fun thsKline(code: String, count: Int, adjust: String): List<KBar> {
        val digits = code.filter { it.isDigit() }.takeLast(6)
        if (digits.length != 6) return emptyList()
        val band = when (adjust) {
            "hfq" -> "02"
            "qfq" -> "01"
            else -> "00"
        }
        val txt = get(
            "https://d.10jqka.com.cn/v6/line/hs_$digits/$band/last.js",
            "utf-8",
            "https://stockpage.10jqka.com.cn/",
        ) ?: return emptyList()
        val start = txt.indexOf("\"data\":\"")
        if (start < 0) return emptyList()
        val from = start + "\"data\":\"".length
        val end = txt.indexOf('"', from)
        if (end <= from) return emptyList()
        val rows = txt.substring(from, end).split(";")
        val out = ArrayList<KBar>()
        for (row in rows) {
            val p = row.split(",")
            if (p.size < 6) continue
            val day = p[0].trim()
            if (day.length != 8 || !day.all { it.isDigit() }) continue
            val open = p[1].toDoubleOrNull() ?: continue
            val high = p[2].toDoubleOrNull() ?: continue
            val low = p[3].toDoubleOrNull() ?: continue
            val close = p[4].toDoubleOrNull() ?: continue
            out += KBar(
                date = "${day.take(4)}-${day.substring(4, 6)}-${day.takeLast(2)}",
                open = open, close = close, high = high, low = low,
                vol = p[5].toDoubleOrNull() ?: 0.0,
            )
        }
        return out.takeLast(count)
    }

    /** 东财日 K：fqt=1 前复权 / 2 后复权 / 0 不复权。 */
    private fun eastmoneyKline(code: String, count: Int, adjust: String): List<KBar> {
        val fqt = if (adjust == "hfq") 2 else if (adjust == "qfq") 1 else 0
        val d = getJsonEm(
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=${secid(code)}" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&klt=101&fqt=$fqt&end=20500101&lmt=$count"
        ) ?: return emptyList()
        val klines = d.optJSONObject("data")?.optJSONArray("klines") ?: return emptyList()
        // "date,open,close,high,low,volume,amount,amplitude,pct,chg,turnover"
        val out = ArrayList<KBar>()
        for (i in 0 until klines.length()) {
            val p = klines.optString(i).split(",")
            if (p.size < 6) continue
            val open = p[1].toDoubleOrNull() ?: continue
            val close = p[2].toDoubleOrNull() ?: continue
            out += KBar(
                date = p[0],
                open = open,
                close = close,
                high = p[3].toDoubleOrNull() ?: close,
                low = p[4].toDoubleOrNull() ?: close,
                vol = p[5].toDoubleOrNull() ?: 0.0,
            )
        }
        return out
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
            parse(getEm("https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get" +
                    "?secid=$sid&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                    "&lmt=$days"))
                ?: parse(getEm("https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get" +
                    "?secid=$sid&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                    "&lmt=$days"))
                ?: emptyList()
        }

    /** 当日分钟级资金流（东财 fflow klt=1）；失败返回空，不编造。 */
    suspend fun fundFlowIntraday(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val sid = secid(code)
        fun parse(txt: String?): List<Map<String, Any?>> {
            if (txt == null) return emptyList()
            val arr = runCatching {
                JSONObject(txt).optJSONObject("data")?.optJSONArray("klines")
            }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val p = arr.optString(i).split(",")
                if (p.size < 6) null else mapOf<String, Any?>(
                    "time" to p[0],
                    "main_net" to p[1].toDoubleOrNull(),
                    "small_net" to p[2].toDoubleOrNull(),
                    "mid_net" to p[3].toDoubleOrNull(),
                    "large_net" to p[4].toDoubleOrNull(),
                    "super_net" to p[5].toDoubleOrNull(),
                )
            }
        }
        val q = "fields1=f1,f2,f3,f7&fields2=f51,f52,f53,f54,f55,f56&klt=1&lmt=0&ut=f057cbcbce2a86e2866ab8877db1d059"
        parse(getEm("https://push2.eastmoney.com/api/qt/stock/fflow/kline/get?secid=$sid&$q"))
            ?: parse(getEm("https://push2delay.eastmoney.com/api/qt/stock/fflow/kline/get?secid=$sid&$q"))
            ?: emptyList()
    }

    // ── 分时走势（腾讯 minute 主源，东财 trends2 兜底）──────

    /**
     * 分时数据（含绘分时图必需的元数据）。
     *
     * 腾讯 minute/query 每个 tick 形如 `"0930 1325.40 905 119948700.00"`：
     *   时间(时分) / 当前价 / **累计成交量(手)** / **累计成交额(元)**；
     * 昨日收盘在 `data.<code>.qt.<code>[4]`（字段 3 是现价、5 是今开）。
     * 盘后仍返回当日全量分时（实测 20:00 仍拿到 267 个 tick），所以非交易时段也应当请求。
     */
    data class MinuteTrendData(
        val date: String?,
        val preClose: Double?,
        val points: List<Map<String, Any?>>,
        val source: String,
    )

    suspend fun minuteTrendRich(code: String): MinuteTrendData = withContext(Dispatchers.IO) {
        val tx = txCode(code)
        getJson("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$tx")?.let { d ->
            val node = d.optJSONObject("data")?.optJSONObject(tx)
            val inner = node?.optJSONObject("data")
            val arr = inner?.optJSONArray("data")
            if (arr != null && arr.length() > 1) {
                val rawDate = inner.optString("date")
                val date = if (rawDate.length == 8)
                    "${rawDate.take(4)}-${rawDate.substring(4, 6)}-${rawDate.takeLast(2)}" else null
                // qt 段：索引 3=现价 4=昨收 5=今开
                val qt = node.optJSONObject("qt")?.optJSONArray(tx)
                val preClose = qt?.optString(4)?.toDoubleOrNull()?.takeIf { it > 0 }
                var cumAmt = 0.0
                val pts = (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optString(i).split(" ")
                    if (p.size < 3) return@mapNotNull null
                    val price = p[1].toDoubleOrNull() ?: return@mapNotNull null
                    val cumVolLots = p[2].toDoubleOrNull() ?: 0.0
                    p.getOrNull(3)?.toDoubleOrNull()?.let { if (it > 0) cumAmt = it }
                    val cumShares = cumVolLots * 100.0
                    val avg = if (cumShares > 0 && cumAmt > 0) cumAmt / cumShares else price
                    mapOf<String, Any?>(
                        "time" to p[0],
                        "price" to price,
                        "vol" to cumVolLots,
                        "amount" to cumAmt,
                        "avg" to avg,
                    )
                }
                if (pts.size > 1) {
                    return@withContext MinuteTrendData(date, preClose, pts, "腾讯 minute/query")
                }
            }
        }
        // 东财 trends2 兜底：f51 时间, f53 收, f56 累计量, f57 累计额, f58 均价
        getJsonEm(
            "https://push2his.eastmoney.com/api/qt/stock/trends2/get?secid=${secid(code)}" +
                "&fields1=f1,f2,f3,f4,f5,f6,f7,f8&fields2=f51,f52,f53,f54,f55,f56,f57,f58&iscr=0&ndays=1"
        )?.optJSONObject("data")?.let { dd ->
            val arr = dd.optJSONArray("trends") ?: return@let
            val preClose = dd.opt("preClose")?.toString()?.toDoubleOrNull()
                ?: dd.opt("prePrice")?.toString()?.toDoubleOrNull()
            var day: String? = null
            val pts = (0 until arr.length()).mapNotNull { i ->
                val p = arr.optString(i).split(",")
                if (p.size < 6) return@mapNotNull null
                val price = p[2].toDoubleOrNull() ?: return@mapNotNull null
                val stamp = p[0]                       // "2026-10-26 09:30"
                if (day == null) day = stamp.take(10).takeIf { it.length == 10 }
                mapOf<String, Any?>(
                    "time" to stamp.takeLast(5).replace(":", ""),
                    "price" to price,
                    "vol" to (p[5].toDoubleOrNull() ?: 0.0) / 100.0,
                    "amount" to (p.getOrNull(6)?.toDoubleOrNull() ?: 0.0),
                    "avg" to (p.getOrNull(7)?.toDoubleOrNull() ?: price),
                )
            }
            if (pts.size > 1) {
                return@withContext MinuteTrendData(day, preClose, pts, "东财 trends2")
            }
        }
        MinuteTrendData(null, null, emptyList(), "none")
    }

    /** 兼容旧调用：只要 tick 列表。 */
    suspend fun minuteTrend(code: String): List<Map<String, Any?>> = minuteTrendRich(code).points

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
        getJsonEm("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=${list.joinToString(",")}" +
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
        getJsonEm("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=${list.joinToString(",")}" +
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

    /** 所属板块（东财行业/概念 + db-market 反向索引兑底）—— 个股详情页用 */
    suspend fun boardsOf(code: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val em = getJsonEm("https://push2.eastmoney.com/api/qt/stock/get?secid=${secid(code)}" +
                "&fields=f57,f58,f127,f128,f129,f130,f131,f132,f133,f134,f135,f136")
            ?.optJSONObject("data")
            ?.let { d ->
                val names = listOf("f127", "f128", "f129", "f130")
                names.mapNotNull { k ->
                    val v = d.optString(k).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    mapOf<String, Any?>("name" to v, "type" to "industry/concept")
                }
            } ?: emptyList()
        if (em.isNotEmpty()) return@withContext em
        // 兑底：db-market 的 stock_to_board.json（board-constituents 管线产出， 2026-10-24 管线复活后每日更新）
        val bare = code.removePrefix("sh").removePrefix("sz").removePrefix("hk")
        getJson("https://raw.githubusercontent.com/jiegengxiangjianxue1/db-market/main/sector/stock_to_board.json")
            ?.optJSONObject("stocks")?.optJSONObject(bare)
            ?.let { s ->
                val out = ArrayList<Map<String, Any?>>()
                for ((kind, key) in listOf("industry" to "行业", "concept" to "概念")) {
                    s.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }
                                ?.let { out += mapOf<String, Any?>("name" to it, "type" to kind) }
                                ?: arr.optString(i).takeIf { it.isNotEmpty() }
                                    ?.let { out += mapOf<String, Any?>("name" to it, "type" to kind) }
                        }
                    }
                }
                out
            } ?: emptyList()
    }

    // ── 本地技术指标计算（不依赖任何远端）──────────────────

    /** 财务摘要（东财 quote 财务字段；holo 未覆盖个股的兜底，与 holo 字段名对齐） */
    suspend fun financeQuote(code: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        getJsonEm("https://push2.eastmoney.com/api/qt/stock/get?secid=${secid(code)}" +
                "&fields=f57,f58,f84,f85,f116,f117,f162,f167,f55,f60")
            ?.optJSONObject("data")?.let { d ->
                buildMap<String, Any?> {
                    d.opt("f116")?.let { put("总市值", it) }
                    d.opt("f117")?.let { put("流通市值", it) }
                    d.opt("f84")?.let { put("总股本", it) }
                    d.opt("f85")?.let { put("流通股本", it) }
                    d.opt("f162")?.let { put("PE_TTM", it) }
                    d.opt("f167")?.let { put("PB", it) }
                    d.opt("f55")?.let { put("每股收益", it) }
                    d.opt("f60")?.let { put("每股净资产", it) }
                }
            } ?: emptyMap()
    }

    /** 本地多源消息：东财 7x24 + 爱股票快讯 + 金融界要闻。各源失败隔离。 */
    suspend fun localNews(limit: Int = 30): LocalNewsResult = coroutineScope {
        val fetchedAt = java.time.OffsetDateTime.now().toString()
        data class SourceResult(val name: String, val rows: List<Map<String, Any?>>?, val error: String?)
        val results = listOf(
            async(Dispatchers.IO) { runCatching { news724(limit) }.fold({ SourceResult("eastmoney_724", it, null) }, { SourceResult("eastmoney_724", null, it.message) }) },
            async(Dispatchers.IO) { runCatching { aigupiaoExpress(limit) }.fold({ SourceResult("aigupiao_express", it, null) }, { SourceResult("aigupiao_express", null, it.message) }) },
            async(Dispatchers.IO) { runCatching { jrjHeadlines(limit) }.fold({ SourceResult("jrj_yaowen", it, null) }, { SourceResult("jrj_yaowen", null, it.message) }) },
        ).awaitAll()
        val status = linkedMapOf<String, String>()
        val errors = ArrayList<String>()
        val all = ArrayList<Map<String, Any?>>()
        results.forEach { result ->
            if (result.rows != null) {
                all.addAll(result.rows)
                status[result.name] = if (result.rows.isEmpty()) "empty" else "ok"
            } else {
                status[result.name] = "error"
                if (!result.error.isNullOrBlank()) errors += "${result.name}: ${result.error}"
            }
        }
        LocalNewsResult(all, status, errors, fetchedAt)
    }

    private suspend fun aigupiaoExpress(limit: Int): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val url = "https://apis.aigupiao.com/Express/express_list/?before=0&source=pc&web_data=yes&number=$limit&express_show_type=1"
        val root = getJson(url) ?: return@withContext emptyList()
        val out = ArrayList<Map<String, Any?>>()
        root.optJSONObject("data")?.keys()?.forEach { day ->
            root.optJSONObject("data")?.optJSONObject(day)?.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val title = item.optString("content").trim()
                    if (title.isBlank()) continue
                    val stocks = ArrayList<Map<String, Any?>>()
                    item.optJSONArray("stock_infos")?.let { ss ->
                        for (j in 0 until ss.length()) {
                            val s = ss.optJSONObject(j) ?: continue
                            if (s.optString("kind") == "stock" && s.optString("code").isNotBlank()) {
                                stocks += mapOf("code" to s.optString("code").replace("USA", ""), "name" to s.optString("name"))
                            }
                        }
                    }
                    out += mapOf("id" to (item.opt("id") ?: item.opt("rec_time")), "title" to title,
                        "source" to "aigupiao_express", "time" to item.optString("rec_time"),
                        "themes" to item.optString("theme"), "stocks" to stocks.take(5),
                        "important" to (item.optString("important") == "yes"))
                }
            }
        }
        out.take(limit)
    }

    private suspend fun jrjHeadlines(limit: Int): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val date = java.time.LocalDate.now().toString()
        val body = get("https://stockjs.jrj.com.cn/share/news/yaowen/yw$date.js") ?: return@withContext emptyList()
        val json = body.substringAfter('=', body).trim()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext emptyList()
        val arr = root.optJSONArray("newsinfo") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), limit)).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            mapOf("id" to (item.opt("iiid") ?: title), "title" to title,
                "content" to item.optString("detail").take(300), "source" to "jrj_yaowen",
                "time" to item.optString("makedate"), "url" to item.optString("infourl"))
        }
    }

    /** 实时 7x24 快讯（东财 np-listapi，设备直连，不受 CI 快照时效限制） */
    suspend fun news724(limit: Int = 30): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val trace = java.util.UUID.randomUUID().toString().replace("-", "")
        getJson("https://np-listapi.eastmoney.com/comm/web/getNewsByColumns" +
                "?client=web&biz=web7_24&fastColumn=100&column=100&req_trace=$trace" +
                "&pageSize=$limit&sortEnd=&sortType=0")
            ?.optJSONObject("data")?.optJSONArray("list")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val it = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = it.optString("title").takeIf { t -> t.isNotEmpty() } ?: return@mapNotNull null
                    mapOf<String, Any?>(
                        "title" to title,
                        "content" to it.optString("digest").take(500),
                        "source" to "eastmoney_724",
                        "updated" to it.optString("showTime"),
                        "url" to it.optString("url"),
                    )
                }
            } ?: emptyList()
    }

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
        // 没有任何下跌（al=0）且有过上涨 → 100；**但完全不动（ag 也为 0）要返回 50**，
        // 否则横盘股会被判「RSI 超买」而扣分，属于方向性错误。
        return when {
            al == 0.0 && ag == 0.0 -> 50.0
            al == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1 + ag / al)
        }
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

    /**
     * 盘中折算量比。
     *
     * 标准「量比」= (现量 / 已开市分钟) ÷ (过去 N 日均量 / 240)。
     * 盘中最后一根日 K 还没走完，直接用 [volumeRatio] 会比收盘后小一大截
     * （9:40 只走了 10 分钟），量能引擎因此把「正常放量」读成「缩量」。
     *
     * @param elapsedMinutes 已开市的连续竞价分钟数（1..239）；不在这个区间就退回原始量比
     */
    fun volumeRatioPaced(bars: List<KBar>, n: Int = 5, elapsedMinutes: Int): Double? {
        val raw = volumeRatio(bars, n) ?: return null
        if (elapsedMinutes !in 1 until 240) return raw
        return raw * 240.0 / elapsedMinutes
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
