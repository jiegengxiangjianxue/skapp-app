package com.skapp.app.data

/**
 * 横截面分位 —— 端上复刻云端 `rank_score.py`。
 *
 * 口径唯一真值：`stocks-ai-view/scripts/rank_score.py:30-130`
 *
 * 为什么端上要它：`LocalScreener.preselect` 用的是四维**秩和**（只决定谁进深算），
 * 最终输出没有任何「在全市场排第几档」的字段。于是界面上「72 分」既可能是今天最强，
 * 也可能只是矮子里的高个 —— 分位能把这件事说清，且不需要任何云端数据。
 *
 * 与云端逐点一致的细节：
 *  - 并列值取**平均秩**（`quantile_score` 的 while 循环），不是稳定排序位次；
 *  - 单元素返回 0.5，不是 0 或 1；
 *  - 缺值时用**非空值中位数**填补（`composite_rank` 的 NaN 处理），而不是丢样本。
 */
object CrossSectionRank {

    /**
     * 云端 7 档标签，逐阈值照抄 `rank_score.py:56-64`。
     *
     * 注意云端首档是 `(0.0, "极弱")`，判据 `pct < 0.0` **永不命中**，
     * 所以实际只会产出 6 档（「极弱」是不可达分支）。端上保持一致：
     * 这里不产出「极弱」，也不把阈值平移成「<0.1 → 极弱」——那会让 0 分位的票
     * 与云端差一档，横截面分层直接错位。
     */
    fun tier(pct: Double): String = when {
        pct < 0.1 -> "弱"
        pct < 0.3 -> "偏弱"
        pct < 0.5 -> "中性"
        pct < 0.7 -> "偏强"
        pct < 0.9 -> "强"
        else -> "极强"
    }

    /**
     * values → 每个值的横截面分位（0..1，1=最高）。
     * 与云端一致：空序列 → 空；单值 → 0.5；并列取平均秩后 /(n-1)。
     */
    fun percentiles(values: List<Double>): List<Double> {
        val n = values.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(0.5)
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++
            val avgRank = (i + j) / 2.0
            for (k in i..j) ranks[order[k]] = avgRank
            i = j + 1
        }
        return ranks.map { it / (n - 1) }
    }

    /**
     * 缺值版本：null 用非空值中位数填补（云端 `composite_rank` 口径），
     * 全空则按 0.0 处理并返回全 0.5 分位（无法排序时不给假区分度）。
     */
    fun percentilesWithMedianFill(values: List<Double?>): List<Double> {
        if (values.isEmpty()) return emptyList()
        val clean = values.filterNotNull().sorted()
        if (clean.isEmpty()) return List(values.size) { 0.5 }
        val median = clean[clean.size / 2]
        return percentiles(values.map { it ?: median })
    }

    /** 排名序号（1 = 最高），并列按平均秩后再定位，保证同名次一致。 */
    fun rankNo(values: List<Double>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val pcts = percentiles(values)
        return pcts.map { p ->
            if (values.size == 1) 1 else (1.0 + (1.0 - p) * (values.size - 1)).toInt().coerceIn(1, values.size)
        }
    }
}
