package com.skapp.app.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * 唯一的 HTTP GET 入口（2026-10-27 收敛，见 reports/code-review-skapp-app-20261027.md P1-2）。
 *
 * 背景：「gzip 未解压 → 数据空 → 页面空白」这类 bug 在本仓已复发两次
 * （docs/ISSUES.md 2026-10-24「实时选股按钮点击无反应」及其「二次」条目），
 * 根因不是单点写错，而是 `HttpURLConnection + UA + Referer + gzip 判断 + 读流` 这套样板
 * 在 6 处各写一遍（MarketDataClient / SiteBoardClient / LiveMarket / ExtendedDataClient /
 * SkappApi / BoardScreen / DiagScreen），修一处漏五处。这里收敛成一份。
 *
 * 行为约定（刻意与收敛前逐处对齐，不顺手改口径）：
 * - 只做 GET；连接层异常不抛，记在 [Probe.error]；HTTP 非 2xx 时 [Probe.body] 为 null；
 * - 响应头声明 gzip 才解压。**HttpURLConnection 不会自动加 Accept-Encoding**，
 *   要请求压缩必须显式 `requestGzip = true`（旧注释里「默认请求 gzip」的说法是错的）；
 * - UA / Referer / Accept 可选，传 null 表示不设该头（不要塞空串，两者的线上行为不同）；
 * - [headers] 最后写入，可覆盖上面三个具名头（ExtendedDataClient 的桌面 UA + Host 就靠这个）。
 */
object Http {

    /** 一次 GET 的结果。`code < 0` 表示连接层失败，原因在 [error]（类名，便于诊断页展示）。 */
    data class Probe(val code: Int, val body: String?, val error: String? = null) {
        val ok: Boolean get() = code in 200..299
        val text: String get() = body.orEmpty()
    }

    fun probe(
        url: String,
        charset: Charset = Charsets.UTF_8,
        timeoutMs: Int = 20_000,
        readTimeoutMs: Int = timeoutMs,
        ua: String? = RemoteSources.UA_ANDROID,
        referer: String? = null,
        accept: String? = null,
        headers: Map<String, String> = emptyMap(),
        requestGzip: Boolean = false,
    ): Probe {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                ua?.let { setRequestProperty("User-Agent", it) }
                referer?.let { setRequestProperty("Referer", it) }
                accept?.let { setRequestProperty("Accept", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (requestGzip) setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Probe(code, null)
            } else {
                val enc = conn.contentEncoding
                val gzipped = enc == "gzip" || enc == "x-gzip" ||
                    (requestGzip && conn.headerFields.values.any { vs -> vs?.any { it.equals("gzip", true) } == true })
                val stream = if (gzipped) GZIPInputStream(conn.inputStream) else conn.inputStream
                Probe(code, BufferedReader(InputStreamReader(stream, charset)).use { it.readText() })
            }
        } catch (e: Exception) {
            Probe(-1, null, e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** 只取正文：非 [successRange] 或异常一律 null（与原各处的 `!= HTTP_OK -> null` 语义对齐）。 */
    fun getText(
        url: String,
        charset: Charset = Charsets.UTF_8,
        timeoutMs: Int = 20_000,
        readTimeoutMs: Int = timeoutMs,
        ua: String? = RemoteSources.UA_ANDROID,
        referer: String? = null,
        accept: String? = null,
        headers: Map<String, String> = emptyMap(),
        requestGzip: Boolean = false,
        successRange: IntRange = 200..299,
    ): String? = probe(
        url = url,
        charset = charset,
        timeoutMs = timeoutMs,
        readTimeoutMs = readTimeoutMs,
        ua = ua,
        referer = referer,
        accept = accept,
        headers = headers,
        requestGzip = requestGzip,
    ).let { if (it.code in successRange) it.body else null }

    /**
     * 站点字符集名 → Charset，与原各处映射保持一致：
     * `gbk` → GBK，其余 `gb*`（gb2312/gb18030）→ GB18030，其他 → UTF-8。
     * 注意：原 MarketDataClient / SkappApi 把非 gbk 的 gb* 当 UTF-8 读（会乱码），
     * 现按 GB18030 读；实测这两处调用点只传 `gbk`，故无实际行为变化。
     */
    fun charset(name: String): Charset = when {
        name.equals("gbk", true) -> Charset.forName("GBK")
        name.startsWith("gb", true) -> Charset.forName("GB18030")
        else -> Charsets.UTF_8
    }
}
