package com.skapp.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 只读真实推股记录；缺字段保留 null，旧档不补造版本/成交价格。 */
object PickHistoryRepository {
    data class Record(
        val code: String,
        val name: String?,
        val ts: String?,
        val session: String?,
        val price: Double?,
        val engine: String?,
        val version: String?,
        val source: String,
        val adjustmentEvidence: String? = null,
        val raw: String = "",
    )
    data class SourceFailure(val source: String, val reason: String)
    data class LoadResult(
        val records: List<Record>,
        val failures: List<SourceFailure>,
        val filesRead: Int,
        val truncated: Boolean,
    )

    private fun JSONObject.text(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" && it != "insufficient" }

    /** 支持 rounds 包装与旧单张 artifact/picks；不改写远端或队列。 */
    fun parse(body: String, source: String, maxRecords: Int = 2000): LoadResult {
        val bound = maxRecords.coerceIn(1, 5000)
        if (body.length > 2_000_000) return LoadResult(emptyList(), listOf(SourceFailure(source, "file_too_large")), 0, true)
        return try {
            val rounds = if (body.trimStart().startsWith("[")) JSONArray(body) else {
                val root = JSONObject(body)
                root.optJSONArray("rounds") ?: JSONArray().put(root)
            }
            val records = mutableListOf<Record>()
            val failures = mutableListOf<SourceFailure>()
            var truncated = false
            for (i in 0 until rounds.length()) {
                val round = rounds.optJSONObject(i)
                if (round == null) { failures.add(SourceFailure(source, "invalid_round:$i")); continue }
                val picks = round.optJSONArray("picks")
                if (picks == null) { failures.add(SourceFailure(source, "missing_picks:$i")); continue }
                for (j in 0 until picks.length()) {
                    if (records.size >= bound) { truncated = true; break }
                    val pick = picks.optJSONObject(j)
                    val code = pick?.text("code")?.let { Code.normalize(it) }
                    if (pick == null || code == null) { failures.add(SourceFailure(source, "invalid_pick:$i/$j")); continue }
                    val meta = round.optJSONObject("meta") ?: round.optJSONObject("metadata")
                    records.add(Record(
                        code = code,
                        name = pick.text("name"),
                        ts = round.text("ts") ?: round.text("calculationTime") ?: round.text("generatedAt"),
                        session = round.text("session"),
                        price = pick.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0 },
                        engine = pick.text("engine") ?: round.text("engine"),
                        version = pick.text("version") ?: round.text("version") ?: round.text("engineVersion")
                            ?: meta?.text("specVersion") ?: meta?.text("engineVersion"),
                        source = source,
                        adjustmentEvidence = pick.text("adjustmentEvidence") ?: meta?.text("adjustmentEvidence"),
                        raw = pick.toString(),
                    ))
                }
                if (truncated) break
            }
            LoadResult(records, failures, 1, truncated)
        } catch (_: Exception) {
            LoadResult(emptyList(), listOf(SourceFailure(source, "invalid_json")), 0, false)
        }
    }

    /** 目录与每个文件最多25秒；文件≤60、记录≤5000、并发≤4，源失败不等于没有历史。 */
    suspend fun load(maxFiles: Int = 30, maxRecords: Int = 2000): LoadResult = coroutineScope {
        val dir = PicksArchiveQueue.PUSH_DB_DIR
        if (!GitHubRepoWriter.isConfigured()) return@coroutineScope LoadResult(emptyList(),
            listOf(SourceFailure(dir, "仓库访问未配置，无法读取真实归档；未使用演示数据")), 0, false)
        val failures = mutableListOf<SourceFailure>()
        val names = try {
            withTimeoutOrNull(25_000L) { GitHubRepoWriter.listDir(dir) }.also {
                if (it == null) failures.add(SourceFailure(dir, "list_timeout"))
            }.orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failures.add(SourceFailure(dir, "list_failed")); emptyList() }
        val matching = names.filter { Regex("\\d{4}-\\d{2}-\\d{2}\\.json").matches(it) }.sortedDescending()
        val chosen = matching.take(maxFiles.coerceIn(1, 60))
        val bound = maxRecords.coerceIn(1, 5000)
        val records = mutableListOf<Record>()
        var filesRead = 0
        var truncated = chosen.size < matching.size || names.size >= 1000
        if (names.size >= 1000) failures.add(SourceFailure(dir, "github_contents_listing_may_be_truncated"))
        for (batch in chosen.chunked(4)) {
            if (records.size >= bound) { truncated = true; break }
            val results = batch.map { name -> async(Dispatchers.IO) {
                val path = "$dir/$name"
                try {
                    val file = withTimeoutOrNull(25_000L) { GitHubRepoWriter.readFile(path) }
                    if (file == null) LoadResult(emptyList(), listOf(SourceFailure(path, "timeout_or_not_found")), 0, false)
                    else parse(file.body, path, bound)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { LoadResult(emptyList(), listOf(SourceFailure(path, "read_failed")), 0, false) }
            } }.map { it.await() }
            results.forEach {
                records.addAll(it.records)
                failures.addAll(it.failures)
                filesRead += it.filesRead
                truncated = truncated || it.truncated
            }
        }
        if (names.isEmpty() && failures.isEmpty()) failures.add(SourceFailure(dir, "archive_empty_or_missing"))
        if (names.isNotEmpty() && matching.isEmpty()) failures.add(SourceFailure(dir, "no_supported_archive_files"))
        LoadResult(records.sortedByDescending { it.ts }.take(bound), failures, filesRead, truncated || records.size > bound)
    }
}
