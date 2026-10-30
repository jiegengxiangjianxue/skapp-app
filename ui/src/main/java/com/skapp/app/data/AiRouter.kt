package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端 AI 统一入口 —— 双源自动切换（GitCode 优先，GitHub Models 备份）。
 *
 * 用户决策：本地 LLM 已停，全走云端免费 AI。双源互为备份解决额度担忧：
 * - 优先 GitCode（已配置且未冷却），失败 / HTTP 429 / 超时 → 自动切 GitHub Models
 * - 两个都没配 token → 返回配置提示
 * - 用户自填 Base URL 的 OpenAI 兼容接口作为第三兜底（见 [OpenAiCompatClient]）
 * - 返回结果标注实际 provider（内容前缀 [via GitHub Models · gpt-4o] 之类）
 *
 * 额度管理：
 * - 每个 provider 的调用/失败次数持久化到 SharedPreferences（prefs="skapp_ai_router"）
 * - 任一 provider 返回 429 → 该 provider 冷却 [COOLDOWN_MS]（1 小时），期间不再调用
 * - [statusLine] 给设置页显示当前可用 provider 与额度统计
 *
 * token 注入：AiRouter 自己不直接读 token——SkappViewModel 从 prefs 读出后调
 * [configure] 把两个 token 灌进各自 client（保持与 GitCodeAiClient 现有模式一致）。
 */
object AiRouter {

    /** GitCode 模型常量（与站点 CI 同一套） */
    const val GITCODE_DEEP_MODEL = "deepseek-ai/DeepSeek-V4-Flash"
    const val GITCODE_LIGHT_MODEL = "Qwen/Qwen3-30B-A3B-Instruct-2507"

    const val PREFS = "skapp_ai_router"
    private const val KEY_CALLS = "calls_"      // + provider
    private const val KEY_FAILS = "fails_"      // + provider
    private const val KEY_COOLDOWN = "cooldown_"// + provider（epoch ms，冷却截止时刻）

    /** 429 冷却时长：1 小时 */
    const val COOLDOWN_MS = 60L * 60_000L

    const val PROVIDER_GITCODE = "GitCode"
    const val PROVIDER_GITHUB = "GitHub Models"
    /** 用户自填 Base URL 的 OpenAI 兼容接口，排在两个官方源之后兜底 */
    const val PROVIDER_CUSTOM = "自定义接口"

    const val NOT_CONFIGURED_MSG = "请在设置页填 GitCode 或 GitHub token"

    /** SharedPreferences 由 ViewModel 在启动时注入（纯 JVM 部分不依赖 android.content） */
    private var prefs: android.content.SharedPreferences? = null

    /** 启动时调用：注入 prefs + 两个 provider 的 token */
    fun configure(
        context: android.content.Context,
        gitcodeToken: String?,
        githubToken: String?,
        customBaseUrl: String? = null,
        customApiKey: String? = null,
        customModel: String? = null,
    ) {
        prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        GitCodeAiClient.setToken(gitcodeToken)
        GitHubModelsClient.setToken(githubToken)
        OpenAiCompatClient.configure(customBaseUrl, customApiKey, customModel)
    }

    enum class Tier { DEEP, LIGHT }

    data class AiResult(
        val text: String?,
        val provider: String?,
        val model: String?,
        /** true=双源都不可用/未配置，text 里是提示语 */
        val failed: Boolean,
    )

    // ── 额度统计 ─────────────────────────────────────────────

    private fun bump(key: String, provider: String) {
        val p = prefs ?: return
        p.edit().putInt(key + provider, p.getInt(key + provider, 0) + 1).apply()
    }

    fun calls(provider: String): Int = prefs?.getInt(KEY_CALLS + provider, 0) ?: 0
    fun fails(provider: String): Int = prefs?.getInt(KEY_FAILS + provider, 0) ?: 0

    /** 429 → 冷却 1 小时 */
    private fun markCooldown(provider: String) {
        prefs?.edit()?.putLong(
            KEY_COOLDOWN + provider,
            System.currentTimeMillis() + COOLDOWN_MS,
        )?.apply()
    }

    fun cooldownLeftMs(provider: String): Long {
        val until = prefs?.getLong(KEY_COOLDOWN + provider, 0L) ?: 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isCooling(provider: String): Boolean = cooldownLeftMs(provider) > 0L

    private fun configured(provider: String): Boolean = when (provider) {
        PROVIDER_GITCODE -> GitCodeAiClient.isConfigured()
        PROVIDER_GITHUB -> GitHubModelsClient.isConfigured()
        PROVIDER_CUSTOM -> OpenAiCompatClient.isConfigured()
        else -> false
    }

    /** 当前可用 provider（已配置且未冷却），供设置页显示 */
    fun availableProviders(): List<String> =
        listOf(PROVIDER_GITCODE, PROVIDER_GITHUB, PROVIDER_CUSTOM).filter { configured(it) && !isCooling(it) }

    /** 设置页状态行：可用 provider + 调用/失败统计 + 冷却剩余 */
    fun statusLine(): String {
        val parts = listOf(PROVIDER_GITCODE, PROVIDER_GITHUB, PROVIDER_CUSTOM).map { p ->
            val cfg = if (configured(p)) "已配置" else "未配置"
            val cool = cooldownLeftMs(p)
            val coolStr = if (cool > 0) " · 冷却中（${cool / 60_000 + 1} 分钟）" else ""
            "$p：$cfg · 调用 ${calls(p)} 次 / 失败 ${fails(p)} 次$coolStr"
        }
        val avail = availableProviders()
        val head = if (avail.isEmpty()) "当前无可用 provider" else "当前可用：${avail.joinToString("、")}"
        return head + "\n" + parts.joinToString("\n")
    }

    // ── 统一提问入口 ─────────────────────────────────────────

    /**
     * 统一提问。messages 为 (role, content) 列表；tier 决定模型档位。
     * GitCode 优先 → 失败/429/超时自动切 GitHub Models。
     */
    suspend fun ask(messages: List<Pair<String, String>>, tier: Tier): AiResult =
        withContext(Dispatchers.IO) {
            // 自定义接口排最后：它是用户自备的兜底，不该抢官方源的额度
            val order = listOf(PROVIDER_GITCODE, PROVIDER_GITHUB, PROVIDER_CUSTOM)
            if (order.none { configured(it) }) {
                return@withContext AiResult(NOT_CONFIGURED_MSG, null, null, failed = true)
            }
            for (p in order) {
                if (!configured(p) || isCooling(p)) continue
                bump(KEY_CALLS, p)
                val (text, model, was429) = callProvider(p, messages, tier)
                if (text != null) {
                    return@withContext AiResult(text, p, model, failed = false)
                }
                bump(KEY_FAILS, p)
                if (was429) markCooldown(p)
            }
            AiResult(
                "云端 AI 暂不可用（双源均失败或被限流，稍后再试）",
                null, null, failed = true,
            )
        }

    /** 深度档：复盘/深度分析 */
    suspend fun askDeep(messages: List<Pair<String, String>>): AiResult = ask(messages, Tier.DEEP)

    /** 轻量档：快答 */
    suspend fun askQuick(messages: List<Pair<String, String>>): AiResult = ask(messages, Tier.LIGHT)

    /** 单 prompt 便捷封装 */
    suspend fun askDeep(prompt: String): AiResult = askDeep(listOf("user" to prompt))
    suspend fun askQuick(prompt: String): AiResult = askQuick(listOf("user" to prompt))

    /** 返回 Triple(text, model, was429) */
    private fun callProvider(
        provider: String,
        messages: List<Pair<String, String>>,
        tier: Tier,
    ): Triple<String?, String?, Boolean> {
        return when (provider) {
            PROVIDER_GITCODE -> {
                val deep = tier == Tier.DEEP
                val first = if (deep) GITCODE_DEEP_MODEL else GITCODE_LIGHT_MODEL
                val second = if (deep) GITCODE_LIGHT_MODEL else GITCODE_DEEP_MODEL
                var text = GitCodeAiClient.rawChat(messages, first)
                var model = first
                // 429 直接冷却，不再烧备选模型额度
                if (text == null && GitCodeAiClient.lastHttpCode == 429) {
                    return Triple(null, first, true)
                }
                if (text == null) {
                    text = GitCodeAiClient.rawChat(messages, second)
                    model = second
                    if (text == null && GitCodeAiClient.lastHttpCode == 429) {
                        return Triple(null, second, true)
                    }
                }
                Triple(text, if (text != null) model else null, false)
            }
            PROVIDER_GITHUB -> {
                val text = if (tier == Tier.DEEP) {
                    GitHubModelsClient.chatDeep(messages)
                } else {
                    GitHubModelsClient.chatLight(messages)
                }
                val model = when {
                    text == null -> null
                    tier == Tier.DEEP -> GitHubModelsClient.DEEP_MODEL
                    else -> GitHubModelsClient.LIGHT_MODEL
                }
                Triple(text, model, text == null && GitHubModelsClient.lastHttpCode == 429)
            }
            PROVIDER_CUSTOM -> {
                val text = OpenAiCompatClient.rawChat(messages)
                Triple(
                    text,
                    if (text != null) OpenAiCompatClient.modelName() else null,
                    text == null && OpenAiCompatClient.lastHttpCode == 429,
                )
            }
            else -> Triple(null, null, false)
        }
    }
}
