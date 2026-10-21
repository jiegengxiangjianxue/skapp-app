package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.PickSignal
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 推股 Tab：顶栏切换「指标共振 / 多维评分 / 涨停 / 回避」，
 * 不再靠滑动翻页。市场概况含全球股市 + A 股指数涨跌。
 */
@Composable
fun PicksScreen(vm: SkappViewModel, state: UiState) {
    val p = state.picks
    var tab by remember { mutableStateOf(0) }

    val tabs = listOf(
        "指标共振" to p.signals,
        "多维评分" to p.values,
        "涨停" to p.limitUp,
    )

    Column(Modifier.fillMaxWidth()) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "推股",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            tabs.forEachIndexed { i, (name, list) ->
                TopTab(name, list.size, tab == i) { tab = i }
            }
        }
        HorizontalDivider()

        // ── 市场概况（含全球股市 + A 股指数）──
        MarketOverview(state)

        HorizontalDivider()

        // ── 列表 ──
        val current = tabs.getOrNull(tab)?.second ?: emptyList()
        if (current.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无数据：上游未产出或文件不可达。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${tabs[tab].first} · ${current.size} 只　·　${listOfNotNull(p.date, p.session, p.ts).joinToString(" · ").ifEmpty { "时点暂无" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                items(current, key = { it.code + "_" + tabs[tab].first }) { s ->
                    PickCard(s, tabs[tab].first, vm, state)
                }
            }
        }
    }
}

@Composable
private fun TopTab(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
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

/** 市场概况：A 股涨跌统计 + 成交 + A股指数 + 全球指数 */
@Composable
private fun MarketOverview(state: UiState) {
    val p = state.picks
    val m = p.market
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.isNotEmpty()) {
            Text("市场概况", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                m.entries.joinToString("　") { (k, v) -> "${marketLabel(k)} ${fmt(v)}" }
                    .ifEmpty { "暂无数据" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.aShareIndices.isNotEmpty()) {
            Text(
                "A 股指数：" + state.aShareIndices.joinToString("　") { g ->
                    val name = (g["name"] ?: g["code"] ?: "").toString()
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    "$name ${pct?.let { String.format("%+.2f%%", it) } ?: "—"}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.globalIndices.isNotEmpty()) {
            Text(
                "全球股市：" + state.globalIndices.joinToString("　") { g ->
                    val name = (g["name"] ?: g["code"] ?: "").toString()
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    "$name ${pct?.let { String.format("%+.2f%%", it) } ?: "—"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
            )
        }
    }
}

@Composable
private fun PickCard(s: PickSignal, group: String, vm: SkappViewModel, state: UiState) {
    // 实时报价优先；休市时回落到云端快照价（快照即收盘价）
    val live = state.pickQuotes[s.code]
    val price = live?.price ?: s.price
    val pct = live?.pctChg ?: s.pctChg
    val priceTag = if (live != null) "实时" else "快照"
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
                        price?.let { String.format("%.2f", it) } ?: "暂无数据",
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        (pct?.let { String.format("%.2f%%", it) } ?: "—") + "　$priceTag",
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
