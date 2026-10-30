package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject

class AllSourcesClient {
    private val TAG = "AllSourcesClient"
    private val CACHE_TTL_MS = 30_000L
    private var lastFetchTime: Long = 0
    private var cachedData: JSONObject? = null

    // 从 GitHub raw 拉取数据
    suspend fun fetch(): JSONObject? {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < CACHE_TTL_MS && cachedData != null) {
            return cachedData
        }

        return try {
            val rawUrl = RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "all_sources.json")
            val jsonObject = fetchFromUrl(rawUrl)
            if (jsonObject != null) {
                cachedData = jsonObject
                lastFetchTime = now
                return jsonObject
            }

            // raw 文件未提供时只尝试主仓 Contents API，不虚构源数量。
            val apiUrl = "https://api.github.com/repos/${RemoteSources.OWNER_A}/${RemoteSources.REPO_A}/contents/data/all_sources.json"
            fetchFromGitHubApi(apiUrl)?.also {
                cachedData = it
                lastFetchTime = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch all_sources failed", e)
            null
        }
    }

    // 从 GitHub API 拉取数据
    private fun fetchFromGitHubApi(apiUrl: String): JSONObject? {
        return try {
            val response = Http.getText(
                apiUrl,
                headers = mapOf("Accept" to "application/vnd.github.v3+json"),
            ) ?: return null
            // GitHub API 返回的是 base64 编码的内容
            val content = JSONObject(response).getString("content")
            val decodedBytes = java.util.Base64.getDecoder().decode(content)
            JSONObject(String(decodedBytes))
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API fetch failed", e)
            null
        }
    }

    // 从 URL 拉取数据
    private fun fetchFromUrl(urlString: String): JSONObject? {
        return try {
            Http.getText(urlString)?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "URL fetch failed", e)
            null
        }
    }

    // 解析 quote 数据（来自 data.quote 数组，每项 _key 为 stock code）
    fun parseQuotes(j: JSONObject): Map<String, Quote> {
        val quoteMap = mutableMapOf<String, Quote>()
        val dataObj = j.optJSONObject("data") ?: return quoteMap
        val quoteArray = dataObj.optJSONArray("quote")

        quoteArray?.let { array ->
            for (i in 0 until array.length()) {
                val quoteJson = array.getJSONObject(i)
                val code = quoteJson.optString("_key", "")
                val price = quoteJson.optDouble("price", 0.0)
                val chg = quoteJson.optDouble("chg_pct", 0.0)
                val vol = quoteJson.optLong("vol", 0)
                val amt = quoteJson.optLong("amt", 0)

                quoteMap[code] = Quote(code, price, chg, vol, amt)
            }
        }

        return quoteMap
    }

    // 生成摘要行
    fun summaryLine(j: JSONObject): String {
        val summary = j.optJSONObject("summary")
        val groups = summary?.optJSONObject("groups")
        val quoteCount = groups?.optJSONObject("quote")?.optInt("merged", 0) ?: 0
        val sectorCount = groups?.optJSONObject("sector")?.optInt("merged", 0) ?: 0
        val newsCount = groups?.optJSONObject("news")?.optInt("merged", 0) ?: 0
        val totalMerged = summary?.optInt("total_merged", 0) ?: 0
        val registry = j.optJSONArray("sources_registry")
            ?: j.optJSONArray("sources")
            ?: j.optJSONArray("registry")
        val known = registry?.length()
        val sourceCount = known?.let { "已知${it}源" } ?: "源总数未知"

        return "$sourceCount ${quoteCount}行情 ${sectorCount}板块 ${newsCount}新闻 / 合并${totalMerged}条"
    }

    // Quote 数据类
    data class Quote(
        val code: String,
        val price: Double,
        val chg: Double,
        val vol: Long,
        val amt: Long
    )
}