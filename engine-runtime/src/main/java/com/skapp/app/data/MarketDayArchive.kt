package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 本地日级快照存档 —— 让「连板高度 / 晋级率 / 板块历史动量 / 长周期 RPS」从不可得变成可算。
 *
 * ## 为什么存、又为什么只存当天
 *
 * 东财 clist 只给「此刻」。昨天谁涨停、前天谁涨停，一旦没存下来就再也补不回来，
 * 于是引擎池里的 `height / promo / firstBoard / ge2` 只能永远写 -1 并标「不可得」。
 * 这不是算法缺，是数据缺。
 *
 * 2026-10 起「昨天谁涨停」这类历史搬到云端：`limit_history.json`（260 个交易日的涨停名单，
 * 由 stocks-ai-view 的 `build_limit_history.py` 用全市场日线回算），端上不必为连板/晋级率囤历史。
 *
 * ## 但 MAX_DAYS 不能是 1（2026-10-29 改）
 *
 * 原先只留当天，后果是端上**任何需要 60 日序列的量都永远不可得**：
 * 波动率目标仓位（[VolTargetGate]）要 60 个交易日的全市场日收益，
 * 端上却连两个交易日都凑不出来。云端虽有 `limit_history`，但它只有涨停名单，
 * **没有每天的涨跌幅**，补不出收益序列。真正能自算的只有存档这一列。
 *
 * 于是保留 65 天：60 天窗口 + 5 天缓冲（节假日/接口故障缺天）。
 * 每天 gzip 后约 50KB，65 天约 3MB，相对「波动率闸门永远不可得」这个代价可以接受。
 * 连板高度/晋级率的权威数据仍在云端，这里多留的天只喂波动率一项，不重复存储别的口径。
 *
 * ## 存哪、多大、留多久
 *
 * - 路径：`filesDir/market_days/YYYY-MM-DD.dat.gz`（一天一个文件，gzip 后约 50KB）。
 * - 格式：制表符文本，不依赖 JSON 库（单元测试里也能跑，不受 Android stub 影响）。
 * - 保留：65 个交易日（MAX_DAYS=65），超出由 [prune] 删最旧。
 *
 * ## 写入门槛（宁可少写，不要写脏）
 *
 * - 盘中：距上次写入 ≥ 60s 才写（心跳 30s 一轮，不必每轮都落盘）。
 * - 收盘后：只接受一次 `final=true` 的终版，之后当日文件不再被盘中数据覆盖。
 * - 快照为空、或全是停牌（`tradable` 为 0）时不写——那是接口故障，不是行情。
 */
object MarketDayArchive {

    private const val DIR_NAME = "market_days"
    private const val MAX_DAYS = 65
    private const val INTRADAY_MIN_INTERVAL_MS = 60_000L
    /**
     * 存档版本（逐版只**追加**列，不重排、不删除，旧文件永远能读）：
     *
     * - v1：`R code pct amount limitUp close`（缺「摸过涨停」，封板率不可得）
     * - v2：+ `touched`（摸过涨停 → 封板率历史的原料）
     * - v3：+ `high low open`（当根振幅 → 长下影/区间位置/多周期位置的前提）
     */
    private const val HEADER = "#v3"
    private val KNOWN_HEADERS = setOf("#v1", "#v2", "#v3")

    @Volatile private var root: File? = null
    @Volatile private var lastWriteAt = 0L
    @Volatile private var lastWriteDate = ""
    @Volatile private var lastWriteFinal = false

    /** 载入过的日期缓存（最近 40 天，避免每次重算都读文件）。 */
    private val cache = object : LinkedHashMap<String, DayMarketSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DayMarketSnapshot>?) =
            size > 40
    }

    /** App 启动时注入一次（`context.filesDir`）。没注入时所有写操作安全地变成 no-op。 */
    fun init(filesDir: File) {
        val dir = File(filesDir, DIR_NAME)
        if (runCatching { dir.mkdirs() }.isFailure) return
        root = dir
        synchronized(cache) { cache.clear() }
        lastWriteAt = 0L
        lastWriteDate = ""
        lastWriteFinal = false
    }

    val ready: Boolean get() = root != null

    private fun fileOf(date: String): File? = root?.let { File(it, "$date.dat.gz") }

    private fun fmt(v: Double?): String = when {
        v == null || v.isNaN() || v.isInfinite() -> "-"
        else -> String.format(java.util.Locale.US, "%.4f", v)
    }

    private fun num(s: String): Double? = if (s == "-" || s.isEmpty()) null else s.toDoubleOrNull()

    // ── 写 ────────────────────────────────────────────────

    /**
     * 落一份当日快照。
     *
     * @param final 是否收盘终版
     * @param now 当前时刻（测试可注入）
     * @return 是否真的写了
     */
    suspend fun capture(
        rows: List<LiveMarket.Row>,
        sectors: LocalSectors.Result? = null,
        now: Long = System.currentTimeMillis(),
        final: Boolean = false,
        minIntervalMs: Long = INTRADAY_MIN_INTERVAL_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = root ?: return@withContext false
        val date = CstTime.day(now)
        val tradable = rows.filter { it.tradable }
        // 全是停牌/没数据 → 接口故障，不是行情，绝不落盘污染历史
        if (tradable.size < 100) return@withContext false

        // 休市日不存档：周末/节假日 clist 仍然返回「上一交易日收盘」的旧盘口，
        // 若按「今天」写进去，就会凭空多出一个交易日，连板高度直接被算翻倍。
        if (MarketHours.isClosedDay(java.util.Date(now))) return@withContext false
        // 盘前（9:15 之前）拿到的也是上一交易日的收盘价，同样不能记成今天
        val dataDate = CstTime.day(tradable.maxOf { it.ts })
        if (dataDate != date) return@withContext false

        // 已经写过终版 → 之后任何写入都没意义（避免收盘后每轮轮询都重写一遍文件）
        if (lastWriteDate == date && lastWriteFinal) return@withContext false
        if (!final && lastWriteDate == date && now - lastWriteAt < minIntervalMs) return@withContext false

        val snapshot = DayMarketSnapshot(
            date = date,
            ts = now,
            final = final,
            entries = tradable.map {
                DayMarketSnapshot.Entry(
                    code = it.code,
                    pctChg = it.pctChg,
                    amount = it.amount,
                    limitUp = it.atLimitUp,
                    close = it.price,
                    touchedLimit = it.atLimitUp || it.brokenBoard,
                    high = it.high,
                    low = it.low,
                    open = it.open,   // f17 今开：长下影/区间位置要用当根振幅
                )
            },
            sectors = run {
                val fresh = sectors?.rows.orEmpty().map {
                    DayMarketSnapshot.SectorDay(it.name, it.chg1d, it.up, it.down, it.heat)
                }
                // 收盘终版可能拿不到板块聚合（那一路要额外映射表）；这时沿用盘中已存的板块段，
                // 不能因为「这一次没传」就把当天的板块历史抹掉
                if (fresh.isNotEmpty()) fresh else load(date)?.sectors.orEmpty()
            },
            hasTouchInfo = true,
            hasRangeInfo = true,   // 本次写的就是 v3：最高/最低/开盘都在
        )

        val ok = runCatching { writeToFile(File(dir, "$date.dat.gz"), snapshot) }.isSuccess
        if (ok) {
            lastWriteAt = now
            lastWriteDate = date
            lastWriteFinal = final
            synchronized(cache) { cache[date] = snapshot }
            runCatching { prune() }
        }
        ok
    }

    private fun writeToFile(file: File, snap: DayMarketSnapshot) {
        // 先写临时文件再原子替换：写到一半被杀进程不会留下半份历史
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { out ->
            val w = out.writer(Charsets.UTF_8)
            w.write("$HEADER\t${snap.date}\t${snap.ts}\t${if (snap.final) 1 else 0}\n")
            for (e in snap.entries) {
                w.write(
                    "R\t${e.code}\t${fmt(e.pctChg)}\t${fmt(e.amount)}\t${if (e.limitUp) 1 else 0}\t" +
                        "${fmt(e.close)}\t${if (e.touchedLimit) 1 else 0}\t" +
                        "${fmt(e.high)}\t${fmt(e.low)}\t${fmt(e.open)}\n",
                )
            }
            for (s in snap.sectors) {
                w.write("S\t${s.name}\t${fmt(s.chg1d)}\t${s.up}\t${s.down}\t${fmt(s.heat)}\n")
            }
            w.flush()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    // ── 读 ────────────────────────────────────────────────

    fun load(date: String): DayMarketSnapshot? {
        synchronized(cache) { cache[date] }?.let { return it }
        val f = fileOf(date) ?: return null
        if (!f.exists()) return null
        val snap = runCatching { readFromFile(f) }.getOrNull() ?: return null
        synchronized(cache) { cache[date] = snap }
        return snap
    }

    private fun readFromFile(file: File): DayMarketSnapshot? {
        var date = ""
        var ts = 0L
        var final = false
        var hasTouchInfo = true
        var hasRangeInfo = false
        val entries = ArrayList<DayMarketSnapshot.Entry>()
        val sectors = ArrayList<DayMarketSnapshot.SectorDay>()
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val p = line.split('\t')
                when (p[0]) {
                    in KNOWN_HEADERS -> {
                        hasTouchInfo = p[0] != "#v1"
                        hasRangeInfo = p[0] == "#v3"
                        date = p.getOrNull(1).orEmpty()
                        ts = p.getOrNull(2)?.toLongOrNull() ?: 0L
                        final = p.getOrNull(3) == "1"
                    }
                    "R" -> if (p.size >= 6) {
                        val limitUp = p[4] == "1"
                        entries += DayMarketSnapshot.Entry(
                            code = p[1],
                            pctChg = num(p[2]),
                            amount = num(p[3]),
                            limitUp = limitUp,
                            close = num(p[5]),
                            // v1 存档没有这一位：退化为「涨停即摸过」，于是那几天的封板率
                            // 只能是 100%（等于不可得），绝不能反过来假设「没摸过」
                            touchedLimit = if (p.size >= 7) p[6] == "1" else limitUp,
                            high = p.getOrNull(7)?.let(::num)?.takeIf { it > 0 },
                            low = p.getOrNull(8)?.let(::num)?.takeIf { it > 0 },
                            open = p.getOrNull(9)?.let(::num)?.takeIf { it > 0 },
                        )
                    }
                    "S" -> if (p.size >= 6) {
                        sectors += DayMarketSnapshot.SectorDay(
                            name = p[1],
                            chg1d = num(p[2]),
                            up = p[3].toIntOrNull() ?: 0,
                            down = p[4].toIntOrNull() ?: 0,
                            heat = num(p[5]) ?: 0.0,
                        )
                    }
                }
            }
        }
        if (date.isEmpty()) return null
        return DayMarketSnapshot(date, ts, final, entries, sectors, hasTouchInfo, hasRangeInfo)
    }

    /** 已存档的交易日，升序。 */
    fun dayKeys(): List<String> {
        val dir = root ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".dat.gz") }
            ?.map { it.name.removeSuffix(".dat.gz") }
            ?.filter { it.length == 10 }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 最近 [days] 个交易日的快照（含今天，升序）。
     *
     * 缺文件的日期直接跳过——这是「存量不足」，不是「当天没涨停」。
     */
    fun recent(days: Int = 30): List<DayMarketSnapshot> =
        dayKeys().takeLast(days).mapNotNull { load(it) }

    /** 交易日数量（UI 可以据此说明「连板高度要攒够 2 天才有」）。 */
    fun storedDays(): Int = dayKeys().size

    /** 超过保留上限时删最旧的。 */
    fun prune(keep: Int = MAX_DAYS) {
        val keys = dayKeys()
        if (keys.size <= keep) return
        val dir = root ?: return
        keys.dropLast(keep).forEach { d ->
            runCatching { File(dir, "$d.dat.gz").delete() }
            synchronized(cache) { cache.remove(d) }
        }
    }

    /** 测试/排障用：清掉全部存档。 */
    fun clearAll() {
        val dir = root ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        synchronized(cache) { cache.clear() }
        lastWriteAt = 0L
        lastWriteDate = ""
        lastWriteFinal = false
    }
}
