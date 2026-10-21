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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 多池选股 Tab —— 对应网页版「🎯 多池选股」。
 * 顶栏切换：推荐池 · 板块池 · 周期池 · 低位池 · 综合池 · 涨停归因 · 资金流
 * 每池点个股 → 走完整详情（实时行情 + 12 引擎 + 29 因子）。
 */
@Composable
fun PoolsScreen(vm: SkappViewModel, state: UiState) {
    var tab by remember { mutableStateOf(0) }
    val pools = state.pools
    val current = pools.getOrNull(tab)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("多池选股", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.poolsLoading) CircularProgressIndicator()
            else Text(
                "刷新",
                Modifier.clickable { vm.loadPools() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 池切换（横滑，池名 + 计数）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            pools.forEachIndexed { i, p ->
                PoolChip(p.name, p.items.size, tab == i) { tab = i }
            }
        }
        HorizontalDivider()

        if (pools.isEmpty()) {
            if (state.poolsLoading) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在拉取多池数据…", color = Color.Gray)
                }
            } else {
                Text(
                    "暂无数据：多池源不可达。",
                    Modifier.padding(16.dp),
                    color = Color.Gray,
                )
            }
            return@Column
        }

        val pool = current ?: return@Column
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text(
                        pool.subtitle ?: pool.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    if (pool.kpis.isNotEmpty()) {
                        Text(
                            pool.kpis.entries.joinToString("　") { "${it.key} ${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                        )
                    }
                    Text(
                        "时点：${pool.ts ?: "暂无"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }

            if (pool.status == "insufficient") {
                item {
                    Text(
                        "该池暂无数据：${pool.reason ?: "上游不可达"}",
                        color = Color(0xFFD29922),
                    )
                }
            } else if (pool.items.isEmpty()) {
                item { Text("该池当前为空。", color = Color.Gray) }
            } else {
                items(pool.items, key = { pool.id + "_" + it.code }) { it2 ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(it2.code) },
                        colors = CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        it2.name ?: it2.code,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        it2.code,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    it2.score?.let {
                                        Text(
                                            "分 ${String.format("%.2f", it)}",
                                            fontWeight = FontWeight.SemiBold,
                                            color = scoreColor(it),
                                        )
                                    }
                                    it2.pctChg?.let {
                                        Text(
                                            String.format("%+.2f%%", it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = pctColorB(it),
                                        )
                                    }
                                }
                            }
                            // 该池特有的字段摘要（如封板时间/连板数/主力净额）
                            val extras = it2.extra.entries.filter { (k, _) ->
                                k !in setOf("code", "name", "symbol", "s", "n", "score",
                                    "composite", "strength", "price", "close", "pctChg", "chg_pct", "pct")
                            }.take(4)
                            if (extras.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    extras.joinToString("　") { (k, v) -> "$k ${v ?: "—"}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoolChip(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }, colors = CardDefaults.cardColors()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                name,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "$count",
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun scoreColor(s: Double): Color = when {
    s >= 70 -> Color(0xFFF85149)
    s >= 55 -> Color(0xFFD29922)
    else -> Color.Gray
}

private fun pctColorB(pct: Double): Color = when {
    pct > 0 -> Color(0xFFF85149)
    pct < 0 -> Color(0xFF3FB950)
    else -> Color.Gray
}
