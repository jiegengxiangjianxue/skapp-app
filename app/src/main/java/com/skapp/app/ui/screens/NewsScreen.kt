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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 消息 Tab：移植站点的消息面四件套（hot_news / news_digest 情绪 /
 * news_impact 个股影响 / hot_themes 主线）。全部来自真实引擎产出。
 */
@Composable
fun NewsScreen(vm: SkappViewModel, state: UiState) {
    val n = state.news
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("消息面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    n.ts ?: "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }

        // 情绪摘要
        val dg = n.digest
        if (dg.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("市场情绪", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val total = dg["total"]
                        val mood = dg["global_mood"]
                        val score = dg["global_score"]
                        Text(
                            buildString {
                                append("情绪：${mood ?: "暂无数据"}")
                                if (score != null) append("（评分 $score）")
                                if (total != null) append("　共 $total 条")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val stale = dg["stale"]
                        if (stale == true) {
                            Text(
                                "注意：上游标记为 stale（数据可能已过期）",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD29922),
                            )
                        }
                    }
                }
            }
        }

        // 主线题材
        if (n.themes.isNotEmpty()) {
            item { SectionHeader("主线题材") }
            items(n.themes, key = { "t_" + it.hashCode() }) { t ->
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            (t["name"] ?: t["theme"] ?: "未命名题材").toString(),
                            fontWeight = FontWeight.Medium,
                        )
                        val stocks = t["stocks"]
                        if (stocks is List<*>) {
                            Text(
                                stocks.take(6).joinToString("、") { s ->
                                    (s as? Map<*, *>)?.get("name")?.toString()
                                        ?: s.toString()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                        }
                    }
                }
            }
        }

        // 个股影响
        if (n.impacts.isNotEmpty()) {
            item { SectionHeader("个股消息影响") }
            items(n.impacts, key = { "i_" + it.hashCode() }) { imp ->
                val code = (imp["code"] ?: "").toString()
                val name = (imp["name"] ?: "").toString().ifEmpty { code }
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (code.isNotEmpty()) Modifier.clickable { vm.openDetail(code) } else Modifier),
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text(
                                code,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        Column {
                            val dir = imp["direction"]
                            if (dir != null) {
                                Text(
                                    dir.toString(),
                                    color = when (dir.toString()) {
                                        "positive", "利好", "bull" -> Color(0xFFF85149)
                                        "negative", "利空", "bear" -> Color(0xFF3FB950)
                                        else -> Color.Gray
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 快讯列表
        if (n.items.isNotEmpty()) {
            item { SectionHeader("快讯") }
            items(n.items, key = { "n_" + it.hashCode() }) { it2 ->
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            (it2["title"] ?: "暂无标题").toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val src = it2["source_name"] ?: it2["source"]
                        val ts = it2["updated"]
                        if (src != null || ts != null) {
                            Text(
                                listOfNotNull(src?.toString(), ts?.toString()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            }
        }

        if (n.items.isEmpty() && n.themes.isEmpty() && n.impacts.isEmpty() && n.digest.isEmpty()) {
            item {
                Text(
                    "暂无数据：消息面引擎产出不可达。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
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
