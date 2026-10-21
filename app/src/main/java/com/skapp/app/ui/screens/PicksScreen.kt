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
import com.skapp.app.data.PickSignal
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/** 推股 Tab：引擎推股清单（signals + values） */
@Composable
fun PicksScreen(vm: SkappViewModel, state: UiState) {
    val p = state.picks
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("今日推股", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(p.date, p.session, p.ts).joinToString(" · ").ifEmpty { "暂无数据" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }

        val m = p.market
        if (m.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("市场概况", fontWeight = FontWeight.Medium)
                        Text(
                            m.entries.joinToString("　") { (k, v) ->
                                "${marketLabel(k)} ${fmt(v)}"
                            }.ifEmpty { "暂无数据" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (p.signals.isNotEmpty()) {
            item { SectionHeader("指标共振（signals）") }
            items(p.signals, key = { "s_" + it.code }) { s -> PickCard(s, vm) }
        }
        if (p.values.isNotEmpty()) {
            item { SectionHeader("多维评分（values）") }
            items(p.values, key = { "v_" + it.code }) { s -> PickCard(s, vm) }
        }
        if (p.signals.isEmpty() && p.values.isEmpty()) {
            item {
                Text(
                    "暂无数据：上游未产出或文件不可达。",
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

@Composable
private fun PickCard(s: PickSignal, vm: SkappViewModel) {
    val pct = s.pctChg
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> Color(0xFFF85149)
        pct < 0 -> Color(0xFF3FB950)
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(s.code) },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(s.name ?: s.code, fontWeight = FontWeight.Medium)
                    Text(s.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        s.price?.let { String.format("%.2f", it) } ?: "暂无数据",
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
            s.score?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "评分 ${String.format("%.2f", it)}" + (s.engine?.let { e -> "　·　$e" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val why = s.why.filter { it.isNotBlank() }
            if (why.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                why.take(3).forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            val tech = s.detail?.technical
            if (!tech.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tech.entries.filter { it.value != null }
                        .joinToString("　") { "${it.key.uppercase()} ${String.format("%.2f", it.value!!)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            if (s.detail != null && s.detail.status == "insufficient") {
                Spacer(Modifier.height(2.dp))
                Text(
                    "个股详情：insufficient",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD29922),
                )
            }
        }
    }
}

private fun marketLabel(k: String): String = when (k) {
    "advance" -> "涨"
    "decline" -> "跌"
    "limit_up" -> "涨停"
    "limit_down" -> "跌停"
    "turnover_yi" -> "成交(亿)"
    "turnover" -> "成交"
    else -> k
}

private fun fmt(v: Any?): String = when (v) {
    null -> "暂无数据"
    is Number -> String.format("%.2f", v.toDouble())
    else -> v.toString()
}
