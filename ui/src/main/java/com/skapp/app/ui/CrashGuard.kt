package com.skapp.app.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录。
 *
 * 起因：线上报「打开决策页就闪退」，而这个 App 没有集成任何崩溃上报，
 * adb / logcat 又拿不到（用户设备没开 root/Shizuku），等于两眼一黑。
 *
 * 两段式设计（2026-10-27 加固）：
 *   1. 崩溃发生时（uncaught handler 里）只做最快的本地写 —— 此时进程随时被系统杀掉，
 *      碰 ContentResolver / MediaStore 很可能来不及完成，所以**不**在这里写 Download。
 *   2. 下次启动时由 exportPending() 把原始记录复制到 Download。
 *      这时进程健康、可以慢，用户不连电脑、不开 adb，用文件管理器就能把日志发出来。
 *
 * 记录内容刻意保持最小：时间、线程名、异常堆栈。不写设备标识、不写业务数据。
 * 每次崩溃独立成文件（不互相覆盖），导出成功后删除源文件，避免重复导出。
 *
 * 注意：`crash_last.txt` 是给红卡读的那一份，**不参与导出**。它同样以 "crash_" 开头，
 * 早先按前缀匹配筛选时被误当成原始记录导出并删除，导致红卡永远为空（2026-10-27 修复）。
 */
object CrashGuard {

    private const val FILE_NAME = "crash_last.txt"
    private const val PREFIX = "crash_"
    private const val TAG = "CrashGuard"

    /**
     * 原始崩溃记录的文件名必须严格匹配 `crash_yyyyMMdd_HHmmss.txt`（与 [write] 的 stamp 格式对应）。
     *
     * 不能用 `startsWith(PREFIX)` 判断：`crash_last.txt` 也以 "crash_" 开头，
     * 会把红卡那份一起导出后 delete() 掉。改 stamp 格式时这里必须同步。
     */
    private val ORIGINAL_NAME = Regex("^crash_\\d{8}_\\d{6}\\.txt$")

    /** 最近一次崩溃的导出结果，供诊断页/红卡展示，避免用户找不到文件 */
    @Volatile
    var lastExportNote: String? = null
        private set

    /** 在 Application/Activity 启动最早处安装；重复调用无副作用 */
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 自己写盘失败绝不能掩盖原始崩溃：全部包在 runCatching 里
            runCatching { write(app, thread?.name ?: "?", error) }
                .onFailure { Log.w(TAG, "崩溃记录写入失败: ${it.message}") }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, threadName: String, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val body = buildString {
            append(stamp).append('\n')
            append("thread: ").append(threadName).append('\n')
            append(Log.getStackTraceString(error))
        }
        // 独立文件：连续闪退几次也不会互相覆盖，能看出是不是同一个栈
        runCatching { File(context.filesDir, "$PREFIX$stamp.txt").writeText(body) }
            .onFailure { Log.w(TAG, "崩溃原始文件写入失败: ${it.message}") }
        // 红卡读这一份（只保留最近一次）；写失败只记日志，不能连累上面那份原始记录
        runCatching { File(context.filesDir, FILE_NAME).writeText(body) }
            .onFailure { Log.w(TAG, "红卡崩溃记录写入失败: ${it.message}") }
    }

    /**
     * 下次启动调用：把上次崩溃的原始记录导出到 Download。
     *
     * API 29+ 走 MediaStore（公共 Download 目录，无需存储权限）；
     * API 26~28 退到应用外部目录（Android/data/<pkg>/files/Download）——
     * 那里不需要权限，文件管理器也能进得去。
     */
    fun exportPending(context: Context) {
        runCatching {
            // 只认严格时间戳命名的原始记录，并显式排除红卡那份 —— 否则它会被导出后删掉
            val files = context.filesDir.listFiles { f ->
                f.name != FILE_NAME && ORIGINAL_NAME.matches(f.name) && f.length() > 0L
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
                "崩溃日志已记录，但导出到 Download 失败"
            } else {
                "崩溃日志已导出到 Download/" + exported.joinToString("、")
            }
        }.onFailure { Log.w(TAG, "导出崩溃日志失败: ${it.message}") }
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

    /** 读取上次崩溃现场；没有则返回 null */
    fun lastCrash(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists() || f.length() == 0L) null else f.readText()
    }.getOrNull()

    /** 用户看完后清掉，避免下次启动又弹（原始 crash_*.txt 保留，等导出） */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** 给诊断界面用：只取首行摘要（时间 + 异常类型 + 第一句） */
    fun summary(context: Context): String? {
        val raw = lastCrash(context) ?: return null
        val lines = raw.lines()
        val time = lines.getOrNull(0).orEmpty()
        val exceptionLine = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()
        return listOfNotNull(time, exceptionLine).joinToString(" · ").ifBlank { "有崩溃记录" }
    }
}
