package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val rawTs get() = CstTime.fmt("yyyyMMddHHmmss")
    private val GBK = java.nio.charset.Charset.forName("GBK")

    /** 批量报价；返回 6 位归一化代码 → 报价。拉不到的票不进结果，由调用方决定怎么标陈旧。 */
    suspend fun batch(codes: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        val wanted = codes.mapNotNull { Code.normalize(it) }.distinct()
        if (wanted.isEmpty()) return@withContext emptyMap()
        val out = LinkedHashMap<String, Quote>()
        for (chunk in wanted.chunked(40)) {
            val url = "https://qt.gtimg.cn/q=" + chunk.joinToString(",") { Code.prefixed(it) }
            val probe = Http.probe(url, charset = GBK, referer = "https://gu.qq.com/")
            if (!probe.ok) continue
            probe.text.lineSequence().forEach { line ->
                parseLine(line)?.let { out[it.code] = it }
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
        val tsRaw = f[30]
        val ts = if (tsRaw.length == 14) {
            runCatching { iso.format(rawTs.parse(tsRaw)!!) }.getOrNull()
        } else null
        return Quote(
            code = code,
            name = f[1],
            price = f[3].toDoubleOrNull(),
            pctChg = f[32].toDoubleOrNull(),
            ts = ts,
        )
    }
}
