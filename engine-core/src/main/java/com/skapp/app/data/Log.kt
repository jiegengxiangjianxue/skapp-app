package com.skapp.app.data

/**
 * 纯 JVM 侧日志出口。
 *
 * engine-core / net / algo / runtime 都是 kotlin.jvm 模块，不链接 Android，
 * 数据层里出现 android.util.Log 会直接编不过；同时这些代码要能在无 Android 的
 * 单测里跑，所以统一走 stderr（Android 运行时同样会收进 logcat 的 System.err）。
 * 与 com.skapp.app.data 同包，调用方写 `Log.e(tag, msg)` 即可，无需 import。
 */
object Log {
    fun e(tag: String, msg: String, t: Throwable? = null) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }

    fun w(tag: String, msg: String) {
        System.err.println("W/$tag: $msg")
    }

    fun d(tag: String, msg: String) {
        System.err.println("D/$tag: $msg")
    }
}
