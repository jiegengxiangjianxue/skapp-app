package com.skapp.app.llm

/**
 * llama.cpp JNI 桥 (骨架) — CI 编译通过; 运行时需 libllama_jni.so (Gradle task 从
 * llama.cpp android 预编译产物注入, 未注入时 init 返回 false, app 引导外部引擎)。
 * system prompt 固化为金融语境: "你是A股盘面分析助手, 只基于给定数据回答, 不编造。"
 */
object LlamaBridge {
    init { try { System.loadLibrary("llama_jni") } catch (e: UnsatisfiedLinkError) { /* graceful */ } }

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeChat(system: String, user: String, maxTokens: Int): String
    private external fun nativeFree()

    private var loaded = false

    fun load(modelPath: String): Boolean {
        if (loaded) return true
        loaded = try { nativeLoad(modelPath) } catch (e: UnsatisfiedLinkError) { false }
        return loaded
    }

    fun chat(system: String, user: String, maxTokens: Int = 256): String? =
        if (loaded) try { nativeChat(system, user, maxTokens) } catch (e: Exception) { null } else null

    fun unload() { if (loaded) { nativeFree(); loaded = false } }
}
