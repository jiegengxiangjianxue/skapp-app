package com.skapp.app.data

/**
 * 进程内数据缓存 —— 避免「点已选中的复权按钮也重新拉取」这类无效请求。
 *
 * 缓存键：
 *   kline:{code}:{adjust}:{count}  → List<KBar>（日 K 不随盘中变化，可长缓存）
 *   quote:{code}                   → Quote（盘中 30s TTL）
 *   flow:{code}                    → List<Map>（资金流，日级）
 *   boards:{code}                  → List<Map>
 *   engine:{code}:{adjust}         → LocalEngineResult（由 kline 派生）
 *   detail:{code}                  → StockDetail（云端）
 *   minute:{code}                  → List<Map>（分时，盘中 60s TTL）
 */
private class CacheEntry(val value: Any?, val expiresAt: Long)

object DataCache {

    private const val QUOTE_TTL_MS = 30_000L
    private const val MINUTE_TTL_MS = 60_000L
    private const val DAY_TTL_MS = 6 * 60 * 60_000L      // 日 K / 资金流：半天（收盘后够用）
    /**
     * 盘中日 K 缓存只能活 60 秒。
     *
     * 之前日 K 一律按 6 小时缓存，盘中最后一根 bar 从 9:31 一直冻到 15:00——
     * 「实时分析」用的是上午十点的价格和成交量，量比/量能/振幅全是旧的。
     * 收盘后没有新 bar 进来，仍然用长 TTL，避免无意义重复拉取。
     */
    private const val KLINE_INTRADAY_TTL_MS = 60_000L
    private const val BOARDS_TTL_MS = 24 * 60 * 60_000L  // 板块归属：一天

    private val store = HashMap<String, CacheEntry>()

    private fun now() = System.currentTimeMillis()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val e = synchronized(store) { store[key] } ?: return null
        if (e.expiresAt < now()) {
            synchronized(store) { store.remove(key) }
            return null
        }
        return try { e.value as? T } catch (t: Throwable) { null }
    }

    fun put(key: String, value: Any?, ttlMs: Long) {
        synchronized(store) { store[key] = CacheEntry(value, now() + ttlMs) }
    }

    fun invalidate(key: String) {
        synchronized(store) { store.remove(key) }
    }

    fun invalidatePrefix(prefix: String) {
        synchronized(store) { store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) } }
    }

    fun clear() = synchronized(store) { store.clear() }

    // ── 便捷方法 ──────────────────────────────────────────

    fun klineKey(code: String, adjust: String, count: Int) = "kline:$code:$adjust:$count"

    /** 盘中/竞价时段用短 TTL，其余时段用长 TTL。 */
    private fun dayTtlMs(): Long =
        if (MarketHours.isTrading() || MarketHours.isAuction()) KLINE_INTRADAY_TTL_MS else DAY_TTL_MS

    suspend fun kline(code: String, adjust: String, count: Int, force: Boolean = false): List<KBar> {
        val key = klineKey(code, adjust, count)
        if (!force) get<List<KBar>>(key)?.let { return it }
        val bars = MarketDataClient.dayKline(code, count, adjust)
        put(key, bars, dayTtlMs())
        return bars
    }

    suspend fun quote(code: String): Quote? {
        val key = "quote:$code"
        get<Quote>(key)?.let { return it }
        val q = MarketDataClient.quote(code)
        put(key, q, QUOTE_TTL_MS)
        return q
    }

    suspend fun fundFlow(code: String, days: Int): List<Map<String, Any?>> {
        val key = "flow:$code:$days"
        get<List<Map<String, Any?>>>(key)?.let { return it }
        val f = MarketDataClient.fundFlowHistory(code, days)
        put(key, f, DAY_TTL_MS)
        return f
    }

    suspend fun boards(code: String): List<Map<String, Any?>> {
        val key = "boards:$code"
        get<List<Map<String, Any?>>>(key)?.let { return it }
        val b = MarketDataClient.boardsOf(code)
        put(key, b, BOARDS_TTL_MS)
        return b
    }

    /**
     * 本地引擎结果（由日 K 派生）。
     *
     * 缓存键必须带上**最后一根 bar 的指纹**：盘中 bar 数量不变、只是数值在更新，
     * 老键（只看 bars.size）会命中上午算出来的旧评分，一整天都不刷新。
     * 换新键时顺手清掉同股旧键，避免盘中每分钟攒一份结果把内存吃掉。
     */
    fun engine(code: String, adjust: String, bars: List<KBar>): LocalEngineResult {
        val last = bars.lastOrNull()
        val fp = "${bars.size}:${last?.date}:${last?.close}:${last?.vol}"
        val prefix = "engine:$code:$adjust:"
        val key = "$prefix$fp"
        get<LocalEngineResult>(key)?.let { return it }
        invalidatePrefix(prefix)
        val r = LocalEngine.analyze(bars)
        put(key, r, dayTtlMs())
        return r
    }

    suspend fun minute(code: String, force: Boolean = false): List<Map<String, Any?>> =
        minuteRich(code, force).points

    /** 分时（含昨收/交易日/来源）。盘中 60s TTL，余下时段 10 分钟。 */
    suspend fun minuteRich(code: String, force: Boolean = false): MarketDataClient.MinuteTrendData {
        val key = "minute_rich:$code"
        if (!force) get<MarketDataClient.MinuteTrendData>(key)?.let { return it }
        val m = MarketDataClient.minuteTrendRich(code)
        val ttl = if (MarketHours.isTrading() || MarketHours.isAuction()) MINUTE_TTL_MS else 10 * 60_000L
        if (m.points.isNotEmpty()) put(key, m, ttl)
        return m
    }

    /** 已缓存的复权方式（供 UI 标记「已缓存」） */
    fun cachedAdjusts(code: String): Set<String> {
        val prefix = "kline:$code:"
        return synchronized(store) {
            store.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore(":") }
                .toSet()
        }
    }
}
