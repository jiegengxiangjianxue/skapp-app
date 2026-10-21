package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 提问式选股：把用户的自然语言问题映射到真实数据上。
 *
 * 设计原则（项目红线）：只基于已拉到的真实数据回答，绝不由本类编造数字。
 * 匹配不到内容就明确说「暂无数据」。
 *
 * 两层：
 *  1) 规则层：识别股票代码/名称、行业关键词、指标问句 → 命中 StockDetail/Decision/Picks
 *  2) 答案层：把命中数据组织成可读回答；AI 问答文本（why/risk/focus）作为补充引用
 */
class AskEngine(
    private val symbols: List<SymbolEntry>,
    private val names: Map<String, String>,
) {

    /** 从问题里抽取股票：代码命中 > 名称命中 */
    fun resolveStocks(question: String): List<String> {
        val q = question.trim().lowercase()
        val hits = LinkedHashSet<String>()

        // 1) 直接代码：600519 / sh600519 / hk00700 / 00700
        val codeHit = Regex("(hk)?\\d{4,6}").find(q)?.value
        if (codeHit != null) {
            SkappApi.normalize(codeHit)?.let { hits.add(it) }
        }
        // 2) 名称命中（最长优先，避免「茅台」撞「茅台集团」类歧义时取更长的）
        if (hits.isEmpty()) {
            symbols.asSequence()
                .filter { it.name != null && it.name.length >= 2 }
                .sortedByDescending { it.name!!.length }
                .firstOrNull { q.contains(it.name!!.lowercase()) }
                ?.let { SkappApi.normalize(it.code)?.let { c -> hits.add(c) } }
        }
        return hits.toList()
    }

    /** 问题意图：用于决定展示哪些板块的数据 */
    fun intent(question: String): Intent {
        val q = question.lowercase()
        return when {
            q.containsAny("为什么", "为啥", "为何", "选它", "选中", "推荐理由") -> Intent.Why
            q.containsAny("风险", "危险", "跌", "回撤", "止损") -> Intent.Risk
            q.containsAny("买", "入场", "进场", "加仓", "目标", "看到") -> Intent.Entry
            q.containsAny("卖", "出", "止盈", "离场") -> Intent.Exit
            q.containsAny("财务", "业绩", "营收", "利润", "市盈", "pe", "pb", "净资产") -> Intent.Financial
            q.containsAny("技术", "均线", "macd", "rsi", "kdj", "量比", "指标") -> Intent.Technical
            q.containsAny("板块", "行业", "概念", "题材", "属于") -> Intent.Board
            q.containsAny("评分", "分数", "多少分", "打分") -> Intent.Score
            else -> Intent.Overview
        }
    }

    enum class Intent { Overview, Why, Risk, Entry, Exit, Financial, Technical, Board, Score }

    private fun String.containsAny(vararg keys: String): Boolean = keys.any { this.contains(it) }

    /**
     * 生成回答：完全由传入的真实数据拼装，缺什么说什么缺。
     */
    fun answer(question: String, details: List<StockDetail>): String {
        if (details.isEmpty()) {
            return "没有找到匹配的股票。可以用「代码+名称」提问，例如：\n" +
                "· 600519 贵州茅台 现在能买吗\n· 宁德时代 有什么风险\n· 300750 技术面怎么样"
        }
        val it = intent(question)
        val sb = StringBuilder()
        details.forEachIndexed { idx, d ->
            if (idx > 0) sb.append("\n\n")
            sb.append(renderStock(d, it))
        }
        return sb.toString().trim()
    }

    private fun renderStock(d: StockDetail, intent: Intent): String {
        val name = d.name ?: d.code
        val head = buildString {
            append("$name（${d.code}）")
        }
        val quote = d.detail?.quote
        val price = quote?.get("price")
        val pct = quote?.get("pctChg") ?: quote?.get("chg_pct")

        val lines = mutableListOf(head)
        if (price != null || pct != null) {
            lines += "现价：${price?.let { formatNum(it) } ?: "暂无数据"}" +
                (pct?.let { "　涨跌幅 ${formatPct(it)}" } ?: "")
        }

        when (intent) {
            Intent.Why, Intent.Overview -> {
                val qa = d.aiQa
                if (qa != null && qa.status == "ok" && !qa.why.isNullOrBlank()) {
                    lines += "为何被关注：${qa.why}"
                } else {
                    lines += "为何被关注：暂无数据"
                }
                if (d.engineScores.isNotEmpty()) {
                    lines += "引擎评分：" + d.engineScores.entries
                        .filter { it.value != null }
                        .joinToString("，") { "${it.key} ${formatNum(it.value!!)}" }
                        .ifEmpty { "暂无数据" }
                }
            }
            Intent.Risk -> {
                val qa = d.aiQa
                if (qa != null && qa.status == "ok" && !qa.risk.isNullOrBlank()) {
                    lines += "主要风险：${qa.risk}"
                } else {
                    lines += "主要风险：暂无数据"
                }
                val rsi = d.detail?.technical?.get("rsi14")
                if (rsi != null) lines += "RSI14：${formatNum(rsi)}"
            }
            Intent.Entry, Intent.Exit -> {
                val qa = d.aiQa
                if (qa != null && qa.status == "ok" && !qa.focus.isNullOrBlank()) {
                    lines += "观察点：${qa.focus}"
                } else {
                    lines += "观察点：暂无数据"
                }
            }
            Intent.Financial -> {
                val fin = d.detail?.financialLatest
                if (fin.isNullOrEmpty()) {
                    lines += "财务数据：暂无数据"
                } else {
                    val keys = listOf("roe", "roa", "revenue", "net_profit", "eps", "pe", "pb", "report_date")
                    val parts = keys.mapNotNull { k ->
                        fin[k]?.takeIf { v -> v != null && v.toString() != "null" }
                            ?.let { v -> "${labelOf(k)} ${formatAny(v)}" }
                    }
                    lines += if (parts.isEmpty()) "财务数据：暂无数据" else "财务摘要：" + parts.joinToString("，")
                }
            }
            Intent.Technical -> {
                val t = d.detail?.technical
                if (t.isNullOrEmpty()) {
                    lines += "技术指标：暂无数据"
                } else {
                    val parts = listOf("ma5", "ma20", "ma60", "rsi14", "macd", "vol_ratio", "pos20")
                        .mapNotNull { k -> t[k]?.let { v -> "${labelOf(k)} ${formatNum(v)}" } }
                    lines += if (parts.isEmpty()) "技术指标：暂无数据" else "技术指标：" + parts.joinToString("，")
                }
            }
            Intent.Board -> {
                lines += if (d.boards.isEmpty()) "所属板块：暂无数据"
                else "所属板块：" + d.boards.joinToString("、")
            }
            Intent.Score -> {
                if (d.engineScores.isEmpty()) lines += "引擎评分：暂无数据"
                else lines += "引擎评分：" + d.engineScores.entries
                    .filter { it.value != null }
                    .joinToString("，") { "${it.key} ${formatNum(it.value!!)}" }
                    .ifEmpty { "暂无数据" }
            }
        }

        if (d.boards.isNotEmpty() && intent != Intent.Board && intent != Intent.Overview) {
            lines += "所属板块：" + d.boards.take(4).joinToString("、")
        }
        if (d.detail?.ts != null) {
            lines += "数据时点：${d.detail.ts}"
        }
        return lines.joinToString("\n")
    }

    private fun labelOf(k: String): String = when (k) {
        "ma5" -> "MA5"; "ma20" -> "MA20"; "ma60" -> "MA60"
        "rsi14" -> "RSI14"; "macd" -> "MACD"; "vol_ratio" -> "量比"; "pos20" -> "20日位置"
        "roe" -> "ROE"; "roa" -> "ROA"; "revenue" -> "营收"; "net_profit" -> "净利润"
        "eps" -> "EPS"; "pe" -> "PE"; "pb" -> "PB"; "report_date" -> "报告期"
        else -> k
    }

    private fun formatNum(v: Double): String =
        if (v >= 1000) String.format("%.0f", v) else String.format("%.2f", v)

    private fun formatAny(v: Any): String =
        (v as? Number)?.let { formatNum(it.toDouble()) } ?: v.toString()

    private fun formatPct(v: Double): String = String.format("%.2f%%", v)
}
