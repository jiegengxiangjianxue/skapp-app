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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.DecisionCard
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/** 决策 Tab：市场状态 + 决策卡（含入场区间/止损/目标/风险） */
@Composable
fun DecisionScreen(vm: SkappViewModel, state: UiState) {
    val d = state.decision
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("市场决策", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(d.ts, d.engine).joinToString(" · ").ifEmpty { "暂无数据" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }

        item {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "市场状态：${d.marketRegime ?: "暂无数据"}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!d.regimeDetail.isNullOrBlank()) {
                        Text(d.regimeDetail, style = MaterialTheme.typography.bodySmall)
                    }
                    val pa = d.positionAdvice
                    if (pa.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pa.entries.joinToString("　") { "${it.key} ${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                        )
                    }
                }
            }
        }

        if (d.cards.isNotEmpty()) {
            item { SectionHeader("决策卡") }
            items(d.cards, key = { it.code }) { c -> DecisionCardView(c, vm) }
        } else {
            item {
                Text("暂无数据：上游未产出或文件不可达。", color = Color.Gray)
            }
        }

        if (d.avoid.isNotEmpty()) {
            item { SectionHeader("回避名单") }
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Text(
                        d.avoid.take(20).joinToString("、") { it.toString() },
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecisionCardView(c: DecisionCard, vm: SkappViewModel) {
    val pct = c.pctChg
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> Color(0xFFF85149)
        pct < 0 -> Color(0xFF3FB950)
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(c.code) },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(c.name ?: c.code, fontWeight = FontWeight.Medium)
                    Text(c.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        c.price?.let { String.format("%.2f", it) } ?: "暂无数据",
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        pct?.let { String.format("%.2f%%", it) } ?: "—",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                c.composite?.let {
                    Text("综合 ${String.format("%.2f", it)}", style = MaterialTheme.typography.bodySmall)
                }
                c.action?.let { Text("动作 $it", style = MaterialTheme.typography.bodySmall) }
                c.confidence?.let {
                    Text("置信 ${String.format("%.2f", it)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (c.entry != null || c.stop != null || c.target != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "入场 ${c.entry?.let { String.format("%.2f", it) } ?: "暂无"}　" +
                        "止损 ${c.stop?.let { String.format("%.2f", it) } ?: "暂无"}　" +
                        "目标 ${c.target?.let { String.format("%.2f", it) } ?: "暂无"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (c.holdingDays != null) {
                Text("持有 ${c.holdingDays} 天", style = MaterialTheme.typography.bodySmall)
            }
            if (!c.risk.isNullOrBlank()) {
                Text("风险：${c.risk}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922))
            }
            val reasons = c.reasons.filter { it.isNotBlank() }
            if (reasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                reasons.take(4).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SectionHeader(t: String) {
    Column {
        Spacer(Modifier.height(4.dp))
        Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        HorizontalDivider()
    }
}
