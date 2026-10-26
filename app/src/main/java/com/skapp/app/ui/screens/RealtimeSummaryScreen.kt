package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.CloseReview
import com.skapp.app.data.PickSignal
import com.skapp.app.data.StructuredPickArtifact
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState) {
    val today = java.time.LocalDate.now().toString()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实时总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${state.marketPhase} · ${state.quoteTs ?: "时点暂无"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                if (state.summaryRefreshing) CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                else Text("刷新", Modifier.clickable { vm.refreshRealtimeSummary("all") }.padding(10.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            state.summaryRefreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("刷新失败")) MaterialTheme.colorScheme.error else Color.Gray) }
        }
        item { PickArtifactCard("结构化盘前推股", state.premarketPicks, today, vm) }
        item { PickArtifactCard("午盘推股", state.middayPicks, today, vm) }
        item { CloseReviewCard(state.closeReview, today) }
        item { WinrateCard(state) }
    }
}

@Composable
private fun PickArtifactCard(title: String, artifact: StructuredPickArtifact, today: String, vm: SkappViewModel) {
    val current = artifact.date == today
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("生成 ${artifact.generatedAt ?: "暂无数据"} · 计算 ${artifact.calculationTime ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (!current || artifact.picks.isEmpty()) {
                Text(if (artifact.reason.isNullOrBlank()) "未到时间或暂无当日数据。" else artifact.reason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                Text("引擎：${artifact.engine ?: "暂无数据"} · ${artifact.picks.size} 条", style = MaterialTheme.typography.bodySmall)
                itemsPreview(artifact.picks, vm)
            }
        }
    }
}

@Composable
private fun itemsPreview(items: List<PickSignal>, vm: SkappViewModel) {
    items.take(20).forEach { pick ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.openDetail(pick.code) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${pick.name?.takeIf { it.isNotBlank() } ?: "暂无名称"}  ${pick.code}", fontWeight = FontWeight.Medium)
                Text(
                    "生成/计算 ${pick.calculationTime ?: "暂无数据"} · 引擎 ${pick.engine ?: "暂无数据"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text(
                    "评分 ${pick.score?.let { "%.1f".format(it) } ?: "暂无数据"} · 共振 ${pick.resonance?.let { "%.1f%%".format(it * 100) } ?: "暂无数据"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("理由：${pick.reason ?: pick.why.take(2).joinToString("；").ifBlank { "暂无数据" }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text("详情 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CloseReviewCard(review: CloseReview, today: String) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("盘后总结", fontWeight = FontWeight.SemiBold)
            Text("生成 ${review.generatedAt ?: "暂无数据"} · 状态 ${review.status}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (review.date != today || review.status != "ok") Text(review.reason ?: "本交易日盘后总结未生成或数据不足。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            else review.stages.forEach { (name, data) ->
                val summary = data["summary"] as? Map<*, *>
                Text("$name：${summary?.get("n") ?: summary?.get("n_requested") ?: "暂无数据"} 条 · 胜率 ${summary?.get("hit_rate_pct") ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WinrateCard(state: UiState) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("推股胜率", fontWeight = FontWeight.SemiBold)
            Text("生成 ${state.winrateMatrix.ts ?: "时点暂无"} · 源数据 ${state.winrateMatrix.sourceTs ?: "暂无数据"} · 来源 ${state.winrateMatrix.source ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            val sourceDate = state.winrateMatrix.sourceTs?.take(10)
            val fresh = sourceDate == java.time.LocalDate.now().toString()
            if (!fresh) Text("暂无数据：胜率源数据不是当前交易日，不能作为今日统计。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (state.winrateMatrix.horizons.isEmpty() || !fresh) Text("暂无数据：当前无可用当日胜率。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            else state.winrateMatrix.horizons.toSortedMap().forEach { (horizon, data) ->
                Text("${horizon}日：胜率 ${formatPercent(data["overall"])} · 平均收益 ${data["avg_ret_pct"] ?: "暂无数据"}% · 样本 ${data["n"] ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatPercent(value: Any?): String = when (value) {
    is Number -> "%.1f%%".format(value.toDouble() * 100)
    else -> "暂无数据"
}
