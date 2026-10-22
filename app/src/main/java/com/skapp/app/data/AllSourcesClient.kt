package com.skapp.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
            // 优先尝试 GitHub raw
            val rawUrl = "https://raw.githubusercontent.com/jiegengxiangjianxue/stocks-ai-view-b/main/data/all_sources.json"
            val jsonObject = fetchFromUrl(rawUrl)
            if (jsonObject != null) {
                cachedData = jsonObject
                lastFetchTime = now
                return jsonObject
            }

            // 如果 raw 失败，退回 GitHub API
            val apiUrl = "https://api.github.com/repos/jiegengxiangjianxue/stocks-ai-view-b/contents/data/all_sources.json"
            fetchFromGitHubApi(apiUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch all_sources failed", e)
            null
        }
    }

    // 从 GitHub API 拉取数据
    private fun fetchFromGitHubApi(apiUrl: String): JSONObject? {
        return try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                // GitHub API 返回的是 base64 编码的内容
                val jsonResponse = JSONObject(response)
                val content = jsonResponse.getString("content")
                val decodedBytes = java.util.Base64.getDecoder().decode(content)
                val decodedContent = String(decodedBytes)
                JSONObject(decodedContent)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API fetch failed", e)
            null
        }
    }

    // 从 URL 拉取数据
    private fun fetchFromUrl(urlString: String): JSONObject? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                JSONObject(response)
            } else {
                null
            }
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

        return "190源 ${quoteCount}行情 ${sectorCount}板块 ${newsCount}新闻 / 合并${totalMerged}条"
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