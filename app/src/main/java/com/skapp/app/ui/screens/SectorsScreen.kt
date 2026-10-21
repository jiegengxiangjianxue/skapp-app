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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.Board
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 板块 Tab：移植站点的行业/概念面板。
 * - 行业列表 / 概念列表（db-market sector）
 * - 点板块看成分股（预生成 Top 30）
 */
@Composable
fun SectorsScreen(vm: SkappViewModel, state: UiState) {
    val s = state.sectors
    var tab by remember { mutableStateOf(0) }   // 0 行业 / 1 概念
    var openBk by remember { mutableStateOf<String?>(null) }

    val boards: List<Board> = if (tab == 0) s.industries else s.concepts

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("板块", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            (s.ts ?: "暂无数据") + if (openBk != null) "　·　$openBk" else "",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("行业 ${s.industries.size}", tab == 0) { tab = 0; openBk = null }
            FilterChip("概念 ${s.concepts.size}", tab == 1) { tab = 1; openBk = null }
            if (openBk != null) FilterChip("← 返回列表", true) { openBk = null }
        }
        Spacer(Modifier.height(8.dp))

        val detail = openBk?.let { bk -> s.boardMembers[bk] }
        if (detail != null) {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${detail["name"] ?: openBk}（$openBk）",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "成分股共 ${detail["n"] ?: "暂无"} 只",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    val members = detail["members"]
                    if (members is List<*>) {
                        members.forEach { m ->
                            val mm = m as? Map<*, *>
                            val code = mm?.get("code")?.toString() ?: return@forEach
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { vm.openDetail(code) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(mm?.get("name")?.toString() ?: code)
                                Text(code, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text("成分股：暂无数据", color = Color.Gray)
                    }
                }
            }
            return@Column
        }

        if (boards.isEmpty()) {
            Text("暂无数据：板块列表不可达。", color = Color.Gray)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(boards, key = { it.bk }) { b ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { if (s.boardMembers.containsKey(b.bk)) openBk = b.bk },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name ?: b.bk, fontWeight = FontWeight.Medium)
                            Text(
                                b.bk + "　" + (b.nStocks?.let { "$it 只" } ?: "只数暂无"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        b.totalMktcap?.let {
                            Text(
                                "市值 ${fmtYi(it)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 元 → 亿元 */
private fun fmtYi(v: Double): String =
    if (v >= 1e8) String.format("%.0f 亿", v / 1e8) else String.format("%.0f 万", v / 1e4)
