package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 本地日级快照存档 —— 让「连板高度 / 晋级率 / 板块历史动量 / 长周期 RPS」从不可得变成可算。
 *
 * ## 为什么必须存
 *
 * 东财 clist 只给「此刻」。昨天谁涨停、前天谁涨停，一旦没存下来就再也补不回来，
 * 于是引擎池里的 `height / promo / firstBoard / ge2` 只能永远写 -1 并标「不可得」。
 * 这不是算法缺，是数据缺。存档就是这个缺口的补丁：**每天存一份，第二天起就能算**。
 *
 * ## 存哪、多大、留多久
 *
 * - 路径：`filesDir/market_days/YYYY-MM-DD.dat.gz`（一天一个文件，gzip 后约 50KB）。
 * - 格式：制表符文本，不依赖 JSON 库（单元测试里也能跑，不受 Android stub 影响）。
 * - 保留：默认 420 个交易日（够覆盖一年半的 RPS250 与年度比较），超出按日期删最旧。
 *
 * ## 写入门槛（宁可少写，不要写脏）
 *
 * - 盘中：距上次写入 ≥ 60s 才写（心跳 30s 一轮，不必每轮都落盘）。
 * - 收盘后：只接受一次 `final=true` 的终版，之后当日文件不再被盘中数据覆盖。
 * - 快照为空、或全是停牌（`tradable` 为 0）时不写——那是接口故障，不是行情。
 */
object MarketDayArchive {

    private const val DIR_NAME = "market_days"
    private const val MAX_DAYS = 420
    private const val INTRADAY_MIN_INTERVAL_MS = 60_000L
    private const val HEADER = "#v1"

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
                w.write("R\t${e.code}\t${fmt(e.pctChg)}\t${fmt(e.amount)}\t${if (e.limitUp) 1 else 0}\t${fmt(e.close)}\n")
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
        val entries = ArrayList<DayMarketSnapshot.Entry>()
        val sectors = ArrayList<DayMarketSnapshot.SectorDay>()
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val p = line.split('\t')
                when (p[0]) {
                    HEADER -> {
                        date = p.getOrNull(1).orEmpty()
                        ts = p.getOrNull(2)?.toLongOrNull() ?: 0L
                        final = p.getOrNull(3) == "1"
                    }
                    "R" -> if (p.size >= 6) {
                        entries += DayMarketSnapshot.Entry(
                            code = p[1],
                            pctChg = num(p[2]),
                            amount = num(p[3]),
                            limitUp = p[4] == "1",
                            close = num(p[5]),
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
        return DayMarketSnapshot(date, ts, final, entries, sectors)
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
