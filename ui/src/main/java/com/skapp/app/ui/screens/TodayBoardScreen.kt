package com.skapp.app.ui.screens

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
import androidx.compose.ui.unit.sp
import com.skapp.app.data.MarketPulseRow
import com.skapp.app.ui.QuoteRow
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.theme.SkColors

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown
private val FLAT = SkColors.marketFlat

/**
 * 今日实时看盘：自选行情 + 指数 + 涨停统计 + 异动榜。
 *
 * 数据来源：
 *   - `state.quotes`     自选股实时报价（`MarketStateHolder.startQuoteLoop` 每 5s 刷新）
 *   - `state.aShareIndices` A 股指数
 *   - `state.board["market"]` 看板 KPI（涨停/跌停/炸板/涨跌家数）
 *   - `state.marketPulse` 异动榜（`startMarketPulseLoop` 每 30s 刷新）
 */
@Composable
fun TodayBoardScreen(vm: SkappViewModel, state: UiState) {
    val quotes = state.quotes
    val marketPulse = state.marketPulse
    val aShare = state.aShareIndices
    val heartbeatAge = if (state.heartbeatAt > 0L) {
        val age = ((System.currentTimeMillis() - state.heartbeatAt) / 1000).coerceAtLeast(0)
        "心跳 ${age}s 前 · ${state.heartbeatLabel.takeIf { it.isNotBlank() } ?: "运行中"}"
    } else {
        "心跳未启动"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 顶栏：标题 + 时点 + 刷新 ──
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "今日实时看盘",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${state.quoteTs ?: "时点暂无"} · $heartbeatAge",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
                Text(
                    "刷新",
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { vm.loadBoard(force = true) },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ── 指数栏 ──
        if (aShare.isNotEmpty()) {
            item { IndexCard(aShare) }
        }

        // ── KPI 行：涨停/跌停/炸板/上涨/下跌/成交 ──
        val b = state.board
        val market = b["market"] as? Map<*, *> ?: emptyMap<String, Any?>()
        item {
            KpiGrid(
                items = listOf(
                    KpiCell("涨停", numOf(market["涨停"]), UP),
                    KpiCell("跌停", numOf(market["跌停"]), DOWN),
                    KpiCell("炸板", numOf(market["炸板"]), SkColors.warn),
                    KpiCell("上涨", numOf(market["涨"]), UP),
                    KpiCell("下跌", numOf(market["跌"]), DOWN),
                    KpiCell("成交(亿)", numOf(market["成交额(亿)"]), SkColors.textTertiary),
                ),
            )
        }

        // ── 自选行情 ──
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自选行情", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (state.marketPulseLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(12.dp), strokeWidth = 1.5.dp)
                }
            }
        }

        if (quotes.isEmpty()) {
            item {
                Text("自选行情加载中…", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
        } else {
            items(quotes, key = { it.code }) { q ->
                QuoteCard(q)
            }
        }

        // ── 异动榜 ──
        if (marketPulse.isNotEmpty()) {
            item {
                Text(
                    "异动榜 (${state.marketPulseScope})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(marketPulse, key = { it.code }) { row ->
                PulseCard(row)
            }
            if (state.pulseDropped.isNotEmpty()) {
                item {
                    Text(
                        "掉出：${state.pulseDropped.take(3).joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
            }
        }

        // ── 加载中 ──
        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun numOf(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

// ─────────── 指数卡 ───────────

@Composable
private fun IndexCard(indices: List<Map<String, Any?>>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("A 股指数", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            indices.take(6).forEach { item ->
                val name = (item["name"] ?: item["code"])?.toString() ?: "—"
                val price = numOf(item["price"])
                val pct = numOf(item["pctChg"])
                val color = pct?.let { if (it >= 0) UP else DOWN } ?: FLAT
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(price?.let { "%.2f".format(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, color = color)
                        Text(pct?.let { "%+.2f%%".format(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, color = color)
                    }
                }
            }
        }
    }
}

// ─────────── KPI 格 ───────────

private data class KpiCell(val label: String, val value: Any?, val color: Color)

@Composable
private fun KpiGrid(items: List<KpiCell>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 每行 3 格
        for (i in 0 until (items.size + 2) / 3) {
            val start = i * 3
            val end = minOf(items.size, start + 3)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.subList(start, end).forEach { kpi ->
                    KpiCardCell(kpi.label, kpi.value, kpi.color, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KpiCardCell(label: String, value: Any?, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(value?.toString() ?: "—", fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        }
    }
}

// ─────────── 自选报价卡 ───────────

@Composable
private fun QuoteCard(q: QuoteRow) {
    val color = q.pctChg?.let { if (it >= 0) UP else DOWN } ?: FLAT
    Card(
        modifier = Modifier.fillMaxWidth().clickable { /* 详情在选股 tab 展开 */ },
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(q.name.ifEmpty { q.code },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(q.code, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                if (q.stale) {
                    Text("stale", style = MaterialTheme.typography.bodySmall, color = SkColors.warn)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    q.price?.let { "%.2f".format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    q.pctChg?.let { "%+.2f%%".format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

// ─────────── 异动榜卡 ───────────

@Composable
private fun PulseCard(row: MarketPulseRow) {
    val color = row.pctChg?.let { if (it >= 0) UP else DOWN } ?: FLAT
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(row.name.ifEmpty { row.code },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(row.code, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                row.price?.let {
                    Text("%.2f".format(it),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    row.pctChg?.let { "%+.2f%%".format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                row.volRatio?.let {
                    Text("量比 ${"%.1f".format(it)}",
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
            }
        }
    }
}
