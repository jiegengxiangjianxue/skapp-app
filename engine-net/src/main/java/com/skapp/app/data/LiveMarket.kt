package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    // 东财 clist 单页最多回 100 行（实测 pz=200/500 都只回 100），旧值 500 会让
    // fetchAll 的 `batch.size < PAGE_SIZE` 第一页就成立，快照永远只剩涨幅前 100 只。
    private const val PAGE_SIZE = 100
    // 实测 total=5926，100/页 需 60 页。
    private const val MAX_PAGES = 60

    // 东财正常 5500~5900 只。低于这个数说明分页被限流截断——最坏情况只剩涨幅前 100 只，
    // 此时推荐池就是残缺样本，名单等于只在前 100 名里挑。命中即用新浪补全市场。
    private const val FULL_SNAPSHOT = 4000

    // 新浪 hs_a 分页：东财被风控时的兜底源。实测 56 页 x 100（末页 76）= 5576 只、
    // 8 并发 2.8s、无缺页。字段比东财少（无量比 f10、主力净额 f62、f24/f25 区间涨幅、涨速 f22），
    // 缺的保持 null——LiveExtras 的 RPS 遇 null 会跳过该行，不会当成 0 参与排名。
    private const val SINA_PAGE_SIZE = 100
    private const val SINA_MAX_PAGES = 58
    private const val SINA_BASE =
        "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData"

    /**
     * clist 的备用域名。实测 push2 会被风控直接返回空，而 push2delay 同一时刻有数据；
     * 反过来 push2delay 连续请求也会被限流。两个都不是稳定源，所以两个都试。
     */
    // 延迟域名放首位：实测 push2 被风控/不可达时单页要 ~12–20s 才判失败，而 push2delay 0.15s
    // 就能回同一份实时数据。主源在前会让推股首屏白等一个超时（fetchAll 串行 27 页）。
    private val HOSTS = arrayOf("https://push2delay.eastmoney.com", "https://push2.eastmoney.com")

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
        // 单页失败即返回空，重试交给 fetchAll 的补拉轮：在这里 sleep 退避会在并发下
        // 把 8 条 IO 线程全按在睡眠里（实测串行节流 63.8s/37 页，并发 8 只要 2.75s/42 页）。
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

    private suspend fun fetchAll(): List<Row> {
        val east = fetchEastAll()
        if (east.size >= FULL_SNAPSHOT) return east
        val sina = fetchSinaAll()
        if (sina.isEmpty()) return east
        // 东财行优先：它多量比/主力净额/60日涨幅，RPS 与资金面策略要靠这些字段。
        val merged = LinkedHashMap<String, Row>()
        east.forEach { merged[it.code] = it }
        sina.forEach { merged.putIfAbsent(it.code, it) }
        return merged.values.filter { it.code.length == 6 && it.code.all(Char::isDigit) }
    }

    private suspend fun fetchEastAll(): List<Row> = coroutineScope {
        // 并发 8 是实测最优点（2026-10-30）：42/60 页、2.75s；串行无节流第 28 页就断连（2700 只），
        // 串行+退避重试 37 页/63.8s，并发 16 反而因限流加剧掉到 36 页。
        val sem = Semaphore(8)
        val first = (1..MAX_PAGES)
            .map { p -> async(Dispatchers.IO) { sem.withPermit { fetchPage(p) } } }
            .awaitAll()

        // 第一轮空的页补一次，并发降到 4：限流是成片断连，二次冲击用同样的并发只会继续被拒。
        val sem2 = Semaphore(4)
        val retried = first.indices
            .filter { first[it].isEmpty() }
            .map { i -> async(Dispatchers.IO) { sem2.withPermit { fetchPage(i + 1) } } }
            .awaitAll()

        val out = LinkedHashMap<String, Row>()
        (first + retried).forEach { batch -> batch.forEach { out.putIfAbsent(it.code, it) } }
        if (out.isEmpty()) return@coroutineScope emptyList()
        // 北交所/港股通等混在里面，按 6 位纯数字代码归一（保留原始码，纯位数判断由 mainBoard 做）。
        out.values.filter { it.code.length == 6 && it.code.all(Char::isDigit) }
    }

    private fun JSONObject.num(key: String): Double? {
        val v = opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString().toDoubleOrNull()
    }

    private fun fetchSinaPage(page: Int): List<Row> {
        val url = SINA_BASE + "?page=$page&num=$SINA_PAGE_SIZE&sort=symbol&asc=1&node=hs_a"
        val txt = httpGet(url, referer = "https://finance.sina.com.cn/") ?: return emptyList()
        val arr = runCatching { JSONArray(txt) }.getOrNull() ?: return emptyList()
        val ts = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("code").takeIf { it.length == 6 } ?: return@mapNotNull null
            val high = o.num("high")
            val low = o.num("low")
            val prev = o.num("settlement")
            Row(
                code = code,
                name = o.optString("name").takeIf { it.isNotEmpty() },
                price = o.num("trade"),
                pctChg = o.num("changepercent"),
                // 新浪 volume 单位是股，东财 f5 是手。
                volumeLots = o.num("volume")?.div(100.0),
                amount = o.num("amount"),
                // 无 f7，按 (高-低)/前收 自算，与东财振幅同口径。
                amplitude = if (high != null && low != null && (prev ?: 0.0) > 0.0) {
                    (high - low) / prev!! * 100
                } else null,
                turnoverRate = o.num("turnoverratio"),
                pe = o.num("per"),
                volRatio = null,
                // 新浪 mktcap 单位万元，东财 f20 是元。
                mv = o.num("mktcap")?.times(10_000.0),
                mainNet = null,
                ts = ts,
                high = high,
                low = low,
                open = o.num("open"),
                prevClose = prev,
                chg60d = null,
                chgYtd = null,
                speed = null,
            )
        }
    }

    private suspend fun fetchSinaAll(): List<Row> = coroutineScope {
        val sem = Semaphore(8)
        val batches = (1..SINA_MAX_PAGES)
            .map { p -> async(Dispatchers.IO) { sem.withPermit { fetchSinaPage(p) } } }
            .awaitAll()
        val out = LinkedHashMap<String, Row>()
        batches.forEach { b -> b.forEach { out.putIfAbsent(it.code, it) } }
        out.values.toList()
    }

    private fun httpGet(url: String, referer: String = "https://wap.eastmoney.com/"): String? = Http.getText(
        url = url,
        charset = Charsets.UTF_8,
        timeoutMs = 8_000,
        referer = referer,
        accept = "application/json",
    )
}
