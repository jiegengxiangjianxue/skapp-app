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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.skapp.app.data.ExtendedDataClient
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.Watchlist

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

/** 行情 Tab：自选股实时报价 + 搜索入口 */
@Composable
fun MarketScreen(vm: SkappViewModel, state: UiState) {
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            "自选行情 · " + state.marketPhase + if (state.autoRefresh) " · 自动刷新中" else " · 休市停表",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        state.quoteTs?.let {
            Text("报价时点：$it", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (!state.autoRefresh) {
            state.nextRefreshIn?.let { ms ->
                Text(
                    "下次自动刷新：${fmtUntil(ms)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD29922),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 数据源状态（证明真的接进来了）
        val sources = remember { ExtendedDataClient.sourceCatalog() }
        Text(
            "已接入数据源 ${sources.size} 个：" +
                sources.take(6).joinToString("、") { it.first } + " 等",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(Modifier.height(8.dp))

        // 全球股市（东财 ulist 批量）
        if (state.globalIndices.isNotEmpty()) {
            Text("全球股市", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.height(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.globalIndices, key = { "g_" + it.hashCode() }) { g ->
                    IndexCard(g)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // A 股指数
        if (state.aShareIndices.isNotEmpty()) {
            Text("A 股指数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.height(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.aShareIndices, key = { "a_" + it.hashCode() }) { g ->
                    IndexCard(g)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.search(it) },
            label = { Text("搜股票名字或代码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        if (state.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isNotBlank()) {
                if (state.searchResults.isEmpty()) {
                    item { Text("没有匹配的股票。", color = Color.Gray) }
                }
                items(state.searchResults, key = { it.code }) { s ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.openDetail(s.code); query = ""
                        },
                        colors = CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(s.name ?: s.code, fontWeight = FontWeight.Medium)
                                    Text(s.code, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "实时分析 ›",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // 搜索即触发本地引擎分析（点开详情时算，这里给提示）
                            Text(
                                "点击查看：实时行情 + 12 引擎 + 29 因子 + 日/周/月K + 资金流 + 板块",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            } else {
                items(Watchlist.codes, key = { it }) { code ->
                    val row = state.quotes.firstOrNull { it.code == code }
                    QuoteCard(row = row, code = code, onClick = { vm.openDetail(code) })
                }
            }
        }
    }
}

/** 指数卡片（全球 / A 股共用）：名称 + 现价 + 涨跌幅 */
@Composable
private fun IndexCard(idx: Map<String, Any?>) {
    val pct = (idx["pctChg"] as? Number)?.toDouble()
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> UP
        pct < 0 -> DOWN
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    (idx["name"] ?: idx["code"] ?: "未知").toString(),
                    fontWeight = FontWeight.Medium,
                )
                (idx["source"] as? String)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (idx["price"] as? Number)?.let { String.format("%.2f", it.toDouble()) } ?: "暂无数据",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    pct?.let { String.format("%+.2f%%", it) } ?: "—",
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 毫秒 → 「x 小时 y 分」 */
private fun fmtUntil(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "$h 小时 $m 分"
        else -> "$m 分钟"
    }
}

@Composable
private fun QuoteCard(row: com.skapp.app.ui.QuoteRow?, code: String, onClick: () -> Unit) {
    val pct = row?.pctChg
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> UP
        pct < 0 -> DOWN
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(row?.name ?: code, fontWeight = FontWeight.Medium)
                Text(row?.display ?: code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    row?.price?.let { String.format("%.2f", it) } ?: "暂无数据",
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
    }
}
