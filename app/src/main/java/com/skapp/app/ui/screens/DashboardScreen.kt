package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.sp
import com.skapp.app.data.LocalScreener
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 看板首页：盘面解读、市场总览与个股查询。
 * 板块池与涨停池统一从多池页面进入，避免重复列表。
 */
private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

@Composable
fun DashboardScreen(vm: SkappViewModel, state: UiState) {
    val b = state.board
    val market = b["market"] as? Map<*, *> ?: emptyMap<String, Any?>()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 顶栏：标题 + 时点 + 刷新 ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("看板", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        (b["ts"]?.toString() ?: "时点暂无") + " · 网页版 panel-board",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Text("刷新", Modifier.clickable { vm.loadBoard() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── KPI 8 格：涨停/跌停/炸板/封板率/上涨/下跌/成交/阶段（网页版 kpis 对齐）──
        item {
            val limitUp = numOf(market["limit_up"])
            val limitDown = numOf(market["limit_down"])
            val broken = numOf(market["broken"])
            val advance = numOf(market["advance"])
            val decline = numOf(market["decline"])
            val turnover = numOf(market["turnover_yi"] ?: (market["turnover"] as? Map<*, *>)?.get("total_yi"))
            val phase = market["phase"]?.toString() ?: "—"
            val phaseScore = numOf(market["phase_score"])
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("涨停", limitUp?.toInt()?.toString(), UP, Modifier.weight(1f))
                    KpiCard("跌停", limitDown?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("炸板", broken?.toInt()?.toString(), Color(0xFFD29922), Modifier.weight(1f))
                    KpiCard("上涨", advance?.toInt()?.toString(), UP, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("下跌", decline?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("成交(亿)", turnover?.let { String.format("%.0f", it) }, Color.LightGray, Modifier.weight(1f))
                    KpiCard("阶段", phase, Color(0xFF58A6FF), Modifier.weight(1f))
                    KpiCard("阶段分", phaseScore?.let { String.format("%.1f", it) }, Color(0xFF58A6FF), Modifier.weight(1f))
                }
                // 宽度热力条（涨跌家数比）
                if (advance != null && decline != null && advance + decline > 0) {
                    val upPct = (advance / (advance + decline)).toFloat()
                    Row(
                        Modifier.fillMaxWidth().height(8.dp).background(DOWN, MaterialTheme.shapes.small)
                    ) {
                        Box(Modifier.weight(upPct.coerceAtLeast(0.01f)).fillMaxSize().background(UP, MaterialTheme.shapes.small))
                        Box(Modifier.weight((1 - upPct).coerceAtLeast(0.01f)).fillMaxSize())
                    }
                    Text(
                        "市场宽度 上涨 ${advance.toInt()} / 下跌 ${decline.toInt()}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
        }

        // 盘面解读只保留看板这一处；实时总结复用同一份 summary，不再重复展示正文。
        item { CommentaryCard(state.summary) }

        item {
            if (state.marketPulse.isNotEmpty() || state.marketPulseLoading) {
                SectionTitle("本地实时异动")
                Text(
                    "全市场预筛 · ${state.marketPulseTs ?: "暂无时点"} · 规则归因前置，深度分析需点选个股",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                if (state.marketPulseLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                state.marketPulse.take(10).forEach { row ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(row.code) },
                        colors = CardDefaults.cardColors(),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.name} ${row.code}", fontWeight = FontWeight.Medium)
                                Text(
                                    "板块 ${row.board ?: "暂无数据"}",
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                                )
                                Text(
                                    "涨幅 ${row.pctChg?.let { "%+.2f%%".format(it) } ?: "暂无数据"} · 涨速 ${row.speed?.let { "%+.2f%%".format(it) } ?: "首轮暂无"} · 判断 ${outlookText(row.outlook)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(row.outlookReason ?: "暂无推断依据", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Text("详情 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            DashboardNewsSection(state, vm)
        }

        // ── 个股查（panel-stock）：搜索入口 ──
        item { StockSearchCard(vm, state) }

        // ── 指数与全球市场（A 股 + 全球，原两区已并入）──
        item {
            if (state.aShareIndices.isNotEmpty() || state.globalIndices.isNotEmpty()) {
                SectionTitle("指数与全球市场")
                (state.aShareIndices + state.globalIndices).forEach { g ->
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    val price = (g["price"] as? Number)?.toDouble()
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((g["name"] ?: g["code"])?.toString() ?: "—",
                                fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(price?.let { String.format("%.2f", it) } ?: "—",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(pct?.let { String.format("%+.2f%%", it) } ?: "—",
                                    color = if ((pct ?: 0.0) >= 0) UP else DOWN,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── 市场状态机（进攻/防守）──
        state.marketState?.let { ms ->
            item { MarketStateCard(ms) }
        }

        // ── 云端数据源健康度 ──
        state.allSourcesSummary?.let { s ->
            item {
                Text(
                    "数据源 $s",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun outlookText(value: String): String = when (value) {
    "bullish" -> "偏多"
    "bearish" -> "偏空"
    "watch_up" -> "观察偏强"
    "watch_down" -> "观察偏弱"
    else -> "数据不足"
}

private fun numOf(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

@Composable
private fun KpiCard(label: String, value: String?, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(2.dp))
            Text(value ?: "—", fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CommentaryCard(summary: Map<String, Any?>) {
    val t = (summary["premarket"] ?: summary["text"])?.toString()?.takeIf { it.isNotBlank() } ?: return
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("盘前分析", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            t.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(">") }
                .take(18)
                .forEach {
                    Text(it.trimStart('#').trim(),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
        }
    }
}

@Composable
private fun DashboardNewsSection(state: UiState, vm: SkappViewModel) {
    val rows = state.news.items.take(8)
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("实时异动", fontWeight = FontWeight.SemiBold)
            Text("盘前分析后的实时消息异动；板块和涨停数据在多池选股查看。",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (rows.isEmpty() && state.newsCalcs.isEmpty()) {
                Text("暂无数据：实时消息异动尚未返回。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    val title = (row["title"] ?: row["newsTitle"] ?: row["content"] ?: "暂无标题").toString()
                    val meta = listOfNotNull(row["source"]?.toString(), row["time"]?.toString(), row["verdict"]?.toString())
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    extractNewsCodes(row).take(5).forEach { code ->
                        Text("影响个股：$code", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { vm.openDetail(code) }.padding(vertical = 2.dp))
                    }
                }
            } else {
                Text("${state.newsCalcs.size} 条实时异动已计算，原始消息正文暂无。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

private fun extractNewsCodes(row: Map<String, Any?>): List<String> {
    val raw = row["codes"] ?: row["impactCodes"] ?: row["affected_stocks"] ?: row["stocks"] ?: return emptyList()
    return when (raw) {
        is List<*> -> raw.mapNotNull { value ->
            when (value) {
                is Map<*, *> -> (value["code"] ?: value["symbol"] ?: value["stock_code"])?.toString()
                else -> value?.toString()
            }
        }
        else -> listOf(raw.toString())
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

@Composable
private fun StockSearchCard(vm: SkappViewModel, state: UiState) {
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("个股查（panel-stock）", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; searched = false },
                    label = { Text("输入股票代码或名称，如 600519") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        searched = true
                        vm.search(query)
                    },
                    enabled = query.isNotBlank(),
                ) {
                    Text("搜索")
                }
            }
            if (searched && state.searchResults.isNotEmpty()) {
                state.searchResults.take(6).forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.openDetail(s.code) },
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${s.name ?: s.code}", fontWeight = FontWeight.Medium)
                        Text("查看 ›", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (searched) {
                Text("暂无匹配股票，请检查代码或名称。", color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyNote(msg: String) {
    Text(msg, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}

/** 本文件的 SectionTitle（原引 BoardScreen 的 private 版本，编译报 private-in-file） */
@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** 市场状态卡（简化版；原引 BoardScreen 的 private MarketStateCard） */
@Composable
private fun MarketStateCard(ms: com.skapp.app.data.MarketState) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("市场状态机", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${ms.state} · E=${String.format("%.1f", ms.E)} · agreement=${String.format("%.0f", ms.agreement * 100)}%",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
    }
}
