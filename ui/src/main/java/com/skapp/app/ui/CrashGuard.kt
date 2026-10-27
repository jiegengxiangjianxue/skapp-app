package com.skapp.app.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录。
 *
 * 起因：线上报「容易闪退，原因未知」，而这个 App 没有集成任何崩溃上报，
 * adb / logcat 又拿不到（用户设备没开 root/Shizuku），等于两眼一黑。
 * 这里只做一件事：把最后一次未捕获异常的堆栈落到应用私有目录，
 * 下次启动时由诊断入口读出来——不需要网络、不需要三方 SDK、不收集任何用户数据。
 *
 * 记录内容刻意保持最小：时间、线程名、异常堆栈。不写设备标识、不写业务数据。
 * 只保留最近一次（覆盖写），不做累积上传。
 */
object CrashGuard {

    private const val FILE_NAME = "crash_last.txt"
    private const val TAG = "CrashGuard"

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
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val body = buildString {
            append(ts).append('\n')
            append("thread: ").append(threadName).append('\n')
            append(Log.getStackTraceString(error))
        }
        File(context.filesDir, FILE_NAME).writeText(body)
    }

    /** 读取上次崩溃现场；没有则返回 null */
    fun lastCrash(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists() || f.length() == 0L) null else f.readText()
    }.getOrNull()

    /** 用户看完后清掉，避免下次启动又弹 */
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
