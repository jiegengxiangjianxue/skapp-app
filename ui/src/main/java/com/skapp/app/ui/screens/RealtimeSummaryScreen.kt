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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.CloseReview
import com.skapp.app.data.PickSignal
import com.skapp.app.data.StructuredPickArtifact
import com.skapp.app.data.MarketHours
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState) {
    val today = java.time.LocalDate.now().toString()
    // 进页即拉一次本地深算与异动，避免“打开就是空页”
    LaunchedEffect(Unit) {
        if (state.screened.isEmpty() && !state.screening) vm.runScreen("all")
        if (state.marketPulse.isEmpty()) vm.refreshPulse()
        vm.refreshLocalAnalytics()
    }
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
        item { SummaryTextCard(state.summary) }
        // 本地部分置顶：本地深算候选（次日视角）排在所有云端产物之前
        item { TomorrowPicksCard(state, vm) }
        item { PickArtifactCard("盘前推股（云端）", state.premarketPicks, today, vm) }
        item { PickArtifactCard("午盘推股（云端）", state.middayPicks, today, vm) }
        item { CloseReviewCard(state.closeReview, today) }
        item { WinrateCard(state) }
    }
}

/** 明日推股：本地引擎候选（次日视角），不依赖云端尚未提供的明日产物。 */
@Composable
private fun TomorrowPicksCard(state: UiState, vm: SkappViewModel) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("明日推股", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "本地引擎计算 · ${state.screened.size} 个候选（次日观察）",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text(
                    "重算",
                    Modifier.clickable { vm.runScreen("all", true) }.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val at = state.screenedAt ?: ""
            val atLabel = if (at.length >= 16) at.replace("T", " ").take(19) else "尚未计算"
            val staleMinutes = if (at.isBlank()) null else runCatching {
                java.time.Duration.between(java.time.OffsetDateTime.parse(at), java.time.OffsetDateTime.now())
                    .toMinutes()
            }.getOrNull()
            Text(
                "计算时点 $atLabel" + (staleMinutes?.let { "（${it} 分钟前）" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (staleMinutes != null && staleMinutes > 30) MaterialTheme.colorScheme.error else Color.Gray,
            )
            if (state.screening) {
                Text(state.screenMsg ?: "正在本地全市场深算…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (state.screened.isEmpty()) {
                Text(
                    state.screenMsg ?: "暂无数据：本地深算尚未产出候选。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text(
                    "重新筛选",
                    Modifier.clickable { vm.runScreen("all", true) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                state.screened.take(10).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openDetail(item.code) }.padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${item.name?.takeIf { it.isNotBlank() } ?: "暂无名称"} ${item.code} · " +
                                (item.centralScore?.let { "%.1f".format(it) } ?: "暂无评分"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("详情 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickArtifactCard(
    title: String,
    artifact: StructuredPickArtifact,
    today: String,
    vm: SkappViewModel,
    fallback: StructuredPickArtifact? = null,
) {
    val stale = artifact.date != today || artifact.picks.isEmpty()
    val shown = if (stale && fallback != null) fallback else artifact
    val sourceDate = shown.date ?: shown.generatedAt?.take(10)
    val current = sourceDate == today
    val premarket = title.contains("盘前")
    val premarketActive = MarketHours.phase() in setOf(MarketHours.Phase.PRE_OPEN, MarketHours.Phase.AUCTION_OPEN)
    val visibleForSession = !premarket || premarketActive || (stale && fallback != null)
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("生成 ${shown.generatedAt ?: "暂无数据"} · 计算 ${shown.calculationTime ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            val reasonText = shown.reason
            if (!stale && reasonText != null && shown.status != "ok") {
                Text(reasonText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (shown.picks.isEmpty() || !current || !visibleForSession) {
                val message = when {
                    !current -> "暂无数据：源日期 ${sourceDate ?: "未知"}，不是当前交易日。"
                    premarket && !premarketActive -> "盘前推股时段已结束；当前请查看本地实时推股或等待午盘推股。"
                    else -> shown.reason ?: "暂无数据：该时段没有可展示推股。"
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                Text("引擎：${shown.engine ?: "暂无数据"} · ${shown.picks.size} 条", style = MaterialTheme.typography.bodySmall)
                if (stale) {
                    Text(shown.reason ?: "原产物过期，已用今日引擎候选回填", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                itemsPreview(shown.picks, vm)
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
            if (review.status != "ok") Text(review.reason ?: "本交易日盘后总结未生成或数据不足。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            else {
                if (review.date != today) Text("源日期 ${review.date ?: "未知"}，不是今日数据；以下仅作历史参考。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                review.stages.forEach { (name, data) ->
                    val summary = data["summary"] as? Map<*, *>
                    Text("$name：${summary?.get("n") ?: summary?.get("n_requested") ?: "暂无数据"} 条 · 胜率 ${summary?.get("hit_rate_pct") ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryTextCard(summary: Map<String, Any?>) {
    val source = summary["source"]?.toString()
    val isLocal = source == "local_realtime"
    val ok = summary["status"]?.toString() == "ok"
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("实时总结", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isLocal -> "本地实时计算"
                        source != null -> "云端 $source"
                        else -> "暂无来源"
                    },
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                )
            }
            if (!ok) {
                Text(
                    summary["reason"]?.toString() ?: "暂无数据：本地行情/消息/深算均未就绪，点上方「刷新」重试。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            } else {
                val text = summary["text"]?.toString()
                Text(
                    if (text.isNullOrBlank()) "暂无数据：总结正文为空。" else text,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "时点 ${summary["ts"] ?: "暂无"} · 交易日 ${summary["date"] ?: "暂无"}",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                )
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
