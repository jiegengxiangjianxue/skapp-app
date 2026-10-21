package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 实时总结 Tab —— 对应网页版「📰 实时总结」。
 * 日报全文（daily_brief.text）+ 今日推股验证（latest.picks）。
 */
@Composable
fun SummaryScreen(vm: SkappViewModel, state: UiState) {
    val s = state.summary
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("实时总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(s["date"]?.toString(), s["session"]?.toString(), s["ts"]?.toString())
                        .joinToString(" · ").ifEmpty { "时点暂无" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            Text(
                "刷新",
                Modifier.clickable { vm.loadSummary() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (s["status"] == "insufficient") {
            Text("暂无数据：${s["reason"] ?: "总结源不可达"}", color = Color.Gray)
            return@Column
        }

        // 日报全文
        val text = s["text"]?.toString()
        if (!text.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text("日报", fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    // 简易 markdown 渲染：# 标题 / - 列表 / 普通段落
                    text.lines().forEach { line ->
                        val t = line.trim()
                        when {
                            t.isEmpty() -> Spacer(Modifier.height(4.dp))
                            t.startsWith("#") -> Text(
                                t.trimStart('#', ' '),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            t.startsWith("-") || t.startsWith("•") || t.startsWith("*") -> Text(
                                "· ${t.trimStart('-', '*', ' ')}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            t.startsWith(">") -> Text(
                                t.trimStart('>', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            else -> Text(t, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // 今日推股验证
        @Suppress("UNCHECKED_CAST")
        val picks = (s["picks"] as? List<Map<String, Any?>>) ?: emptyList()
        if (picks.isNotEmpty()) {
            Text("今日推股验证（${picks.size}）", fontWeight = FontWeight.SemiBold)
            picks.take(30).forEach { p ->
                val code = (p["code"] ?: p["symbol"])?.toString() ?: return@forEach
                val pct = (p["pctChg"] ?: p["chg_pct"]) as? Number
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(code) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text((p["name"] ?: code).toString(), fontWeight = FontWeight.Medium)
                            Text(
                                code,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            pct?.let {
                                Text(
                                    String.format("%+.2f%%", it.toDouble()),
                                    color = when {
                                        it.toDouble() > 0 -> Color(0xFFF85149)
                                        it.toDouble() < 0 -> Color(0xFF3FB950)
                                        else -> Color.Gray
                                    },
                                )
                            }
                            (p["score"] as? Number)?.let {
                                Text(
                                    "分 ${String.format("%.2f", it.toDouble())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
