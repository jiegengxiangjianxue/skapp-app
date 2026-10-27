package com.skapp.app.ui

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.skapp.app.data.health.ProcessHealthCore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行健康守卫（vc19）：补上 [CrashGuard] 抓不到的那三类死亡。
 *
 * ## 与 CrashGuard 的分工
 *
 * [CrashGuard] 走 `Thread.setDefaultUncaughtExceptionHandler`，只有「Java 层抛出且没人接」
 * 的异常才经过它。以下三类**完全不产生异常**，进程直接消失，CrashGuard 一条都不会留：
 *
 *   - **ANR**：主线程卡住 → 系统弹框 / 强杀；
 *   - **低内存被 LMK 回收** → SIGKILL；
 *   - **native 崩溃（SIGSEGV）** → Java 层不知情。
 *
 * 本守卫因此不依赖异常，只依赖两条与异常无关的信号：
 *
 *   1. **主线程心跳**：主线程每 [ProcessHealthCore.BEAT_INTERVAL_MS] 自增计数；
 *      `skapp-health-watchdog` 线程盯着计数，超过 [ProcessHealthCore.STALL_THRESHOLD_MS]
 *      没跳就判定停顿，**当场**把主线程栈写盘（不等进程被杀——ANR 被杀后什么都写不出来）。
 *   2. **内存水位**：`skapp-health-memory` 线程每 30s 采样堆占用 / native 占用 /
 *      系统 `lowMemory` 标记，在 OOM 或 LMK 动手前留痕。
 *
 * ## 文件通道刻意与崩溃记录隔离
 *
 * 记录写在 `filesDir/health_<yyyyMMdd_HHmmss_SSS>.txt`，导出到 Download 时叫
 * `skapp_health_*.txt`。**不**复用 CrashGuard 的 `crash_` 前缀与导出循环：
 * 2026-10-27 出过一次事故——按前缀匹配把红卡那份 `crash_last.txt` 也导出后删掉了。
 * 健康记录和崩溃记录是两条独立证据链，任何一条都不该有能力删掉另一条的文件，
 * 所以这里的 `saveToDownload` 是有意复制的一份，而不是调用 CrashGuard 的私有实现。
 *
 * ## 自身的可靠性
 *
 * 守卫不能成为新的崩溃源：
 *   - 两个线程都是 daemon，不拖住进程退出；
 *   - 每个循环体整段包 `runCatching` / `catch (Throwable)`，写盘失败只记日志；
 *   - 记录文件上限 [MAX_FILES] 个，超了删最老的，避免卡死时刷爆存储。
 */
object HealthGuard {

    private const val TAG = "HealthGuard"

    /** 健康记录文件名前缀。必须与 [CrashGuard] 的 `crash_` 互不重叠，见类注释。 */
    private const val PREFIX = "health_"

    /** 最近一条健康记录（只保留最新一份），供诊断页直接展示。 */
    private const val LAST_FILE = "health_last.txt"

    /** 严格匹配自己写出的文件名；不匹配的一律不导出、不删除。 */
    private val ORIGINAL_NAME = Regex("^health_\\d{8}_\\d{6}_\\d{3}\\.txt$")

    /** 记录文件上限：卡死时每 10s 一条，20 个足够覆盖一整段最坏情况。 */
    private const val MAX_FILES = 20

    /** 首次内存采样延迟：避开启动期那波解析高峰，否则每次冷启动都会记一条无意义告警。 */
    private const val MEM_FIRST_SAMPLE_MS = 8_000L

    /** 内存采样间隔。 */
    private const val MEM_SAMPLE_INTERVAL_MS = 30_000L

    /** 同一水位的复报间隔：水位没上升时不重复记，避免每 30s 一个文件。 */
    private const val MEM_REPEAT_MS = 10 * 60 * 1000L

    /** 是否已安装；重复调用无副作用。 */
    @Volatile
    private var installed = false

    /** 主线程心跳计数，唯一被主线程写的状态。 */
    private val beats = AtomicLong(0)

    private val stallEvents = AtomicInteger(0)
    private val memEvents = AtomicInteger(0)

    @Volatile
    private var lastStallSummary: String? = null

    /** 最近一次导出结果，供诊断页展示，避免用户找不到文件。 */
    @Volatile
    var lastExportNote: String? = null
        private set

    /**
     * 在 Activity/Application 最早处安装。
     *
     * 必须在**主线程**调用：心跳是 `Handler(mainLooper).postDelayed` 自循环，
     * 这也是它能反映主线程真实调度情况的原因——如果主线程被卡住，回调就不会跑，
     * 计数不涨，watchdog 才能看出停顿。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        runCatching {
            startMainThreadBeat()
            startWatchdog(app)
            startMemorySampler(app)
        }.onFailure { Log.w(TAG, "守卫安装失败: ${it.message}") }
    }

    // ── 1. 主线程心跳 ────────────────────────────────────────────────

    private fun startMainThreadBeat() {
        val handler = Handler(Looper.getMainLooper())
        val beat = object : Runnable {
            override fun run() {
                beats.incrementAndGet()
                handler.postDelayed(this, ProcessHealthCore.BEAT_INTERVAL_MS)
            }
        }
        handler.post(beat)
    }

    // ── 2. 停顿看门狗 ────────────────────────────────────────────────

    private fun startWatchdog(app: Context) {
        Thread({
            var lastSeen = beats.get()
            var lastChangeAt = SystemClock.elapsedRealtime()
            var recorded = 0
            while (true) {
                try {
                    Thread.sleep(ProcessHealthCore.BEAT_INTERVAL_MS)
                    val now = SystemClock.elapsedRealtime()
                    val seen = beats.get()
                    if (seen != lastSeen) {
                        // 心跳恢复：这段停顿结束，去重计数归零
                        lastSeen = seen
                        lastChangeAt = now
                        recorded = 0
                        continue
                    }
                    val decision = ProcessHealthCore.decideStall(now - lastChangeAt, recorded)
                    if (!decision.record) continue
                    recorded = decision.episode
                    captureStall(app, decision)
                } catch (t: Throwable) {
                    // 看门狗自己出错绝不能连累 App
                    Log.w(TAG, "watchdog 循环异常: ${t.message}")
                }
            }
        }, "skapp-health-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 抓主线程栈并落盘。
     *
     * 注意是在 **watchdog 线程**上写：主线程此刻正卡着，让它写等于把现场一起丢了。
     * `thread.stackTrace` 是快照式读取，对运行中的线程是安全的。
     */
    private fun captureStall(app: Context, decision: ProcessHealthCore.StallDecision) {
        runCatching {
            val frames = runCatching {
                Looper.getMainLooper().thread.stackTrace.map { "at $it" }
            }.getOrDefault(emptyList())
            val stamp = stamp()
            writeRecord(
                app,
                stamp,
                ProcessHealthCore.formatStallReport(stamp, decision, frames = frames),
            )
            stallEvents.incrementAndGet()
            lastStallSummary = "$stamp 主线程停顿 ${decision.blockedMs}ms（第 ${decision.episode} 次记录）"
        }.onFailure { Log.w(TAG, "停顿记录失败: ${it.message}") }
    }

    // ── 3. 内存水位采样 ──────────────────────────────────────────────

    private fun startMemorySampler(app: Context) {
        Thread({
            var lastLevel = ProcessHealthCore.MemLevel.OK
            var lastWriteAt = 0L
            var firstRound = true
            while (true) {
                try {
                    Thread.sleep(if (firstRound) MEM_FIRST_SAMPLE_MS else MEM_SAMPLE_INTERVAL_MS)
                    firstRound = false
                    val now = SystemClock.elapsedRealtime()
                    val snap = snapshot(app)
                    val level = ProcessHealthCore.classifyMemory(snap.used, snap.max, snap.lowMemory)
                    if (level == ProcessHealthCore.MemLevel.OK) {
                        lastLevel = level
                        continue
                    }
                    // 只在「水位上升」或「同一水位已过复报间隔」时记，避免每 30s 一条
                    val escalated = level.ordinal > lastLevel.ordinal
                    if (!escalated && now - lastWriteAt < MEM_REPEAT_MS) continue
                    val stamp = stamp()
                    writeRecord(
                        app,
                        stamp,
                        ProcessHealthCore.formatMemoryReport(
                            stamp = stamp,
                            level = level,
                            usedBytes = snap.used,
                            maxBytes = snap.max,
                            nativeBytes = snap.native,
                            systemLowMemory = snap.lowMemory,
                            availBytes = snap.avail,
                            thresholdBytes = snap.threshold,
                        ),
                    )
                    memEvents.incrementAndGet()
                    lastLevel = level
                    lastWriteAt = now
                } catch (t: Throwable) {
                    Log.w(TAG, "内存采样异常: ${t.message}")
                }
            }
        }, "skapp-health-memory").apply {
            isDaemon = true
            start()
        }
    }

    private class MemSnapshot(
        val used: Long,
        val max: Long,
        val native: Long,
        val avail: Long,
        val threshold: Long,
        val lowMemory: Boolean,
    )

    private fun snapshot(app: Context): MemSnapshot {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        var avail = 0L
        var threshold = 0L
        var low = false
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            avail = info.availMem
            threshold = info.threshold
            low = info.lowMemory
        }.onFailure { Log.w(TAG, "MemoryInfo 读取失败: ${it.message}") }
        return MemSnapshot(used, max, native, avail, threshold, low)
    }

    // ── 4. 落盘与导出 ────────────────────────────────────────────────

    /** 文件名精确到毫秒：同一秒内连续两次记录（停顿 + 内存）不会互相覆盖。 */
    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun writeRecord(app: Context, stamp: String, body: String) {
        runCatching { File(app.filesDir, "$PREFIX$stamp.txt").writeText(body) }
            .onFailure { Log.w(TAG, "健康记录写入失败: ${it.message}") }
        // 诊断页展示用的最新一份；写失败只记日志，不影响上面那条原始记录
        runCatching { File(app.filesDir, LAST_FILE).writeText(body) }
            .onFailure { Log.w(TAG, "健康摘要写入失败: ${it.message}") }
        prune(app)
    }

    /** 只删自己前缀且符合时间戳格式的文件，永远不碰 `crash_*` / `crash_last.txt`。 */
    private fun prune(app: Context) {
        runCatching {
            val files = app.filesDir.listFiles { f -> ORIGINAL_NAME.matches(f.name) } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.name }.dropLast(MAX_FILES).forEach { runCatching { it.delete() } }
        }.onFailure { Log.w(TAG, "清理健康记录失败: ${it.message}") }
    }

    /**
     * 下次启动调用：把健康记录导出到 Download，用户不连电脑就能把文件发出来。
     *
     * 与 [CrashGuard.exportPending] 同一套思路（API 29+ 走 MediaStore，26~28 退到外部目录），
     * 但是**独立的循环、独立的文件名、独立的失败处理**——两条证据链互不删除，见类注释。
     */
    fun exportPending(context: Context) {
        runCatching {
            val files = context.filesDir.listFiles { f ->
                ORIGINAL_NAME.matches(f.name) && f.length() > 0L
            } ?: return
            if (files.isEmpty()) return
            val exported = ArrayList<String>()
            files.sortedBy { it.name }.forEach { f ->
                if (saveToDownload(context, "skapp_${f.name}", f.readText())) {
                    exported.add("skapp_${f.name}")
                    runCatching { f.delete() }
                }
            }
            lastExportNote = if (exported.isEmpty()) {
                "健康日志已记录，但导出到 Download 失败"
            } else {
                "健康日志已导出到 Download/" + exported.joinToString("、")
            }
        }.onFailure { Log.w(TAG, "导出健康日志失败: ${it.message}") }
    }

    private fun saveToDownload(context: Context, name: String, body: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                ?: return@runCatching false
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            File(dir, name).writeText(body)
            true
        }
    }.getOrDefault(false)

    // ── 5. 诊断页接口 ────────────────────────────────────────────────

    /** 一行式状态：心跳是否在跑、累计记录数、当前内存水位。诊断页每 2s 刷一次。 */
    fun status(context: Context): String {
        val snap = runCatching { snapshot(context.applicationContext) }.getOrNull()
        val level = snap?.let { ProcessHealthCore.classifyMemory(it.used, it.max, it.lowMemory) }
        return buildString {
            append("守卫：").append(if (installed) "运行中" else "未安装")
            append(" · 停顿记录 ").append(stallEvents.get()).append(" 次")
            append(" · 内存告警 ").append(memEvents.get()).append(" 次")
            if (snap != null && level != null) {
                append('\n')
                append("堆 ").append(mb(snap.used)).append("/").append(mb(snap.max)).append(" MB")
                append(" · native ").append(mb(snap.native)).append(" MB")
                append(" · 系统可用 ").append(mb(snap.avail)).append(" MB")
                append(" · 低内存 ").append(snap.lowMemory)
                append(" · 水位 ").append(level.name)
            }
            lastStallSummary?.let { append("\n最近停顿：").append(it) }
        }
    }

    /** 最近一条健康记录原文（诊断页/排查时直接看，不用等导出）。 */
    fun lastRecord(context: Context): String? = runCatching {
        val f = File(context.filesDir, LAST_FILE)
        if (!f.exists() || f.length() == 0L) null else f.readText()
    }.getOrNull()

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1048576.0)
}
