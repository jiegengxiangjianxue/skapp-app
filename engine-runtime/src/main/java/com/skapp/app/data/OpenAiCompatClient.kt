package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容接口客户端 —— 用户自填 Base URL 的兜底通道。
 *
 * 用途：GitCode / GitHub Models 都不可用（限流、欠费、地区限制）时，
 * 用自己搭的中转或私有网关接着做分析。走标准 `/chat/completions` 协议，
 * Base URL 填到 `/v1` 一级或直接填完整路径都能认。
 *
 * 与另外两个 client 保持一致：凭据由 ViewModel 从 SharedPreferences 读出后注入，
 * 本类不读存储、不内置任何地址与密钥。
 */
object OpenAiCompatClient {

    private const val TIMEOUT = 90_000
    private const val DEFAULT_MODEL = "gpt-4o-mini"

    /** 最近一次调用的 HTTP 状态码（0=未请求/网络异常），供 [AiRouter] 识别 429 */
    @Volatile
    var lastHttpCode: Int = 0
        private set

    private var baseUrl: String? = null
    private var apiKey: String? = null
    private var model: String = DEFAULT_MODEL

    fun configure(baseUrl: String?, apiKey: String?, model: String?) {
        this.baseUrl = baseUrl?.trim()?.takeIf { it.isNotBlank() }
        this.apiKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
        this.model = model?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    }

    fun isConfigured(): Boolean = !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank()

    fun modelName(): String = model

    /**
     * 单次 chat。返回 null 表示失败；429 时 [lastHttpCode]==429，由 [AiRouter] 决定是否冷却。
     */
    fun rawChat(messages: List<Pair<String, String>>, overrideModel: String? = null): String? {
        val base = baseUrl ?: return null
        val key = apiKey ?: return null
        val endpoint = if (base.endsWith("/chat/completions")) {
            base
        } else {
            base.trimEnd('/') + "/chat/completions"
        }
        lastHttpCode = 0
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("model", overrideModel ?: model)
                put("temperature", 0.3)
                put(
                    "messages",
                    JSONArray().apply {
                        messages.forEach { (role, content) ->
                            put(
                                JSONObject().apply {
                                    put("role", role)
                                    put("content", content)
                                },
                            )
                        }
                    },
                )
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            lastHttpCode = code
            if (code != HttpURLConnection.HTTP_OK) return null
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
