package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.skapp.app.ui.components.SkCard
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
                    Text("${state.marketPhase} · ${state.quoteTs ?: "时点暂无"}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
                if (state.summaryRefreshing) CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                else Text("刷新", Modifier.clickable { vm.refreshRealtimeSummary("all") }.padding(10.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            state.summaryRefreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("刷新失败")) MaterialTheme.colorScheme.error else SkColors.textSecondary) }
        }
        // 本页只做总结：推股名单已拆成「盘前推股名单 / 实时推股名单」两个独立入口，
        // 名单是执行用的，总结是复盘用的，节奏不同，混在一页两边都看不清。
        item(key = "sec_summary") { SectionTitle("① 盘中总结", "当下市场在讲什么，来自 AI 二次总结") }
        item { SummaryTextCard(state.summary) }

        item(key = "sec_close") { SectionTitle("② 盘后复盘", "收盘后各阶段实际跑出了什么") }
        item { CloseReviewCard(state.closeReview, today) }

        item(key = "sec_winrate") { SectionTitle("③ 命中率", "历史推股兑现情况，用来判断该信多少") }
        item { WinrateCard(state) }
    }
}

@Composable
private fun CloseReviewCard(review: CloseReview, today: String) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("盘后总结", fontWeight = FontWeight.SemiBold)
            Text("生成 ${review.generatedAt ?: "暂无数据"} · 状态 ${review.status}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            if (review.status != "ok") Text(review.reason ?: "本交易日盘后总结未生成或数据不足。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            else {
                if (review.date != today) Text("源日期 ${review.date ?: "未知"}，不是今日数据；以下仅作历史参考。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
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
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("实时总结", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isLocal -> "本地实时计算"
                        source != null -> "云端 $source"
                        else -> "暂无来源"
                    },
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
            if (!ok) {
                Text(
                    summary["reason"]?.toString() ?: "暂无数据：本地行情/消息/深算均未就绪，点上方「刷新」重试。",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            } else {
                val text = summary["text"]?.toString()
                Text(
                    if (text.isNullOrBlank()) "暂无数据：总结正文为空。" else text,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "时点 ${summary["ts"] ?: "暂无"} · 交易日 ${summary["date"] ?: "暂无"}",
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun WinrateCard(state: UiState) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("推股胜率", fontWeight = FontWeight.SemiBold)
            Text("生成 ${state.winrateMatrix.ts ?: "时点暂无"} · 源数据 ${state.winrateMatrix.sourceTs ?: "暂无数据"} · 来源 ${state.winrateMatrix.source ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            val sourceDate = state.winrateMatrix.sourceTs?.take(10)
            val fresh = sourceDate == java.time.LocalDate.now().toString()
            if (!fresh) Text("暂无数据：胜率源数据不是当前交易日，不能作为今日统计。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            if (state.winrateMatrix.horizons.isEmpty() || !fresh) Text("暂无数据：当前无可用当日胜率。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            else state.winrateMatrix.horizons.toSortedMap().forEach { (horizon, data) ->
                Text("${horizon}日：胜率 ${formatPercent(data["overall"])} · 平均收益 ${data["avg_ret_pct"] ?: "暂无数据"}% · 样本 ${data["n"] ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatPercent(value: Any?): String = when (value) {
    // 胜率矩阵的 overall 由 ViewModel 预格式化成字符串（如 "62.5"），
    // 只认 Number 会让命中率恒显示「暂无数据」——数据在、界面说没有。
    is Number -> "%.1f%%".format(value.toDouble() * 100)
    is String -> value.takeIf { it.isNotBlank() && it != "insufficient" }?.let { "$it%" } ?: "暂无数据"
    else -> "暂无数据"
}

/** 分区标题：把「总结 / 复盘 / 命中率」三段的界限画出来，避免又滚成一条长列表。 */
@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
    }
}
