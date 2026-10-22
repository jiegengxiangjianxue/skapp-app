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

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

/**
 * 看板 Tab —— 对应网页版「📊 看板」。
 * 指数 · 涨跌统计 · 涨跌幅榜 · 情绪 · 涨停池 · 板块热度。
 */
@Composable
fun BoardScreen(vm: SkappViewModel, state: UiState) {
    val b = state.board
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("看板", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        (b["ts"]?.toString() ?: "时点暂无"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                Text(
                    "刷新",
                    Modifier.clickable { vm.loadBoard() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 市场状态机（进攻/防守）—— 决策根基，置顶
        state.marketState?.let { ms ->
            item { MarketStateCard(ms) }
        }

        // 云端数据源健康度（190 源合并产物）
        state.allSourcesSummary?.let { s ->
            item {
                Text(
                    "数据源：$s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // 首屏加载中：骨架提示，避免白屏
        if (state.loading && state.quotes.isEmpty() && b.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.height(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text("  正在拉取行情与看板数据…", color = Color.Gray)
                    }
                }
            }
        }

        // 自选股实时报价（原行情 Tab 合入看板）
        if (state.quotes.isNotEmpty()) {
            item { SectionTitle("自选行情 · ${state.marketPhase}" + if (state.autoRefresh) " · 自动刷新" else " · 休市停表") }
            items(state.quotes, key = { "q_" + it.code }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(row.code) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(row.name.ifEmpty { row.code }, fontWeight = FontWeight.Medium)
                            Text(
                                row.display,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                row.price?.let { String.format("%.2f", it) } ?: "暂无",
                                color = pctColor(row.pctChg),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                row.pctChg?.let { String.format("%.2f%%", it) } ?: "—",
                                color = pctColor(row.pctChg),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // 全球股市
        if (state.globalIndices.isNotEmpty()) {
            item { SectionTitle("全球股市") }
            items(state.globalIndices, key = { "gl_" + it.hashCode() }) { g ->
                val pct = (g["pctChg"] as? Number)?.toDouble()
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text((g["name"] ?: g["code"] ?: "未知").toString(), fontWeight = FontWeight.Medium)
                        Text(
                            pct?.let { String.format("%+.2f%%", it) } ?: "—",
                            color = pctColor(pct),
                        )
                    }
                }
            }
        }

        if (b["status"] == "insufficient") {
            item {
                Text(
                    "暂无数据：${b["reason"] ?: "看板源不可达"}",
                    color = Color.Gray,
                )
            }
            return@LazyColumn
        }

        // 主要指数
        @Suppress("UNCHECKED_CAST")
        val indices = (b["indices"] as? List<Map<String, Any?>>) ?: emptyList()
        if (indices.isNotEmpty()) {
            item { SectionTitle("主要指数") }
            items(indices, key = { "idx_" + it.hashCode() }) { idx ->
                val pct = (idx["pctChg"] ?: idx["pct"]) as? Number
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            (idx["name"] ?: idx["code"] ?: "未知").toString(),
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                ((idx["price"] ?: idx["close"]) as? Number)?.let { String.format("%.2f", it.toDouble()) } ?: "—",
                            )
                            Text(
                                pct?.let { String.format("%+.2f%%", it.toDouble()) } ?: "—",
                                color = pctColor(pct?.toDouble()),
                            )
                        }
                    }
                }
            }
        }

        // 涨跌统计
        @Suppress("UNCHECKED_CAST")
        val market = (b["market"] as? Map<String, Any?>) ?: emptyMap()
        if (market.isNotEmpty()) {
            item { SectionTitle("涨跌统计") }
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Text(
                        market.entries.joinToString("　") { (k, v) -> "${marketLabel(k)} ${v ?: "—"}" },
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 情绪/阶段
        b["phase"]?.let { ph ->
            item { SectionTitle("市场阶段") }
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Text(ph.toString(), Modifier.padding(12.dp))
                }
            }
        }

        // 涨幅榜
        @Suppress("UNCHECKED_CAST")
        val gainers = (b["gainers"] as? List<Map<String, Any?>>) ?: emptyList()
        if (gainers.isNotEmpty()) {
            item { SectionTitle("涨幅榜") }
            items(gainers.take(15), key = { "g_" + it.hashCode() }) { g ->
                val code = (g["code"] ?: g["symbol"])?.toString() ?: return@items
                val pct = (g["pctChg"] ?: g["pct"]) as? Number
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(code) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text((g["name"] ?: code).toString(), fontWeight = FontWeight.Medium)
                        Text(
                            pct?.let { String.format("%+.2f%%", it.toDouble()) } ?: "—",
                            color = pctColor(pct?.toDouble()),
                        )
                    }
                }
            }
        }

        // 跌幅榜
        @Suppress("UNCHECKED_CAST")
        val losers = (b["losers"] as? List<Map<String, Any?>>) ?: emptyList()
        if (losers.isNotEmpty()) {
            item { SectionTitle("跌幅榜") }
            items(losers.take(15), key = { "l_" + it.hashCode() }) { g ->
                val code = (g["code"] ?: g["symbol"])?.toString() ?: return@items
                val pct = (g["pctChg"] ?: g["pct"]) as? Number
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(code) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text((g["name"] ?: code).toString(), fontWeight = FontWeight.Medium)
                        Text(
                            pct?.let { String.format("%+.2f%%", it.toDouble()) } ?: "—",
                            color = pctColor(pct?.toDouble()),
                        )
                    }
                }
            }
        }

        // 涨停池
        @Suppress("UNCHECKED_CAST")
        val limitUp = (b["limit_up"] as? List<Map<String, Any?>>) ?: emptyList()
        if (limitUp.isNotEmpty()) {
            item { SectionTitle("涨停池（${limitUp.size}）") }
            items(limitUp.take(20), key = { "zt_" + it.hashCode() }) { g ->
                val code = (g["code"] ?: g["symbol"])?.toString() ?: return@items
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(code) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text((g["name"] ?: code).toString(), fontWeight = FontWeight.Medium)
                            Text(
                                code,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        val boards = (g["boards"] ?: g["industry"]) as? List<*>
                        if (!boards.isNullOrEmpty()) {
                            Text(
                                boards.take(2).joinToString("、") { it.toString() },
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

/** 市场状态卡：E 分 + 状态标签 + 5 部件得分 */
@Composable
private fun MarketStateCard(ms: com.skapp.app.data.MarketState) {
    val color = when {
        ms.state.startsWith("进攻") -> UP
        ms.state.startsWith("防守") -> DOWN
        else -> Color(0xFFD29922)
    }
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("市场状态", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        String.format("E=%+.1f", ms.E),
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        ms.state,
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "一致性 ${String.format("%.0f%%", ms.agreement * 100)}" +
                    if (ms.missingInputs.isNotEmpty()) " · 缺输入: ${ms.missingInputs.joinToString(", ")}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ms.parts.forEach { (k, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            String.format("%+.0f", v),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (v > 0) UP else if (v < 0) DOWN else Color.Gray,
                        )
                        Text(
                            partLabel(k),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                        )
                    }
                }
            }
        }
    }
}

private fun partLabel(k: String): String = when (k) {
    "market_phase" -> "阶段"
    "cycle_v2" -> "周期"
    "global" -> "全球"
    "win_loop" -> "胜负"
    "kelly" -> "凯利"
    else -> k
}

@Composable
private fun SectionTitle(t: String) {
    Column {
        Spacer(Modifier.height(6.dp))
        Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        HorizontalDivider()
    }
}

private fun pctColor(pct: Double?): Color = when {
    pct == null -> Color.Gray
    pct > 0 -> UP
    pct < 0 -> DOWN
    else -> Color.Gray
}

private fun marketLabel(k: String): String = when (k) {
    "advance" -> "涨"
    "decline" -> "跌"
    "limit_up" -> "涨停"
    "limit_down" -> "跌停"
    "turnover_yi" -> "成交亿"
    "turnover" -> "成交"
    else -> k
}
