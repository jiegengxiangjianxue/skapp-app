package com.skapp.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 腾讯行情直连（qt.gtimg.cn）——全市场快照被拒时的报价兜底。
 *
 * 为什么需要它：自选/推股/决策的报价平时来自 [LiveMarket.snapshot]（东财 clist）或云端合并产物。
 * 东财风控拒绝时（响应空、kline 返回 rc=102）这两个来源会一起变空，界面上就只剩下「暂无数据」，
 * 而腾讯这个接口不校验 cookie、支持逗号批量、GBK 编码，是现成的第二来源。
 *
 * 只做报价兜底，不承担全市场列表：没有代码清单就无从拉取，异动榜/板块仍需要各自的数据源。
 */
object TencentQuote {

    private val iso get() = CstTime.fmt("yyyy-MM-dd HH:mm:ss")
    // 必须走 CstTime：SimpleDateFormat 默认跟设备时区，UTC 设备上会把北京时间的
    // 报文解析成 UTC 再按 CST 渲染，时间戳平白差 8 小时。
    private val sourceTime = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val sourceZone = ZoneId.of("Asia/Shanghai")

    internal const val MARKET_SOURCE = "sina_universe/tencent_quote"

    internal fun sourceTimestamp(raw: String, now: Long = System.currentTimeMillis()): Long? {
        if (raw.length != 14 || !raw.all { it in '0'..'9' }) return null
        return runCatching { LocalDateTime.parse(raw, sourceTime).atZone(sourceZone).toInstant().toEpochMilli() }
            .getOrNull()?.takeIf { it > 0 && it <= now }
    }

    /** 官方枚举仅提供身份；所有报价字段和日期必须取自同一腾讯数组。 */
    internal fun parseMarketRows(text: String?, wanted: List<String>, expectedTotal: Int?): List<LiveMarket.Row> {
        if (text == null) return emptyList()
        // Android 与 JVM 的 JSONObject 对重复键处理不同，先拒绝歧义身份。
        val identities = Regex("\"(?:sh|sz|bj)[0-9]{6}\"\\s*:").findAll(text).map { it.value.substringBeforeLast('"') }.toList()
        if (identities.distinct().size != identities.size) return emptyList()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val allowed = wanted.associateBy { Code.prefixed(it) }
        val out = LinkedHashMap<String, LiveMarket.Row>()
        for (key in obj.keys()) {
            val code = allowed[key] ?: continue
            val f = obj.optJSONArray(key) ?: continue
            if (f.length() != 88 || (0 until f.length()).any { f.opt(it) !is String }) continue
            if (f.getString(2) != code) continue
            val ts = sourceTimestamp(f.getString(30)) ?: continue
            fun num(i: Int): Double? = f.getString(i).toDoubleOrNull()?.takeIf { it.isFinite() }
            val price = num(3)?.takeIf { it > 0 } ?: continue
            val row = LiveMarket.Row(
                code = code, name = f.getString(1).takeIf { it.isNotEmpty() },
                price = price, pctChg = num(32), volumeLots = num(6)?.takeIf { it >= 0 },
                amount = null, amplitude = null, turnoverRate = null, pe = null,
                volRatio = null, mv = null, mainNet = null, ts = ts,
                high = num(33), low = num(34), open = num(5), prevClose = num(4),
                expectedTotal = expectedTotal, source = MARKET_SOURCE,
            )
            out[code] = row
        }
        return out.values.toList()
    }

    internal suspend fun marketSnapshot(codes: List<String>, expectedTotal: Int?, deadline: Long): List<LiveMarket.Row> = coroutineScope {
        val sem = Semaphore(6)
        codes.distinct().chunked(100).map { chunk -> async(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            sem.withPermit {
                currentCoroutineContext().ensureActive()
                val remaining = deadline - System.nanoTime()
                val timeout = (remaining / 2_000_000L).coerceAtMost(3_000L).toInt()
                if (timeout <= 0) return@withPermit emptyList<LiveMarket.Row>()
                val text = Http.getText(
                    "https://sqt.gtimg.cn/utf8/?q=" + chunk.joinToString(",") { Code.prefixed(it) } + "&fmt=json",
                    timeoutMs = timeout, readTimeoutMs = timeout, referer = "https://gu.qq.com/",
                    accept = "application/json",
                )
                currentCoroutineContext().ensureActive()
                parseMarketRows(text, chunk, expectedTotal)
            }
        } }.awaitAll().flatten()
    }
    private val GBK = java.nio.charset.Charset.forName("GBK")

    /** 批量报价；返回 6 位归一化代码 → 报价。拉不到的票不进结果，由调用方决定怎么标陈旧。 */
    suspend fun batch(codes: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        val wanted = codes.mapNotNull { Code.normalize(it) }.distinct()
        if (wanted.isEmpty()) return@withContext emptyMap()
        val out = LinkedHashMap<String, Quote>()
        for (chunk in wanted.chunked(40)) {
            currentCoroutineContext().ensureActive()
            val url = "https://qt.gtimg.cn/q=" + chunk.joinToString(",") { Code.prefixed(it) }
            val probe = Http.probe(url, charset = GBK, referer = "https://gu.qq.com/")
            currentCoroutineContext().ensureActive()
            if (!probe.ok) continue
            probe.text.lineSequence().forEach { line ->
                parseLine(line)?.takeIf { it.code in chunk }?.let { out[it.code] = it }
            }
        }
        out
    }

    /**
     * 解析一行 `v_sh600519="1~名称~600519~现价~昨收~今开~…~时间~涨跌~涨跌幅~…"`。
     *
     * 按下标取值，字段数不足直接放弃——腾讯偶尔对停牌/退市票返回短行，
     * 硬取会抛 IndexOutOfBounds 并把整批报价拖垮。
     */
    internal fun parseLine(line: String): Quote? {
        val body = line.substringAfter("\"", "").substringBefore("\"")
        val f = body.split('~')
        if (f.size < 33) return null
        val code = Code.normalize(f[2]) ?: return null
        val price = f[3].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val timestamp = sourceTimestamp(f[30]) ?: return null
        return Quote(
            code = code,
            name = f[1],
            price = price,
            pctChg = f[32].toDoubleOrNull()?.takeIf { it.isFinite() },
            ts = iso.format(java.util.Date(timestamp)),
        )
    }
}
