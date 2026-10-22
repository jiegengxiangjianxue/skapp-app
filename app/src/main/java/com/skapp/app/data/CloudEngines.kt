package com.skapp.app.data

/**
 * 云端 15 引擎的端侧精简版 (2026-10-22)
 * 数据: 全部来自公开行情 API (qt.gtimg.cn / ifzq K线), 不需要 token
 * 与云端中枢对齐: 同一套权重, ≥4票=high
 */
object CloudEngines {

    // ── 引擎1: 日历效应 (362万样本验证: 周二最强/周五最弱/2月最强) ──
    fun calendarWeight(dayOfWeek: Int, month: Int): Double {
        val dow = mapOf(1 to 1.05, 2 to 1.15, 3 to 0.85, 4 to 0.95, 5 to 0.70)
        val mon = mapOf(1 to 0.7, 2 to 1.4, 3 to 1.0, 4 to 1.05, 5 to 0.95, 6 to 0.75,
                        7 to 1.05, 8 to 1.1, 9 to 1.2, 10 to 1.15, 11 to 1.12, 12 to 0.95)
        return (dow[dayOfWeek] ?: 1.0) * (mon[month] ?: 1.0)
    }

    // ── 引擎2: 吸筹检测 v2 (价斜率 vs 量斜率 背离, gitcode flow_divergence 法) ──
    fun accumulation(closes: List<Double>, volumes: List<Double>): Pair<String, Double> {
        if (closes.size < 20 || volumes.size < 20) return "数据不足" to 0.0
        fun slope(s: List<Double>): Double {
            val n = minOf(5, s.size); val xs = (0 until n).map { it.toDouble() }
            val ys = s.takeLast(n)
            val mx = xs.average(); val my = ys.average()
            val num = xs.zip(ys).sumOf { (x, y) -> (x - mx) * (y - my) }
            val den = xs.sumOf { (it - mx) * (it - mx) }
            return if (den != 0.0) num / den else 0.0
        }
        val ap = slope(closes) / closes.last() * 100
        val av = slope(volumes) / maxOf(1.0, volumes.takeLast(20).average()) * 100
        val persist = volumes.takeLast(10).count { it > volumes.takeLast(20).average() } / 10.0
        return when {
            ap < -0.02 && av > 0.05 && persist >= 0.6 -> "吸筹背离" to 80.0
            ap > 0.02 && av < -0.05 && persist <= 0.4 -> "派发背离" to 20.0
            persist >= 0.7 -> "量能持续" to 60.0
            else -> "无显著" to 50.0
        }
    }

    // ── 引擎3: 连板实证 (ban_prob: 首板胜率15.5%勿追, 2板+ 57%) ──
    fun banProb(lianban: Int): Triple<Double, Double, String> {
        val table = mapOf(
            1 to Triple(0.24, 15.5, "首板勿追"),
            2 to Triple(1.77, 57.5, "可跟"),
            3 to Triple(2.79, 63.7, "可跟"),
            4 to Triple(2.86, 67.7, "强但炸板风险"),
            5 to Triple(3.51, 67.6, "龙头"),
            6 to Triple(4.19, 77.3, "总龙头"))
        val (ret, win, note) = table[lianban] ?: Triple(0.24, 15.5, "首板勿追")
        return Triple(ret, win, note)
    }

    // ── 引擎4: 中枢投票 (与云端 fuse_scores 同权重) ──
    data class Vote(val engine: String, val weight: Double, val score: Double, val sym: String)
    data class Fused(val sym: String, val score: Double, val engines: List<String>,
                     val confidence: String)

    fun fuse(all: Map<String, Map<String, Double>>): List<Fused> {
        // 权重与云端 engine_registry 对齐
        val weights = mapOf(
            "orchestrator" to 1.2, "factor117" to 1.0, "v7_recoup" to 1.0,
            "moneyflow" to 0.9, "ml_ensemble" to 1.0, "sentiment" to 0.8,
            "dual_trend" to 0.8, "calendar" to 0.5, "acc_detect" to 0.8,
            "lowpos_cyq" to 0.9, "ban_prob" to 0.6, "pead" to 0.9,
            "mystic" to 0.2, "overseas" to 0.7, "beta_inverse" to 0.6)
        val total = mutableMapOf<String, Double>()
        val src = mutableMapOf<String, MutableList<String>>()
        for ((eng, symScores) in all) {
            val w = weights[eng] ?: continue
            for ((sym, s) in symScores) {
                total[sym] = (total[sym] ?: 0.0) + w * s
                src.getOrPut(sym) { mutableListOf() }.add(eng)
            }
        }
        return total.entries.sortedByDescending { it.value }.map {
            val n = src[it.key]!!.size
            Fused(it.key, it.value, src[it.key]!!,
                  if (n >= 4) "high" else if (n >= 2) "mid" else "low")
        }.filter { it.confidence != "low" }
    }
}
