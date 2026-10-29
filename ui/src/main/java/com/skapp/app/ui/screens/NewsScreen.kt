package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.skapp.app.ui.components.SkCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.NewsCalc
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown

@Composable
fun NewsScreen(vm: SkappViewModel, state: UiState) {
    var tab by remember { mutableStateOf(0) }
    val snapshotItems = state.news.items
    val calcs = state.newsCalcs
    val snapshotRows = snapshotItems.filter { item -> isMainline(item) == (tab == 1) }
    val calcRows = calcs.filter { calc -> calc.newsTitle.startsWith("【主线】") == (tab == 1) }
    val themes = state.news.themes.mapNotNull { (it["name"] ?: it["theme"])?.toString() }.filter { it.isNotBlank() }
    val groupedSnapshot = snapshotRows.groupBy { item ->
        val title = newsTitle(item)
        themes.firstOrNull { title.contains(it, ignoreCase = true) } ?: "最新消息"
    }
    val expanded = remember(groupedSnapshot.keys.toList()) { mutableStateOf(groupedSnapshot.keys.toSet()) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            NewsTab("快讯", snapshotItems.count { !isMainline(it) }, tab == 0) { tab = 0 }
            NewsTab("主线", snapshotItems.count { isMainline(it) }, tab == 1) { tab = 1 }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sourceLine = state.news.sourceStatus.entries.joinToString(" · ") { "${it.key}:${it.value}" }
            Text(
                "本地多源 ${if (sourceLine.isBlank()) "暂无状态" else sourceLine} · 抓取 ${state.news.fetchedAt ?: "暂无数据"} · 去重 ${state.news.dedupCount ?: "暂无数据"}",
                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
            )
            if (state.news.sourceErrors.isNotEmpty()) {
                Text("部分源失败：${state.news.sourceErrors.joinToString("；")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.newsCalcLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text("刷新", Modifier.clickable { vm.loadNewsCalcs() }, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }

        if (snapshotRows.isEmpty() && calcRows.isEmpty() && !state.newsCalcLoading) {
            Text(
                if (tab == 1) "暂无数据：主线消息未返回。" else "暂无数据：快讯未返回。",
                Modifier.fillMaxWidth().padding(24.dp), color = SkColors.textSecondary,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupedSnapshot.forEach { (theme, rows) ->
                    item(key = "theme:$theme") {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                expanded.value = if (theme in expanded.value) expanded.value - theme else expanded.value + theme
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$theme · ${rows.size} 条", fontWeight = FontWeight.SemiBold)
                            Text(if (theme in expanded.value) "收起" else "展开", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (theme in expanded.value) {
                        items(rows, key = { rawNewsKey(it) }) { row ->
                            RawNewsCard(row, calcs.firstOrNull { it.newsTitle == newsTitle(row) }, vm)
                        }
                    }
                }
                val snapshotTitles = snapshotRows.mapTo(HashSet(), ::newsTitle)
                val calcOnly = calcRows.filter { it.newsTitle !in snapshotTitles }
                if (calcOnly.isNotEmpty()) {
                    item { Text("实时补充", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                    items(calcOnly, key = { "calc:${it.newsTitle}:${it.ts.orEmpty()}" }) { calc -> NewsCalcCard(calc, vm) }
                }
            }
        }
    }
}

@Composable
private fun RawNewsCard(row: Map<String, Any?>, calc: NewsCalc?, vm: SkappViewModel) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(newsTitle(row), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val meta = listOfNotNull(
                row["source"] ?: row["source_name"],
                row["time"] ?: row["updated"] ?: row["ts"],
                row["verdict"] ?: row["direction"],
            ).map { it.toString() }
                // "insufficient" 是数据层「算不出来」的占位符，不是给人看的文案
                .filter { it.isNotBlank() && it != "insufficient" }
                .joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            val reason = row["reason"] ?: row["analysis"] ?: row["note"]
            reason?.toString()?.takeIf { it.isNotBlank() && it != "insufficient" }
                ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            extractCodes(row).forEach { code ->
                Text("影响个股：$code", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { vm.openDetail(code) }.padding(vertical = 3.dp))
            }
            calc?.let { NewsCalcInline(it, vm) }
        }
    }
}

@Composable
private fun NewsCalcCard(calc: NewsCalc, vm: SkappViewModel) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(calc.newsTitle, fontWeight = FontWeight.Medium)
            NewsCalcInline(calc, vm)
        }
    }
}

@Composable
private fun NewsCalcInline(n: NewsCalc, vm: SkappViewModel) {
    HorizontalDivider(Modifier.padding(vertical = 5.dp))
    Text(
        listOfNotNull(n.source, n.ts, n.verdict.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "实时分析 · 时点暂无" },
        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
    )
    // 消息面只保留「异动」一个口径：利好/利空只是方向的两种叫法，与异动重复，
    // 真正有信息量的是「因为什么、朝哪个方向、动了多少」，因此合并成一行结论 + 一行依据。
    val moveTag = when (n.direction) {
        "pos" -> "异动偏强"
        "neg" -> "异动偏弱"
        else -> "无明确异动"
    }
    Text(
        "$moveTag · ${n.verdict.takeIf { it.isNotBlank() && it != "insufficient" } ?: "方向待确认"}",
        style = MaterialTheme.typography.bodySmall,
        color = when (n.direction) {
            "neg" -> DOWN
            "pos" -> UP
            else -> SkColors.textSecondary
        },
    )
    val moveBasis = listOfNotNull(
        n.positiveReason?.takeIf { it.isNotBlank() && it != "insufficient" },
        n.negativeReason?.takeIf { it.isNotBlank() && it != "insufficient" },
    ).distinct().joinToString("；")
    if (moveBasis.isNotBlank()) {
        Text("异动依据：$moveBasis", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
    }
    if (n.sentimentHits.isNotEmpty()) Text("命中依据：${n.sentimentHits.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
    if (n.impactCodes.isNotEmpty() && n.stocks.isEmpty()) Text("影响个股：${n.impactCodes.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (n.boards.isNotEmpty()) {
        val detail = n.boards.joinToString("、") { name ->
            val chg = n.boardChg[name]
            if (chg == null) name else "$name ${"%+.2f".format(chg)}%"
        }
        Text("影响板块：$detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        n.boardMomentum?.let { m ->
            val tag = when {
                m >= 1.5 -> "板块共振走强（均涨 ${"%+.2f".format(m)}%）"
                m <= -1.5 -> "板块整体走弱（均跌 ${"%+.2f".format(m)}%），反弹需等确认"
                else -> "板块分化（均涨 ${"%+.2f".format(m)}%）"
            }
            Text("板块研判：$tag", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
        }
    }
    if (n.themes.isNotEmpty()) Text("影响题材：${n.themes.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (n.stocks.isEmpty()) {
        Text(if (n.status == "no_stock") "该条未提及具体股票，无个股计算。" else if (n.status == "board_only") (n.note ?: "仅命中板块，个股待确认。") else "计算中或数据不足。",
            style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
    } else {
        n.stocks.forEach { stock ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openDetail(stock.code) }.padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${stock.name ?: stock.code}  ${stock.code}", fontWeight = FontWeight.Medium)
                    Text("实时价 ${stock.price?.let { "%.2f".format(it) } ?: "暂无"} · 涨跌 ${stock.pctChg?.let { "%+.2f%%".format(it) } ?: "暂无"}",
                        style = MaterialTheme.typography.bodySmall, color = pctColor(stock.pctChg))
                    Text("中枢引擎 ${stock.localScore?.let { "%.1f".format(it) } ?: "insufficient"} · ${signalText(stock.signal)} · 量比 ${stock.volRatio?.let { "%.2f".format(it) } ?: "暂无"}",
                        style = MaterialTheme.typography.bodySmall, color = signalColor(stock.signal))
                    if (stock.topReasons.isNotEmpty()) Text("证据：${stock.topReasons.joinToString("；")}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    stock.centralSummary?.takeIf { it.isNotBlank() }?.let { Text("引擎摘要：$it", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary) }
                }
                Text("详情 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NewsTab(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = if (selected) MaterialTheme.colorScheme.primary else SkColors.textSecondary,
                style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text("$count", color = if (selected) MaterialTheme.colorScheme.primary else SkColors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun newsTitle(row: Map<String, Any?>): String =
    (row["title"] ?: row["newsTitle"] ?: row["digest"] ?: row["content"] ?: "暂无标题").toString().trim()

private fun rawNewsKey(row: Map<String, Any?>): String =
    "news:${row["id"] ?: row["news_id"] ?: ""}:${newsTitle(row)}:${row["time"] ?: row["ts"] ?: ""}"

private fun isMainline(row: Map<String, Any?>): Boolean =
    newsTitle(row).startsWith("【主线】") || row["type"]?.toString()?.let { it in setOf("theme", "mainline") } == true

private fun extractCodes(row: Map<String, Any?>): List<String> {
    val raw = row["codes"] ?: row["impactCodes"] ?: row["stocks"] ?: return emptyList()
    return when (raw) {
        is List<*> -> raw.mapNotNull { value ->
            when (value) {
                is Map<*, *> -> (value["code"] ?: value["symbol"] ?: value["stock_code"])?.toString()
                else -> value?.toString()
            }
        }
        else -> listOf(raw.toString())
    }.map(String::trim).filter(String::isNotBlank).distinct()
}

private fun pctColor(pct: Double?): Color = when {
    pct == null -> SkColors.textSecondary
    pct > 0 -> UP
    pct < 0 -> DOWN
    else -> SkColors.textSecondary
}

private fun signalText(signal: String?): String = when (signal) {
    "bullish" -> "偏多"
    "bearish" -> "偏空"
    "neutral" -> "中性"
    else -> "数据不足"
}

private fun signalColor(signal: String?): Color = when (signal) {
    "bullish" -> UP
    "bearish" -> DOWN
    else -> SkColors.textSecondary
}
