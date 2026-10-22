package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 本地实时选股引擎 —— 在设备上完成「实时收集数据 → 算法分析 → 出结果」。
 *
 * 用户核心诉求：推股/决策不能再用云端旧快照，要在手机上实时算。
 *
 * 数据链（全部实时，无云端快照）：
 *   1) 东财 clist 批量拉全市场实时行情（价格/涨跌幅/量比/市值/PE，一次 5000 只）
 *   2) 预筛（放量 + 涨幅区间 + 非 ST）→ 候选池
 *   3) 对候选池前 N 只逐只拉日 K（腾讯→东财），本地 12 引擎 + 29 因子
 *   4) 按 composite 排序输出
 *
 * 红线：只用真实拉到的数据；拉不到的标 insufficient，不编造。
 */
object LocalScreener {

    /** 筛选条件 */
    data class Criteria(
        val minChgPct: Double = 1.0,
        val maxChgPct: Double = 10.5,
        val minVolRatio: Double = 1.2,
        val minScore: Double = 55.0,
        val excludeST: Boolean = true,
        val maxCandidates: Int = 40,   // 逐只拉 K 线的上限（控制耗时/流量）
        val klineCount: Int = 120,
    )

    /** 一只股票的实时分析结果 */
    data class Screened(
        val code: String,
        val name: String?,
        val price: Double?,
        val pctChg: Double?,
        val volRatio: Double?,
        val turnover: Double?,
        val pe: Double?,
        val mv: Double?,
        val engine: LocalEngineResult?,
        val detailStatus: String,     // ok | insufficient
        val reason: String? = null,
    )

    /** 全市场实时行情快照（东财 clist） */
    suspend fun marketSnapshot(pn: Int = 1, pz: Int = 3000): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=$pn&pz=$pz&po=1&fid=f3" +
                "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048" +
                "&fields=f2,f3,f5,f6,f8,f9,f10,f12,f14,f20,f21,f62,f115,f124"
            val txt = HttpURLConnectionBridge(url).readText() ?: return@withContext emptyList()
            val obj = runCatching { org.json.JSONObject(txt) }.getOrNull() ?: return@withContext emptyList()
            val arr = obj?.optJSONObject("data")?.optJSONArray("diff")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optString("f12").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                mapOf<String, Any?>(
                    "code" to code,
                    "name" to o.optString("f14").takeIf { it.isNotEmpty() },
                    "price" to o.opt("f2")?.toString()?.toDoubleOrNull(),
                    "pctChg" to o.opt("f3")?.toString()?.toDoubleOrNull(),
                    "volRatio" to o.opt("f10")?.toString()?.toDoubleOrNull(),
                    "turnover" to o.opt("f5")?.toString()?.toDoubleOrNull(),
                    "volume" to o.opt("f6")?.toString()?.toDoubleOrNull(),
                    "amplitude" to o.opt("f7")?.toString()?.toDoubleOrNull(),
                    "turnoverRate" to o.opt("f8")?.toString()?.toDoubleOrNull(),
                    "pe" to o.opt("f9")?.toString()?.toDoubleOrNull(),
                    "mv" to o.opt("f20")?.toString()?.toDoubleOrNull(),
                    "mainNet" to o.opt("f62")?.toString()?.toDoubleOrNull(),
                    "speed" to o.opt("f124")?.toString()?.toDoubleOrNull(),
                )
            }
        }

    /**
     * 实时选股：市场快照 → 预筛 → 逐只本地引擎 → 排序。
     */
    suspend fun screen(c: Criteria = Criteria()): List<Screened> = coroutineScope {
        val snap = marketSnapshot()
        if (snap.isEmpty()) return@coroutineScope emptyList()

        // 预筛
        val cands = snap.filter { m ->
            val chg = (m["pctChg"] as? Number)?.toDouble() ?: return@filter false
            val vr = (m["volRatio"] as? Number)?.toDouble()
            val name = m["name"]?.toString() ?: ""
            val st = c.excludeST && (name.contains("ST", ignoreCase = true) ||
                name.contains("退", ignoreCase = true))
            chg in c.minChgPct..c.maxChgPct && !st && (vr == null || vr >= c.minVolRatio)
        }.sortedByDescending { (it["mainNet"] as? Number)?.toDouble() ?: 0.0 }
            .take(c.maxCandidates)

        // 逐只拉 K 线 + 本地引擎（并发 4，避免打满）
        val out = ArrayList<Screened>()
        cands.chunked(4).forEach { batch ->
            val results = batch.map { m ->
                async(Dispatchers.IO) {
                    val code = m["code"]?.toString() ?: return@async null
                    val bars = DataCache.kline(code, "qfq", c.klineCount)
                    val eng = if (bars.size >= 30) DataCache.engine(code, "qfq", bars) else null
                    Screened(
                        code = code,
                        name = m["name"]?.toString(),
                        price = (m["price"] as? Number)?.toDouble(),
                        pctChg = (m["pctChg"] as? Number)?.toDouble(),
                        volRatio = (m["volRatio"] as? Number)?.toDouble(),
                        turnover = (m["turnover"] as? Number)?.toDouble(),
                        pe = (m["pe"] as? Number)?.toDouble(),
                        mv = (m["mv"] as? Number)?.toDouble(),
                        engine = eng,
                        detailStatus = if (eng != null) "ok" else "insufficient",
                        reason = if (eng == null) "K线不足，无法本地计算" else null,
                    )
                }
            }.mapNotNull { runCatching { it.await() }.getOrNull() }
            out.addAll(results)
        }

        out.filter { (it.engine?.composite ?: 0.0) >= c.minScore }
            .sortedByDescending { it.engine?.composite ?: 0.0 }
    }
}

/** 极简 HTTP 桥（避免与 MarketDataClient 的 private get 重复） */
private class HttpURLConnectionBridge(url: String) {
    private val conn: java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
            setRequestProperty("Referer", "https://quote.eastmoney.com/")
            setRequestProperty("Accept", "application/json")
        }

    fun readText(): String? =
        try {
            if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
}
