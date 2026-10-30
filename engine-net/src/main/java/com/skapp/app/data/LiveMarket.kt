package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 实时全市场行情源（东财 clist，盘中真实盘口）。
 *
 * ## 为什么单独拆出来
 *
 * 原先「拉全市场快照」这段逻辑写在 `LocalScreener.marketSnapshot` 里，结果被三处各拉一遍：
 * 选股、策略卡、界面。盘中一次刷新就是三次十几页的重复请求，而且每处拿到的**时点还不一样**
 * （同一屏里「全市场 5000 只」和「筛选池」可能隔着十几秒）。
 *
 * 现在统一在这里拉一次、共享一份短 TTL 缓存：同一次刷新里所有分析看到的是**同一个时点**，
 * 也把请求数从 3×N 压到 1×N。
 *
 * ## 数据口径
 *
 * - 只允许实时接口。云端快照不在这里出现——本层是「链路」，不是「库」。
 * - 涨跌停判定按涨跌幅近似（主板 ±10%、ST ±5%、创业板/科创板 ±20%）：
 *   clist 不直接给涨停价，精确判定要再拉一次涨停价表，代价不划算，这里近似并在字段上写明。
 *
 * ## 缓存
 *
 * 盘中 5s（够一屏用，且不会把「刚拉到的」重复拉）、非盘中 60s。
 */
object LiveMarket {

    /** 一行实时盘口。字段名与东财 clist 对应关系写在每个属性后面。 */
    data class Row(
        val code: String,
        val name: String?,
        val price: Double?,        // f2  最新价
        val pctChg: Double?,       // f3  涨跌幅 %
        val volumeLots: Double?,   // f5  成交量（手）
        val amount: Double?,       // f6  成交额（元）
        val amplitude: Double?,    // f7  振幅 %
        val turnoverRate: Double?, // f8  换手率 %
        val pe: Double?,           // f9  市盈率（动）
        val volRatio: Double?,     // f10 量比
        val mv: Double?,           // f20 总市值（元）
        val mainNet: Double?,      // f62 主力净流入（元）
        val ts: Long,              // 抓取时刻
        /**
         * 以下四个字段为「RPS / 封板率」本地计算新增（同一次 clist 批量里取，零额外请求）：
         * f15 最高价、f18 昨收、f24 60 日涨跌幅、f25 年初至今涨跌幅。
         * 拿不到时保持 null —— 宁可显示不可得，也不填 0。
         */
        val high: Double? = null,       // f15 最高价
        /**
         * f16 最低价、f17 今开：退出纪律（长下影/区间位置）要用当根振幅，
         * 只有 high 算不出下影线。同在这一次 clist 批量里，零额外请求。
         */
        val low: Double? = null,        // f16 最低价
        val open: Double? = null,       // f17 今开
        val prevClose: Double? = null,  // f18 昨收
        val chg60d: Double? = null,     // f24 60 日涨跌幅 %
        val chgYtd: Double? = null,     // f25 年初至今涨跌幅 %
        /**
         * f22 涨速（%，东财约 5 分钟窗口）。
         *
         * 异动榜第一轮没有「上一轮快照」可比，必须有这个字段兜底；之前界面读了一个
         * 快照里根本不存在的 "speed" 键，于是首轮整张异动榜都是「缺少连续快照」。
         */
        val speed: Double? = null,
    ) {

        /** 涨停价（元）：昨收 ×(1+上限)，两位小数。昨收缺失则为 null。 */
        val limitUpPrice: Double?
            get() = prevClose?.let { p -> Math.round(p * (1 + limitCap / 100.0) * 100.0) / 100.0 }

        /** 曾触及涨停（用于算炸板/封板率）：最高价到过涨停价但当前没封住。 */
        val touchedLimitUp: Boolean
            get() {
                val lp = limitUpPrice ?: return false
                val hi = high ?: return false
                return hi >= lp - 0.011
            }

        /** 炸板：触及过涨停价但现价没封在涨停价。 */
        val brokenBoard: Boolean
            get() {
                val lp = limitUpPrice ?: return false
                val px = price ?: return false
                return touchedLimitUp && px < lp - 0.011
            }
        /**
         * 涨跌幅上限：主板 10%（ST 5%）、创业板/科创板 20%（689 是科创板 CDR，同样 20%）、
         * 北交所 30%。ST 只在主板是 5%，创业/科创板的 ST 仍是 20%。
         */
        val limitCap: Double
            get() {
                val base = when {
                    code.startsWith("300") || code.startsWith("301") ||
                        code.startsWith("688") || code.startsWith("689") -> 20.0
                    code.startsWith("8") || code.startsWith("4") || code.startsWith("92") -> 30.0
                    else -> 10.0
                }
                return if (base == 10.0 && name != null && name.contains("ST", ignoreCase = true)) 5.0 else base
            }

        /** 跌停价（元）：昨收 ×(1-上限)，两位小数。 */
        val limitDownPrice: Double?
            get() = prevClose?.let { p -> Math.round(p * (1 - limitCap / 100.0) * 100.0) / 100.0 }

        /**
         * 涨停判定的抖动余量。
         *
         * 涨停价按「昨收×(1+上限) 四舍五入到分」定，于是显示出来的涨幅天然偏离上限：
         * 10cm 常见 9.8~10.0、20cm 常见 19.7~20.0、30cm 常见 29.5~30.0。
         * 余量因此按上限的 3.5% 取（10→0.35 / 20→0.7 / 30→1.05），
         * 而不是写死 0.35——写死的话 30cm 板几乎永远判不出涨停。
         * ST 的 5cm 板另设 0.2 下限：低价 ST 股四舍五入后涨幅可能只有 4.8%。
         */
        private val limitSlack: Double get() = (limitCap * 0.035).coerceAtLeast(0.2)

        /**
         * 是否涨停。
         *
         * **有昨收就按价判**（现价 ≥ 涨停价 − 0.005，0.005 只为浮点容差），
         * 没有昨收才退回涨幅近似。原因：涨幅近似必须留余量（10cm 板真实涨幅可能只有 9.85%），
         * 而这个余量会把 +9.7% 的**没涨停**股票也算成涨停——涨停家数、封板率、板块涨停池都会虚高。
         */
        val atLimitUp: Boolean
            get() {
                val lp = limitUpPrice
                val px = price
                if (lp != null && px != null && px > 0.0) return px >= lp - 0.005
                val c = pctChg ?: return false
                return c >= limitCap - limitSlack
            }

        /** 是否跌停（判定与涨停同理：有昨收按价判）。 */
        val atLimitDown: Boolean
            get() {
                val ld = limitDownPrice
                val px = price
                if (ld != null && px != null && px > 0.0) return px <= ld + 0.005
                val c = pctChg ?: return false
                return c <= -(limitCap - limitSlack)
            }

        /**
         * 是否在正常交易（有价格、有成交量）。
         *
         * 停牌票 clist 里照样有行，涨跌幅是 0 —— 直接算「平盘」会把平盘数虚增、
         * 把中位涨幅往 0 拉、把板块均值稀释。统计口径要先过这一关。
         */
        val tradable: Boolean
            get() = (price ?: 0.0) > 0.0 && (volumeLots ?: 0.0) > 0.0

        /**
         * 是否沪深主板。与 [Code.isMainBoard] 同一判据——此前这里用 `startsWith("60")||startsWith("00")`
         * 的宽口径，使 003xxx 在统计路径算主板、在推荐路径算非主板，同一只票归属相反。
         */
        val mainBoard: Boolean
            get() = Code.isMainBoard(code)
    }

    private const val PAGE_SIZE = 500
    private const val MAX_PAGES = 40

    /**
     * clist 的备用域名。实测 push2 会被风控直接返回空，而 push2delay 同一时刻有数据；
     * 反过来 push2delay 连续请求也会被限流。两个都不是稳定源，所以两个都试。
     */
    private val HOSTS = arrayOf("https://push2.eastmoney.com", "https://push2delay.eastmoney.com")

    /** 最近一次拉成功的域名下标：限流按域名算，粘住可用的那个能省一半请求。 */
    @Volatile private var hostIdx = 0

    @Volatile private var cached: List<Row> = emptyList()
    @Volatile private var cachedAt = 0L

    /** 快照互斥锁：同一时刻只允许一个协程真正去拉全市场。 */
    private val fetchLock = Mutex()

    private fun ttlMs(): Long = if (MarketHours.isTrading() || MarketHours.isAuction()) 5_000L else 60_000L

    /**
     * 全市场实时快照。
     *
     * @param force 跳缓存（用户手动刷新时用）
     * @return 真实抓取结果；网络全挂时返回空列表（**不返回假数据**，由上层显示「暂无数据」）
     */
    suspend fun snapshot(force: Boolean = false): List<Row> = withContext(Dispatchers.IO) {
        val fresh = cached.takeIf { !force && it.isNotEmpty() && System.currentTimeMillis() - cachedAt < ttlMs() }
        if (fresh != null) return@withContext fresh

        // 并发去重：心跳深算、界面刷新、策略卡可能同时要快照。
        // 没有这把锁时它们会各拉一遍十几页，而且拿到**不同时点**的数据；
        // 抢到锁之后先复查缓存——前一个协程可能刚拉完。
        val rows = fetchLock.withLock {
            val again = cached.takeIf { !force && it.isNotEmpty() && System.currentTimeMillis() - cachedAt < ttlMs() }
            if (again != null) return@withLock again
            val fetched = fetchAll()
            if (fetched.isNotEmpty()) {
                cached = fetched
                cachedAt = System.currentTimeMillis()
            }
            // 盘中拉不到时宁可给上一份，并保留真实抓取时间，让上层能标「陈旧」。
            fetched.ifEmpty { cached }
        }
        rows
    }

    private fun fetchPage(page: Int): List<Row> {
        val query = "/api/qt/clist/get?pn=$page&pz=$PAGE_SIZE&po=1&fid=f3" +
            "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048" +
            // f15/f18/f24/f25 一并取：RPS（60日/年初至今涨幅）与封板率（最高价 vs 涨停价）
            // 都要它们，放在同一次批量里不增加请求数。
            "&fields=f2,f3,f5,f6,f7,f8,f9,f10,f12,f14,f15,f16,f17,f18,f20,f22,f24,f25,f62"
        for (attempt in HOSTS.indices) {
            val idx = (hostIdx + attempt) % HOSTS.size
            val rows = parsePage(httpGet(HOSTS[idx] + query))
            if (rows.isNotEmpty()) {
                hostIdx = idx
                return rows
            }
        }
        return emptyList()
    }

    private fun parsePage(txt: String?): List<Row> {
        if (txt.isNullOrEmpty()) return emptyList()
        val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
        val ts = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("f12").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Row(
                code = code,
                name = o.optString("f14").takeIf { it.isNotEmpty() },
                price = o.num("f2"),
                pctChg = o.num("f3"),
                volumeLots = o.num("f5"),
                amount = o.num("f6"),
                amplitude = o.num("f7"),
                turnoverRate = o.num("f8"),
                pe = o.num("f9"),
                volRatio = o.num("f10"),
                mv = o.num("f20"),
                mainNet = o.num("f62"),
                ts = ts,
                high = o.num("f15"),
                low = o.num("f16"),
                open = o.num("f17"),
                prevClose = o.num("f18"),
                chg60d = o.num("f24"),
                chgYtd = o.num("f25"),
                speed = o.num("f22"),
            )
        }
    }

    private fun fetchAll(): List<Row> {
        val out = LinkedHashMap<String, Row>()
        var page = 1
        while (page <= MAX_PAGES) {
            val batch = fetchPage(page)
            if (batch.isEmpty()) break
            batch.forEach { out.putIfAbsent(it.code, it) }
            if (batch.size < PAGE_SIZE) break
            page++
        }
        if (out.isEmpty()) return emptyList()
        // 北交所/港股通等混在里面，按 6 位纯数字代码归一（保留原始码，纯位数判断由 mainBoard 做）。
        return out.values.filter { it.code.length == 6 && it.code.all(Char::isDigit) }
    }

    private fun JSONObject.num(key: String): Double? {
        val v = opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString().toDoubleOrNull()
    }

    private fun httpGet(url: String): String? = Http.getText(
        url = url,
        charset = Charsets.UTF_8,
        timeoutMs = 20_000,
        referer = "https://wap.eastmoney.com/",
        accept = "application/json",
    )
}
