package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("实时总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.marketPhase} · ${state.quoteTs ?: "时点暂无"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                if (state.summaryRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "刷新",
                        modifier = Modifier
                            .clickable { vm.refreshRealtimeSummary("all") }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            state.summaryRefreshMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("刷新失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(
                "等待刷新：中枢引擎会并发更新盘面、消息与实时状态。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }

        item {
            val premarket = state.summary["premarket"]?.toString()?.takeIf { it.isNotBlank() }
            if (premarket != null) {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("盘前分析", fontWeight = FontWeight.SemiBold)
                        premarket.split("\\n").map { it.trim() }.filter { it.isNotBlank() }.take(18).forEach {
                            Text(it.trimStart('#').trim(), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }

        item {
            val picksText = state.summary["picks_text"]?.toString()?.takeIf { it.isNotBlank() }
            if (picksText != null) {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("今日推股与盘后验证", fontWeight = FontWeight.SemiBold)
                        picksText.split("\\n").map { it.trim() }.filter { it.isNotBlank() }.take(24).forEach {
                            Text(it.trimStart('#').trim(), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }

        item {
            val summary = state.summary
            val stamp = listOfNotNull(
                summary["date"]?.toString(),
                summary["session"]?.toString(),
                summary["ts"]?.toString(),
            ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "时点暂无" }
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("中枢引擎", fontWeight = FontWeight.SemiBold)
                    Text(
                        "本页只提供刷新反馈与统一入口；盘前分析和实时异动请在看板查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    Text("运行状态：${state.scanMode.ifBlank { "待刷新" }} · 时点：$stamp", style = MaterialTheme.typography.bodySmall)
                    Text("中枢引擎说明：统一协调盘面、消息、行情与策略结果，页面不重复渲染池列表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI 摘要入口", fontWeight = FontWeight.SemiBold)
                    Text(
                        "AI 摘要由统一 AI 总结入口提供；当前无凭据时仍以中枢引擎结果为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
