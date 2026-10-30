package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Models 免费 AI 客户端 —— 云端 AI 的第二来源（GitCode 的备份）。
 *
 * 端点：https://models.github.ai/inference/chat/completions（Bearer GitHub PAT）
 * 深度模型：[DEEP_MODEL]（主链路）；轻量模型：[LIGHT_MODEL]（快答/兜底）
 *
 * 用户决策：本地 LLM 已停，全走云端 GitCode + GitHub 免费 AI，双源互为备份。
 * GitCode 仍是第一优先（红线不替换），GitHub Models 只在 GitCode 失败/429/超时时接管
 * （见 [AiRouter]）。
 *
 * 结构与 [GitCodeAiClient] 对齐：object 单例 + setToken/isConfigured/chat，
 * HttpURLConnection + org.json，同步方法（由 [AiRouter] 包到 IO 调度器）。
 */
object GitHubModelsClient {

    const val ENDPOINT = "https://models.github.ai/inference/chat/completions"
    /** 深度/主链路模型 */
    const val DEEP_MODEL = "gpt-4o"
    /** 轻量模型（快答/深度失败时兜底） */
    const val LIGHT_MODEL = "llama-3.1-8b-instant"
    private const val TIMEOUT = 90000

    /** token 由用户在 app 设置页填入（SharedPreferences key=github_token，由 AiRouter/SkappViewModel 注入） */
    private var token: String? = null

    fun setToken(t: String?) {
        token = t?.takeIf { it.isNotBlank() }
    }

    fun isConfigured(): Boolean = !token.isNullOrBlank()

    /** 最近一次调用的 HTTP 状态码（0=未请求/网络异常），供 AiRouter 识别 429 */
    @Volatile
    var lastHttpCode: Int = 0
        private set

    /**
     * 单次 chat 调用。返回 null 表示失败（非 200/异常），
     * 429/超时等也会返回 null——是否触发冷却由 [AiRouter] 用 [lastHttpCode] 判断。
     */
    fun chat(messages: List<Pair<String, String>>, model: String): String? {
        val tok = token ?: return null
        lastHttpCode = 0
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    messages.forEach { (role, content) ->
                        put(JSONObject().apply {
                            put("role", role)
                            put("content", content)
                        })
                    }
                })
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            lastHttpCode = code
            if (code != HttpURLConnection.HTTP_OK) return null
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(resp).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 深度模型 → 轻量模型依次尝试 */
    fun chatDeep(messages: List<Pair<String, String>>): String? =
        chat(messages, DEEP_MODEL) ?: chat(messages, LIGHT_MODEL)

    fun chatLight(messages: List<Pair<String, String>>): String? =
        chat(messages, LIGHT_MODEL)
}
