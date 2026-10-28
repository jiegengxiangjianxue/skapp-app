package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 端上待上传队列：消息库与开盘推股历史的「本地攒、打开就补发」。
 *
 * ## 为什么必须落盘
 *
 * 用户不保证一直开着 App：09:12 收到一条利好、09:40 数据还没传上去，App 被杀掉，
 * 这条消息就永远没了——早盘二次分析和午盘都会少一块证据。所以队列写在 JSONL 文件里，
 * 杀进程、重启、隔天再开都能接着 flush。
 *
 * ## 去重口径
 *
 * 消息按 `ts`、推送轮次按 `ts` 去重：同一秒重复采集（多源同一快讯）只留一条，
 * 重试上传同一轮不会在远端长出两条。
 *
 * 纯 JVM 模块，不依赖 Android API；[init] 传进来的目录由 ui 层从 filesDir 给。
 */
object PicksArchiveQueue {

    private const val NEWS_FILE = "news.jsonl"
    private const val PUSH_FILE = "push.jsonl"
    const val NEWS_DB_DIR = "消息数据库"
    const val PUSH_DB_DIR = "开盘推股历史数据"

    private var baseDir: File? = null

    data class FlushReport(
        val newsQueued: Int = 0,
        val newsUploaded: Int = 0,
        val pushQueued: Int = 0,
        val pushUploaded: Int = 0,
        val failures: List<String> = emptyList(),
    ) {
        val allOk: Boolean get() = failures.isEmpty()
        val hasWork: Boolean get() = newsQueued + pushQueued > 0
    }

    fun init(dir: File) {
        baseDir = File(dir, "picks-queue").apply { mkdirs() }
    }

    fun isReady(): Boolean = baseDir != null

    private fun file(name: String): File? = baseDir?.let { File(it, name) }

    @Synchronized
    private fun append(name: String, line: String) {
        val f = file(name) ?: return
        f.appendText(line.trimEnd() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    private fun readAll(name: String): List<JSONObject> {
        val f = file(name) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    @Synchronized
    private fun rewrite(name: String, keep: List<JSONObject>) {
        val f = file(name) ?: return
        f.writeText(keep.joinToString("\n") { it.toString() } + if (keep.isEmpty()) "" else "\n", Charsets.UTF_8)
    }

    /** 存一条消息（已由 NewsCalcEngine 算好受益/受损）。 */
    fun enqueueNews(date: String, item: JSONObject) {
        val ts = item.optString("ts")
        if (ts.isBlank()) return
        val existing = readAll(NEWS_FILE).any { it.optString("ts") == ts }
        if (existing) return
        append(NEWS_FILE, JSONObject().put("date", date).put("item", item).toString())
    }

    /** 存一轮推送记录。 */
    fun enqueuePushRound(date: String, round: JSONObject) {
        val ts = round.optString("ts")
        if (ts.isBlank()) return
        val existing = readAll(PUSH_FILE).any { it.optString("ts") == ts }
        if (existing) return
        append(PUSH_FILE, JSONObject().put("date", date).put("round", round).toString())
    }

    fun pendingNews(): Int = readAll(NEWS_FILE).size
    fun pendingPush(): Int = readAll(PUSH_FILE).size

    /**
     * 把队列刷到 GitHub。成功上传的条目从本地移除，失败的原样留着等下次。
     *
     * 每条日期文件走一次 [GitHubRepoWriter.upsert]：读远端 → 按 ts 合并 → 带 sha 写回。
     */
    suspend fun flush(): FlushReport {
        if (!GitHubRepoWriter.isConfigured()) {
            return FlushReport(failures = listOf("GitHub token 未配置，队列暂存本地"))
        }

        var newsUploaded = 0
        var pushUploaded = 0
        val failures = mutableListOf<String>()

        val newsRows = readAll(NEWS_FILE)
        if (newsRows.isNotEmpty()) {
            val byDate = newsRows.groupBy { it.optString("date") }
            val done = mutableSetOf<String>()
            for ((date, rows) in byDate) {
                if (date.isBlank()) continue
                val fresh = rows.mapNotNull { it.optJSONObject("item") }
                val result = GitHubRepoWriter.upsert(
                    path = "$NEWS_DB_DIR/$date.json",
                    message = "[端上] 消息库 $date",
                ) { current ->
                    val merged = mergeByTs(
                        existing = current?.body?.let { runCatching { JSONObject(it).optJSONArray("items") }.getOrNull() },
                        incoming = fresh,
                        key = "ts",
                    )
                    if (merged.isEmpty()) return@upsert null
                    JSONObject().apply {
                        put("date", date)
                        put("source", "skapp-app")
                        put("items", JSONArray(merged))
                    }.toString()
                }
                when (result) {
                    is GitHubRepoWriter.WriteResult.Ok -> {
                        done += rows.map { it.optString("ts") }
                        newsUploaded += fresh.size
                    }
                    is GitHubRepoWriter.WriteResult.Conflict ->
                        failures += "消息库 $date：远端被改过，已保留待下次重试"
                    is GitHubRepoWriter.WriteResult.Failed ->
                        failures += "消息库 $date：${result.message}"
                }
            }
            if (done.isNotEmpty()) {
                rewrite(NEWS_FILE, newsRows.filter { it.optString("ts") !in done })
            }
        }

        val pushRows = readAll(PUSH_FILE)
        if (pushRows.isNotEmpty()) {
            val byDate = pushRows.groupBy { it.optString("date") }
            val done = mutableSetOf<String>()
            for ((date, rows) in byDate) {
                if (date.isBlank()) continue
                val fresh = rows.mapNotNull { it.optJSONObject("round") }
                val result = GitHubRepoWriter.upsert(
                    path = "$PUSH_DB_DIR/$date.json",
                    message = "[端上] 开盘推股历史 $date",
                ) { current ->
                    val merged = mergeByTs(
                        existing = current?.body?.let { runCatching { JSONObject(it).optJSONArray("rounds") }.getOrNull() },
                        incoming = fresh,
                        key = "ts",
                    )
                    if (merged.isEmpty()) return@upsert null
                    JSONObject().apply {
                        put("date", date)
                        put("source", "skapp-app")
                        put("rounds", JSONArray(merged))
                    }.toString()
                }
                when (result) {
                    is GitHubRepoWriter.WriteResult.Ok -> {
                        done += rows.map { it.optString("ts") }
                        pushUploaded += fresh.size
                    }
                    is GitHubRepoWriter.WriteResult.Conflict ->
                        failures += "推送历史 $date：远端被改过，已保留待下次重试"
                    is GitHubRepoWriter.WriteResult.Failed ->
                        failures += "推送历史 $date：${result.message}"
                }
            }
            if (done.isNotEmpty()) {
                rewrite(PUSH_FILE, pushRows.filter { it.optString("ts") !in done })
            }
        }

        return FlushReport(
            newsQueued = newsRows.size,
            newsUploaded = newsUploaded,
            pushQueued = pushRows.size,
            pushUploaded = pushUploaded,
            failures = failures,
        )
    }

    /** 按时间字段合并：远端已有 + 本轮新增，同 ts 以新增为准，结果按 ts 升序。 */
    private fun mergeByTs(existing: JSONArray?, incoming: List<JSONObject>, key: String): List<JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        existing?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    val k = obj.optString(key)
                    if (k.isNotBlank()) map[k] = obj
                }
            }
        }
        incoming.forEach { obj ->
            val k = obj.optString(key)
            if (k.isNotBlank()) map[k] = obj
        }
        return map.entries.sortedBy { it.key }.map { it.value }
    }
}
