package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * GitHub 仓库文件读写：端上把「消息库」「开盘推股历史」归档到仓库的唯一入口。
 *
 * 为什么不扩展 [Http]：那是刻意只做 GET 的收敛入口（gzip 解压那类坑都在里面），
 * 把 PUT 塞进去会毁掉它的约定。而且这里的失败必须让调用方看见——归档写不进去
 * 要进队列下次重试，不能像探测型 GET 那样静默返回 null。
 *
 * 写入用 `sha` 条件更新：同一文件可能被云端 CI 和端上同时改，不带 sha 会直接覆盖掉
 * 对方刚写的轮次。冲突（409）交给调用方重试，本类不自动 merge。
 */
object GitHubRepoWriter {

    private var token: String? = null
    private var owner: String = "qwerzxcva"
    private var repo: String = "stocks-ai-view"
    private var branch: String = "main"

    /** 路径里可能有中文目录（开盘推股历史数据 / 消息数据库），统一编码一次。 */
    private fun apiUrl(path: String): String {
        val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://api.github.com/repos/$owner/$repo/contents/$encoded?ref=$branch"
    }

    fun configure(token: String?, owner: String? = null, repo: String? = null, branch: String? = null) {
        this.token = token?.takeIf { it.isNotBlank() }
        owner?.let { this.owner = it }
        repo?.let { this.repo = it }
        branch?.let { this.branch = it }
    }

    fun isConfigured(): Boolean = !token.isNullOrBlank()

    /** 读到的文件：内容 + 用于条件更新的 sha。 */
    data class RemoteFile(val path: String, val contentType: String?, val sha: String, val body: String)

    sealed class WriteResult {
        data class Ok(val sha: String?) : WriteResult()
        /** 远端已变（sha 不匹配），调用方应重读后重试。 */
        data object Conflict : WriteResult()
        data class Failed(val message: String) : WriteResult()
    }

    /** 读文件；不存在返回 null（404 与网络失败要区分：网络失败抛给调用方，不当作「没有」）。 */
    suspend fun readFile(path: String): RemoteFile? = withContext(Dispatchers.IO) {
        val conn = (URL(apiUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            when (conn.responseCode) {
                in 200..299 -> {
                    val payload = JSONObject(conn.inputStream.bufferedReader().readText())
                    val raw = payload.optString("content").replace("\n", "")
                    val decoded = String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
                    RemoteFile(
                        path = path,
                        contentType = payload.optString("type"),
                        sha = payload.optString("sha"),
                        body = decoded,
                    )
                }
                404 -> null
                else -> throw java.io.IOException("GitHub GET $path HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 列目录下的文件名（目录可能是中文名，和 [readFile] 走同一个 [apiUrl] 编码）。
     *
     * 目录不存在返回空表；其它非 2xx 抛给调用方，避免「网络挂了」被当成「没有历史」。
     */
    suspend fun listDir(path: String): List<String> = withContext(Dispatchers.IO) {
        val conn = (URL(apiUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            when (conn.responseCode) {
                in 200..299 -> {
                    val arr = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                    }
                }
                404 -> emptyList()
                else -> throw java.io.IOException("GitHub LIST $path HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 写文件。`sha` 传 null 表示新建；远端已存在时会 409，调用方重读再写。
     */
    suspend fun writeFile(
        path: String,
        body: String,
        message: String,
        sha: String? = null,
    ): WriteResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)))
            put("branch", branch)
            sha?.let { put("sha", it) }
        }
        val conn = (URL(apiUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", RemoteSources.UA_ANDROID)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                in 200..299 -> {
                    val reply = JSONObject(conn.inputStream.bufferedReader().readText())
                    WriteResult.Ok(reply.optJSONObject("content")?.optString("sha"))
                }
                409, 422 -> WriteResult.Conflict
                else -> {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
                    WriteResult.Failed("HTTP ${conn.responseCode} $err")
                }
            }
        } catch (e: Exception) {
            WriteResult.Failed(e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 读-合并-写，最多重试 [attempts] 次。
     *
     * @param merge 拿远端已有内容（null 表示文件还不存在）返回合并后的新内容；
     *              返回 null 表示「这次不需要写」，直接算成功。
     */
    suspend fun upsert(
        path: String,
        message: String,
        attempts: Int = 3,
        merge: (RemoteFile?) -> String?,
    ): WriteResult {
        var last: WriteResult = WriteResult.Failed("not attempted")
        repeat(attempts) {
            val current = try {
                readFile(path)
            } catch (e: Exception) {
                return WriteResult.Failed("read: " + (e.message ?: e.javaClass.simpleName))
            }
            val merged = merge(current) ?: return WriteResult.Ok(current?.sha)
            when (val r = writeFile(path, merged, message, current?.sha)) {
                is WriteResult.Ok -> return r
                is WriteResult.Conflict -> last = r
                is WriteResult.Failed -> return r
            }
        }
        return last
    }
}
