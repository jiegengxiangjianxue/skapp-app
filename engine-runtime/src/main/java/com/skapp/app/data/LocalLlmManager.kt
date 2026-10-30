package com.skapp.app.data

import com.skapp.app.llm.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 本地 LLM 模型管理器 (2026-10-24; 2026-10-30 修掉合并文件名不一致、单文件死路径、
 * Kotlin object 反射调用三处缺陷)。
 *
 * 模型分发仓: github jiegengxiangjianxue/llm-models — Qwen3-0.6B Q4_K_M GGUF,
 * 受 GitHub 单文件 100MB 限制切成 24 片 (qwen06b_q4.part-00..23), 下载后按序拼接并校验 sha256。
 * 尺寸与 sha256 取自仓内 MANIFEST.json: 484,220,320 字节 / 9acfc1e0...。
 * 推理优先走内置 llama.cpp JNI; 未注入 libllama_jni.so 时 load() 返回 false, 由 UI 引导外部引擎。
 * 不内置任何 token; 分片是公开仓 raw 链接, 无需鉴权。
 */
object LocalLlmManager {

    data class ModelInfo(val name: String, val file: String, val bytes: Long, val desc: String)

    val CATALOG = listOf(
        ModelInfo(
            "Qwen3-0.6B Q4_K_M (手机端首选)",
            "Qwen3-0.6B-Q4_K_M.gguf",
            484_220_320L,
            "0.6B Q4量化, 16GB机型流畅, 盘中本地问答"
        ),
    )

    private const val RAW_BASE = "https://raw.githubusercontent.com/jiegengxiangjianxue/llm-models/main/models"
    private const val SHA256 = "9acfc1e001311f34b4252001b626f2e466d592a42065f66571bff3790d4e1b14"
    private const val SHARD_BYTES = 20_971_520L
    private val PARTS = (0..23).map { "qwen06b_q4.part-%02d".format(it) }

    fun modelFile(dir: File, m: ModelInfo): File = File(dir, "llm/${m.file}")

    fun isDownloaded(dir: File, m: ModelInfo): Boolean {
        val f = modelFile(dir, m)
        return f.exists() && f.length() == m.bytes
    }

    /** 下载全部分片(逐片断点续传) → 按序拼接 → 校验 sha256。onProgress: 0..100 */
    suspend fun download(dir: File, m: ModelInfo, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val outDir = File(dir, "llm").apply { mkdirs() }
                val dst = modelFile(dir, m)
                if (isDownloaded(dir, m)) return@withContext Result.success(dst)

                for ((idx, part) in PARTS.withIndex()) {
                    val expected = if (idx == PARTS.size - 1) m.bytes - SHARD_BYTES * idx else SHARD_BYTES
                    val shard = File(outDir, part)
                    if (shard.length() != expected) fetchShard(part, shard, expected, idx, onProgress)
                }

                val tmp = File(dst.absolutePath + ".merge")
                java.io.FileOutputStream(tmp).use { fos ->
                    PARTS.forEach { p -> File(outDir, p).inputStream().use { it.copyTo(fos) } }
                }
                check(tmp.length() == m.bytes) { "merged size ${tmp.length()} != ${m.bytes}" }
                check(sha256(tmp) == SHA256) { "sha256 mismatch" }
                if (dst.exists()) dst.delete()
                check(tmp.renameTo(dst)) { "rename merged" }
                PARTS.forEach { File(outDir, it).delete() }
                onProgress(100)
                Result.success(dst)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun fetchShard(name: String, shard: File, expected: Long, idx: Int, onProgress: (Int) -> Unit) {
        val tmp = File(shard.absolutePath + ".part")
        if (tmp.length() > expected) tmp.delete()
        val start = if (tmp.exists()) tmp.length() else 0L
        val conn = URL("$RAW_BASE/$name").openConnection() as HttpURLConnection
        if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            conn.inputStream.use { ins ->
                java.io.FileOutputStream(tmp, true).use { fos ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    while (ins.read(buf).also { n = it } > 0) fos.write(buf, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
        check(tmp.length() == expected) { "part $idx size ${tmp.length()} != $expected" }
        if (shard.exists()) shard.delete()
        check(tmp.renameTo(shard)) { "rename part $idx" }
        onProgress((idx + 1) * 99 / PARTS.size)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 模型完整且 JNI 就绪才为 true; 未注入 libllama_jni.so 时 false, 由 UI 引导外部引擎 */
    fun localInferAvailable(dir: File, m: ModelInfo): Boolean =
        isDownloaded(dir, m) && LlamaBridge.load(modelFile(dir, m).absolutePath)

    fun chat(dir: File, m: ModelInfo, systemPrompt: String, userMsg: String): String? =
        if (localInferAvailable(dir, m)) LlamaBridge.chat(systemPrompt, userMsg) else null

    fun release() = LlamaBridge.unload()
}
