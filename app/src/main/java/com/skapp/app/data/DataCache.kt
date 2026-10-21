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
    private const val DAY_TTL_MS = 6 * 60 * 60_000L      // 日 K / 资金流：半天
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

    suspend fun kline(code: String, adjust: String, count: Int): List<KBar> {
        val key = klineKey(code, adjust, count)
        get<List<KBar>>(key)?.let { return it }
        val bars = MarketDataClient.dayKline(code, count, adjust)
        put(key, bars, DAY_TTL_MS)
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

    fun engine(code: String, adjust: String, bars: List<KBar>): LocalEngineResult {
        val key = "engine:$code:$adjust:${bars.size}"
        get<LocalEngineResult>(key)?.let { return it }
        val r = LocalEngine.analyze(bars)
        put(key, r, DAY_TTL_MS)
        return r
    }

    suspend fun minute(code: String): List<Map<String, Any?>> {
        val key = "minute:$code"
        get<List<Map<String, Any?>>>(key)?.let { return it }
        val m = MarketDataClient.minuteTrend(code)
        put(key, m, MINUTE_TTL_MS)
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
