package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * BanProb - 涨停次日表现概率
 *
 * 数据来源：云端 ban_prob.json
 * 结构：base 包含 by_lianban（按连板数分组的涨停次日表现）
 *
 * 项目未引入 kotlinx.serialization，全部用 org.json 手工解析。
 */
data class BanProb(
    val ts: String? = null,
    val engine: String? = null,
    val base: Map<String, LianbanRaw> = emptyMap(),
    val balanced: BalanceResult? = null,
) {
    /** 平衡后的次日涨停率（%），无数据返回 null */
    fun balancedZtRate(tier: String): Double? =
        balanced?.byLianban?.get(tier)?.balZtNextRate

    /** 是否对该连板层样本充足（≥100） */
    fun isSufficient(tier: String): Boolean =
        balanced?.byLianban?.get(tier)?.sufficient ?: false
}

object BanProbClient {
    private const val BAN_PROB_URL = "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data/ban_prob.json"

    /**
     * 加载 ban_prob 并应用样本均衡
     * 优先尝试均衡版本，失败则回退到原始版本
     */
    suspend fun load(): BanProb? {
        return try {
            // 先尝试加载均衡版本（云上有 ban_prob_balanced.json）
            val balancedUrl = "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data/ban_prob_balanced.json"
            val json = SkappApi.fetchJson(balancedUrl)
            if (json != null) return parseBalanced(json)

            // 回退到原始版本并本地均衡
            val raw = SkappApi.fetchJson(BAN_PROB_URL) ?: return null
            parseRaw(raw)
        } catch (e: Exception) {
            try {
                val raw = SkappApi.fetchJson(BAN_PROB_URL) ?: return null
                parseRaw(raw)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun parseBalanced(json: JSONObject): BanProb? {
        return try {
            val ts = json.optString("ts").takeIf { it.isNotEmpty() }
            val engine = json.optString("engine").takeIf { it.isNotEmpty() }
            val byLiAnban = LinkedHashMap<String, LianbanBalanced>()
            json.optJSONObject("by_lianban")?.let { obj ->
                SampleBalancer.TIER_ORDER.forEach { tier ->
                    val e = obj.optJSONObject(tier) ?: return@forEach
                    byLiAnban[tier] = LianbanBalanced(
                        lianban = e.optInt("lianban"),
                        nSamples = e.optInt("n_samples"),
                        rawZtNextRate = if (e.isNull("raw_zt_next_rate")) null else e.optDouble("raw_zt_next_rate"),
                        balZtNextRate = e.optDouble("bal_zt_next_rate"),
                        rawZhabanNextRate = if (e.isNull("raw_zhaban_next_rate")) null else e.optDouble("raw_zhaban_next_rate"),
                        balZhabanNextRate = e.optDouble("bal_zhaban_next_rate"),
                        rawRetNextAvg = if (e.isNull("raw_ret_next_avg")) null else e.optDouble("raw_ret_next_avg"),
                        balRetNextAvg = e.optDouble("bal_ret_next_avg"),
                        confidence = e.optDouble("confidence"),
                        sufficient = e.optBoolean("sufficient"),
                        priorUsed = e.optDouble("prior_used"),
                    )
                }
            }
            val k = json.optInt("k", 50)
            val minSufficient = json.optInt("min_sufficient", 100)
            val globalPrior = json.optJSONObject("global_prior")
            val imbalance = json.optJSONObject("sample_imbalance")
            val insufficientTiers = imbalance?.optJSONArray("insufficient_tiers")?.let { arr ->
                List(arr.length()) { i -> arr.optString(i) }
            } ?: emptyList()
            val balanced = BalanceResult(
                method = "empirical-Bayes shrinkage, k=$k",
                k = k,
                minSufficient = minSufficient,
                globalPriorZt = globalPrior?.optDouble("zt_next_rate") ?: 0.0,
                globalPriorZb = globalPrior?.optDouble("zhaban_next_rate") ?: 0.0,
                globalPriorRet = globalPrior?.optDouble("ret_next_avg") ?: 0.0,
                byLianban = byLiAnban,
                totalSamples = imbalance?.optInt("total") ?: 0,
                topTierSharePct = imbalance?.optDouble("top_tier_share_pct") ?: 0.0,
                insufficientTiers = insufficientTiers,
            )
            BanProb(ts = ts, engine = engine, balanced = balanced)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRaw(json: JSONObject): BanProb? {
        return try {
            val ts = json.optString("ts").takeIf { it.isNotEmpty() }
            val engine = json.optString("engine").takeIf { it.isNotEmpty() }
            val rawBase = json.optJSONObject("base")?.optJSONObject("by_lianban")
            val base = LinkedHashMap<String, LianbanRaw>()
            rawBase?.let { obj ->
                SampleBalancer.TIER_ORDER.forEach { tier ->
                    val e = obj.optJSONObject(tier) ?: return@forEach
                    base[tier] = LianbanRaw(
                        lianban = e.optInt("lianban"),
                        nSamples = e.optInt("n_samples"),
                        ztNextRate = if (e.isNull("zt_next_rate")) null else e.optDouble("zt_next_rate"),
                        zhabanNextRate = if (e.isNull("zhaban_next_rate")) null else e.optDouble("zhaban_next_rate"),
                        retNextAvg = if (e.isNull("ret_next_avg")) null else e.optDouble("ret_next_avg"),
                    )
                }
                // 遍历所有 key，防止比 6 连板更高的被遗漏
                (obj.keys() as? Iterable<String>)?.forEach { k ->
                    if (!base.containsKey(k)) {
                        val e = obj.optJSONObject(k) ?: return@forEach
                        base[k] = LianbanRaw(
                            lianban = e.optInt("lianban"),
                            nSamples = e.optInt("n_samples"),
                            ztNextRate = if (e.isNull("zt_next_rate")) null else e.optDouble("zt_next_rate"),
                            zhabanNextRate = if (e.isNull("zhaban_next_rate")) null else e.optDouble("zhaban_next_rate"),
                            retNextAvg = if (e.isNull("ret_next_avg")) null else e.optDouble("ret_next_avg"),
                        )
                    }
                }
            }
            val balanced = if (base.isNotEmpty()) SampleBalancer.balance(base) else null
            BanProb(ts = ts, engine = engine, base = base, balanced = balanced)
        } catch (e: Exception) {
            null
        }
    }
}
