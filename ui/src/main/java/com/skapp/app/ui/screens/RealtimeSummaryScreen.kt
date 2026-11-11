package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.MarketHours
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.state.WorkbenchSummary
import com.skapp.app.ui.theme.SkColors

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState, onSection: (Int) -> Unit = {}) {
    LaunchedEffect(Unit) { vm.loadSummary() }
    val sections = WorkbenchSummary.sections(state)
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("市场复盘", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("市场 → 主线 → 催化 → 候选 → 风险", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    val marketSection = sections.firstOrNull { it.id == "market" }
                    Text(when (marketSection?.status) {
                        "historical" -> "历史复盘 · 源日期 ${state.board["sourceDate"]} · 非实时、不用于当前推荐"
                        "historical_sample" -> "历史采样 ${state.board["sampleSize"]} 只 · 源日期 ${state.board["sourceDate"]} · 不代表全市场、非实时"
                        "ok" -> "实时行情复盘 · 源时点 ${marketSection.timestamp}"
                        else -> "行情证据不足 · 不展示未校验盘面"
                    }, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
                if (state.summaryRefreshing) CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { vm.refreshRealtimeSummary("all") }) { Text("刷新") }
            }
            state.summaryRefreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.localPicksLoading) Text(state.localPicksMsg ?: "本地深算中…", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onSection(1) }) { Text("消息事件") }
                TextButton(onClick = { onSection(2) }) { Text("打开题材主线") }
                TextButton(onClick = { onSection(3) }) { Text("决策详情") }
            }
        }
        items(sections, key = { it.id }) { section ->
            var expanded by remember(section.id) { mutableStateOf(false) }
            SkCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        Text(when (section.status) { "ok" -> "时效已校验"; "historical" -> "历史复盘 · 非实时"; "historical_sample" -> "历史采样 · 非全市场"; "partial" -> "部分可用"; "observe" -> "观察"; else -> "证据不足" },
                            style = MaterialTheme.typography.labelMedium, color = SkColors.textSecondary)
                    }
                    Text("${section.source} · ${section.timestamp ?: "时点未提供"}", style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary)
                    val entries = if (expanded) section.entries else section.entries.take(3)
                    entries.forEach { entry ->
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium,
                            modifier = if (entry.code != null) Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { vm.openDetail(entry.code) }.padding(vertical = 8.dp) else Modifier.fillMaxWidth())
                    }
                    if (section.entries.size > 3) TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开全部 ${section.entries.size} 项")
                    }
                }
            }
        }
        item {
            val review = state.closeReview
            SkCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("盘后复盘 · 历史参考", style = MaterialTheme.typography.titleMedium)
                    Text("源日期 ${review.date ?: "未知"} · 生成 ${review.generatedAt ?: "未知"}", style = MaterialTheme.typography.labelSmall)
                    Text(when {
                        review.status != "ok" -> review.reason ?: "盘后归档尚未就绪。"
                        review.date != MarketHours.todayKey() -> "不是今日记录；不回填为当日判断。"
                        else -> review.reason ?: "当日归档；不代表当前实时判断。"
                    }, style = MaterialTheme.typography.bodyMedium)
                    review.stages.entries.take(4).forEach { (name, data) ->
                        val summary = data["summary"] as? Map<*, *>
                        Text("$name · 归档样本 ${summary?.get("n") ?: summary?.get("n_requested") ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
