package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.skapp.app.data.LocalScreener
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private enum class ResultOrder(val title: String, val mode: String) {
    SCORE("综合评分", "score"), RESONANCE("指标共振", "all"), LIMIT_UP("涨停观察", "limitup"),
}

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState) {
    var order by remember { mutableStateOf(ResultOrder.SCORE) }

    val uniqueResults = remember(state.screened) {
        state.screened.distinctBy { normalizeScreenCode(it.code) }
    }
    val visible = remember(uniqueResults, order) {
        when (order) {
            ResultOrder.SCORE -> uniqueResults
                .filter { it.buyable == true && it.detailStatus == "ok" && (it.pctChg ?: Double.POSITIVE_INFINITY) < 9.6 }
                .sortedByDescending { it.engine?.composite }
            ResultOrder.RESONANCE -> uniqueResults
                .filter { it.detailStatus == "ok" && it.engine?.composite != null }
                .sortedByDescending(::resonanceRatio)
            ResultOrder.LIMIT_UP -> uniqueResults
                .filter { it.pctChg != null }
                .sortedByDescending { it.pctChg }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("盘面解读请前往看板查看统一正文；本页仅展示实时异动与推股策略。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("实时总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${state.marketPhase} · ${state.quoteTs ?: "时点暂无"}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Text("刷新", Modifier.clickable {
                        vm.refreshRealtimeSummary(order.mode)
                    }, color = MaterialTheme.colorScheme.primary)
                }
                val summary = state.summary
                val summaryTs = listOfNotNull(
                    summary["date"]?.toString(), summary["session"]?.toString(), summary["ts"]?.toString(),
                ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "session/time: unknown" }
                Text("盘面总结已统一到看板 · $summaryTs", fontWeight = FontWeight.SemiBold)
                Text("本页不重复展示盘前正文；请在看板查看统一盘面解读。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                SessionStatus(summary)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("推股策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResultOrder.values().forEach { option ->
                        Card(
                            modifier = Modifier.clickable { order = option },
                            colors = CardDefaults.cardColors(
                                containerColor = if (order == option) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Text(option.title, Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.screening) CircularProgressIndicator()
                    else Text("扫描", Modifier.clickable { vm.runScreen(order.mode, force = true) }
                        .padding(horizontal = 8.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary)
                }
                Text("${state.screenMsg ?: "实时策略待刷新"} · ${state.scanMode.ifBlank { "mode unknown" }}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        if (visible.isEmpty()) {
            item { Text(if (state.screening) "实时策略扫描进行中…" else "暂无数据：请点击“扫描”获取当前模式结果。", color = Color.Gray) }
        } else {
            item { Text("${visible.size} 只 · ${order.title}排序", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            items(visible, key = { normalizeScreenCode(it.code) }) { item ->
                LocalResultRow(item, order, vm)
            }
        }

        item {
            Text("实时异动与消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.newsCalcs.isEmpty()) {
            item { Text("暂无数据：没有已计算的实时消息异动。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        } else {
            items(state.newsCalcs.take(20), key = { "news:${it.newsTitle}:${it.ts.orEmpty()}" }) { news ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(news.newsTitle, fontWeight = FontWeight.Medium)
                    Text(listOfNotNull(news.source, news.verdict).joinToString(" · ").ifBlank { "时点暂无 · 状态 unknown" },
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(news.note ?: "暂无分析数据", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text("补充数据面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("多池 ${state.pools.size} · 板块 ${state.sectors.industries.size + state.sectors.concepts.size} · 云端 engine_v5 ${if (state.engineFeeds?.engineV5 != null) "available" else "unknown"}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            val poolStamp = state.pools.firstOrNull()?.ts?.takeIf { it.isNotBlank() } ?: "时点暂无"
            Text("多池时点：$poolStamp；云端结果仅为来源参考，不加入本地推荐列表。",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        item {
            val poolRows = state.pools.flatMap { pool -> pool.items.map { pool.name to it } }
                .distinctBy { normalizeScreenCode(it.second.code) }
            poolRows.take(20).forEach { (poolName, item) ->
                Card(colors = CardDefaults.cardColors()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.name ?: item.code} · $poolName",
                            style = MaterialTheme.typography.bodySmall)
                        Text(item.score?.let { String.format("%.1f", it) } ?: "暂无评分",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalResultRow(item: LocalScreener.Screened, order: ResultOrder, vm: SkappViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(item.code) },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(item.name ?: item.code, fontWeight = FontWeight.Medium)
                    Text(item.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(item.price?.let { "%.2f".format(it) } ?: "暂无数据")
                    Text(item.pctChg?.let { "%+.2f%%".format(it) } ?: "涨跌暂无",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            when (order) {
                ResultOrder.SCORE -> Text("综合评分 ${item.engine?.composite?.let { "%.1f".format(it) } ?: "insufficient_data"}",
                    style = MaterialTheme.typography.bodySmall)
                ResultOrder.RESONANCE -> Text("共振 ${"%.0f%%".format(resonanceRatio(item) * 100)} · 有效 ${item.engine?.nValid ?: 0}/12 引擎",
                    style = MaterialTheme.typography.bodySmall)
                ResultOrder.LIMIT_UP -> Text("涨停观察 · 涨幅 ${item.pctChg?.let { "%+.2f%%".format(it) } ?: "暂无数据"}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (item.detailStatus != "ok" || item.engine?.composite == null) {
                Text("insufficient_data · 不作为普通推荐", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD29922))
            }
        }
    }
}

@Composable
private fun SessionStatus(summary: Map<String, Any?>) {
    val sessions = listOf(
        "早盘" to listOf("morning_session", "early_session", "morning"),
        "午盘" to listOf("noon_session", "midday_session", "noon"),
        "收盘" to listOf("close_session", "closing_session", "close"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sessions.forEach { (label, keys) ->
            val value = keys.asSequence().mapNotNull { summary[it]?.toString()?.takeIf(String::isNotBlank) }.firstOrNull()
            val time = keys.asSequence().mapNotNull { key ->
                summary["${key}_ts"]?.toString()?.takeIf(String::isNotBlank)
                    ?: summary["${key}_time"]?.toString()?.takeIf(String::isNotBlank)
            }.firstOrNull()
            Text("$label · session=${value ?: "unknown"} · time=${time ?: "暂无"}",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

private fun resonanceRatio(item: LocalScreener.Screened): Double {
    val engines = item.engine?.engines ?: return 0.0
    val valid = engines.filter { it.score != null }
    return if (valid.isEmpty()) 0.0 else valid.count { it.signal == "bullish" }.toDouble() / valid.size
}
