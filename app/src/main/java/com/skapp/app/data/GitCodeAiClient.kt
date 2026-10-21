package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitCode 免费 AI 客户端 —— 用户在 app 里直接问 AI。
 *
 * 端点：https://gitcode.com/api/v5/chat/completions（Bearer token）
 * 主模型：deepseek-ai/DeepSeek-V4-Flash；备选 Qwen/Qwen3-30B-A3B-Instruct-2507
 *
 * 与站点 CI 的 ai_review / bull_bear_debate 同一套（用户红线：必须 GitCode，
 * 不是 GitHub Models）。
 *
 * 关键设计：把**本地实时算法/引擎/数据**全部塞进 prompt，AI 只基于这些数据回答，
 * 不许编造。输入数据为空时明确回答「数据不足，无法判断」。
 */
object GitCodeAiClient {

    private const val ENDPOINT = "https://gitcode.com/api/v5/chat/completions"
    private const val MODEL = "deepseek-ai/DeepSeek-V4-Flash"
    private const val FALLBACK_MODEL = "Qwen/Qwen3-30B-A3B-Instruct-2507"
    private const val TIMEOUT = 90000

    // token 由用户在 app 设置页填入（SharedPreferences）；本类不内置
    private var token: String? = null

    fun setToken(t: String?) {
        token = t?.takeIf { it.isNotBlank() }
    }

    fun isConfigured(): Boolean = !token.isNullOrBlank()

    private fun chat(messages: List<Pair<String, String>>, model: String): String? {
        val tok = token ?: return null
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
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(resp).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun chatOnce(prompt: String): String? =
        chat(listOf("user" to prompt), MODEL)
            ?: chat(listOf("user" to prompt), FALLBACK_MODEL)

    // ── 场景 1：AI 复盘 ──────────────────────────────────

    /**
     * 单股 AI 复盘：把该股全部真实数据给 AI。
     */
    suspend fun reviewStock(
        code: String,
        name: String?,
        quote: Quote?,
        engine: LocalEngineResult?,
        factors: Map<String, Double?>,
        holo: HoloBundle?,
        news: List<Map<String, Any?>>,
        boards: List<Map<String, Any?>>,
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "GitCode AI 未配置 token（在设置页填）"

        val data = buildString {
            append("【基础】代码=$code 名称=$name")
            quote?.let { q ->
                append(" 现价=${q.price ?: "暂无"} 涨跌=${q.pctChg?.let { "%.2f%%".format(it) } ?: "暂无"}")
            }
            appendLine()
            engine?.let { e ->
                appendLine("【本地引擎】综合=${e.composite?.let { "%.1f".format(it) } ?: "insufficient"}（${e.nValid}/${e.engines.size} 有效）")
                e.engines.forEach { en ->
                    if (en.score != null) {
                        appendLine("  · ${en.engine}=${"%.1f".format(en.score)} ${en.signal}（${en.reasons.take(2).joinToString("，")}）")
                    }
                }
            }
            val validFactors = factors.entries.filter { it.value != null }.take(15)
            if (validFactors.isNotEmpty()) {
                appendLine("【因子】" + validFactors.joinToString(" ") { "${it.key}=${"%.4f".format(it.value!!)}" })
            }
            holo?.technical?.entries?.filter { it.value != null }?.take(10)?.let { t ->
                appendLine("【holo 技术】" + t.joinToString(" ") { "${it.key}=${"%.2f".format(it.value!!)}" })
            }
            holo?.finance?.entries?.take(8)?.let { f ->
                appendLine("【财务】" + f.joinToString(" ") { "${it.key}=${it.value ?: "暂无"}" })
            }
            if (news.isNotEmpty()) {
                appendLine("【该股新闻】")
                news.take(6).forEach { n ->
                    appendLine("  · ${n["title"] ?: "无标题"}")
                }
            }
            if (boards.isNotEmpty()) {
                appendLine("【板块】" + boards.mapNotNull { it["name"]?.toString() }.joinToString("、"))
            }
        }

        val prompt = """你是 A 股复盘分析师。以下是该股 $code $name 的**真实实时数据**，你只允许基于这些数据回答，禁止编造任何数字或事实。数据里没有的就说「数据不足」。

$data

请输出 3 部分（每部分 1-2 句，总 300 字内）：
1. 【当前状态】技术面+资金面+消息面综合评价
2. 【风险】主要风险点
3. 【观察点】后续该盯什么
直接输出文本，不要 markdown 代码块。"""
        chatOnce(prompt)
    }

    // ── 场景 2：AI 问答（自由提问）────────────────────────

    /**
     * 用户自由提问：把当前 app 已拉到的全部上下文（实时行情/引擎/因子/板块/消息面/池子）
     * 给 AI 作为数据底稿，AI 直接回答。
     */
    suspend fun askFree(question: String, context: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "GitCode AI 未配置 token（在设置页填）"
        val prompt = """你是 A 股数据分析助手。用户提问：$question

以下是你可调用的**实时数据底稿**（app 刚从数据源拉到的真实值）：

$context

规则：
- 只允许基于上面这些数据回答；数据里没有的就明说「未拉取到相关数据」
- 结论要有依据，引用具体数字（如「量比 2.3」「本地引擎 71.2」）
- 300 字内，直接回答，不要寒暄、不要免责声明"""
        chatOnce(prompt)
    }

    // ── 场景 3：AI 复盘（市场级，对标站点 ai_review）────────

    suspend fun reviewMarket(
        marketNow: Map<String, Any?>,
        decision: Decision,
        picks: TodayPicks,
        news: NewsSnapshot,
        sectors: SectorsSnapshot,
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "GitCode AI 未配置 token（在设置页填）"

        val data = buildString {
            appendLine("【市场】${marketNow.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(300)}")
            appendLine("【决策】状态=${decision.marketRegime} ${decision.regimeDetail ?: ""} 建议仓位=${decision.positionAdvice}")
            appendLine("【推股】signals=${picks.signals.size} values=${picks.values.size} 涨停=${picks.limitUp.size}")
            picks.signals.take(5).forEach {
                appendLine("  · ${it.name ?: it.code} 分=${it.score} ${it.engine ?: ""}")
            }
            appendLine("【消息情绪】${news.digest["global_mood"]} 评分=${news.digest["global_score"]} 共 ${news.digest["total"]} 条")
            appendLine("【板块热度】行业 ${sectors.industries.size} 概念 ${sectors.concepts.size}")
        }

        val prompt = """你是 A 股复盘分析师。基于以下当日真实数据，写 300 字内复盘：
$data

要求：
1) 市场情绪与主线判断
2) 热点信号里哪些最值得关注（按数据支撑强度排序）
3) 明日情景推演（进攻/防守两种）
直接输出文本，不要 markdown 代码块。"""
        chatOnce(prompt)
    }
}
