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
import com.skapp.app.data.DecisionCard
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/** 决策 Tab：市场状态 + 决策卡（含入场区间/止损/目标/风险） */
@Composable
fun DecisionScreen(vm: SkappViewModel, state: UiState) {
    val d = state.decision
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("市场决策", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(d.ts, d.engine).joinToString(" · ").ifEmpty { "暂无数据" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }

        item {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "市场状态：${d.marketRegime ?: "暂无数据"}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    val regimeDetail = d.regimeDetail
                    if (!regimeDetail.isNullOrBlank()) {
                        Text(regimeDetail, style = MaterialTheme.typography.bodySmall)
                    }
                    val pa = d.positionAdvice
                    if (pa.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val baseCap = (pa["cap"] as? Number)?.toDouble()
                        val label = pa["label"]?.toString()?.takeIf { it.isNotBlank() }
                        val reason = pa["reason"]?.toString()?.takeIf { it.isNotBlank() }
                        Text(
                            "仓位建议：" + (label ?: "暂无标签") +
                                (baseCap?.let { "　上限 ${String.format("%.0f%%", it * 100)}" } ?: ""),
                            fontWeight = FontWeight.Medium,
                        )
                        if (reason != null) {
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        // 聪明钱调档：只有 ETF 份额序列成熟（>=20 交易日）才允许改仓位上限
                        val (adjCap, adjReason) = state.smartMoney?.capAdjust(baseCap) ?: (null to "")
                        if (adjReason.isNotBlank()) {
                            val changed = adjCap != null && baseCap != null && adjCap != baseCap
                            Text(
                                "ETF 份额信号：$adjReason",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (changed) MaterialTheme.colorScheme.primary else Color.Gray,
                            )
                            if (changed) {
                                Text(
                                    "调整后仓位上限：${String.format("%.0f%%", adjCap!! * 100)}"
                                        + "（基准 ${String.format("%.0f%%", baseCap!! * 100)}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 聪明钱：宽基 ETF 份额跟踪（份额口径来自 push2 实时快照，K 线换手率不可用于增减判断）──
        item { SmartMoneyCard(state, vm) }

        if (d.cards.isNotEmpty()) {
            item { SectionHeader("决策卡") }
            // 主板红线兜底：上游若混入 300/301/688/北交所不渲染
            items(d.cards.filter { isMainBoardPick(it.code) }, key = { it.code }) { c -> DecisionCardView(c, vm) }
        } else {
            item {
                Text("暂无数据：上游未产出或文件不可达。", color = Color.Gray)
            }
        }

        if (d.avoid.isNotEmpty()) {
            item { SectionHeader("回避名单") }
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Text(
                        d.avoid.take(20).joinToString("、") { it.toString() },
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecisionCardView(c: DecisionCard, vm: SkappViewModel) {
    val pct = c.pctChg
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> Color(0xFFF85149)
        pct < 0 -> Color(0xFF3FB950)
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(c.code) },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(c.name ?: c.code, fontWeight = FontWeight.Medium)
                    Text(c.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        c.price?.let { String.format("%.2f", it) } ?: "暂无数据",
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

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                c.composite?.let {
                    Text("综合 ${String.format("%.2f", it)}", style = MaterialTheme.typography.bodySmall)
                }
                c.action?.let { Text("动作 $it", style = MaterialTheme.typography.bodySmall) }
                c.confidence?.let {
                    Text("置信 ${String.format("%.2f", it)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (c.entry != null || c.stop != null || c.target != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "入场 ${c.entry?.let { String.format("%.2f", it) } ?: "暂无"}　" +
                        "止损 ${c.stop?.let { String.format("%.2f", it) } ?: "暂无"}　" +
                        "目标 ${c.target?.let { String.format("%.2f", it) } ?: "暂无"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (c.holdingDays != null) {
                Text("持有 ${c.holdingDays} 天", style = MaterialTheme.typography.bodySmall)
            }
            if (!c.risk.isNullOrBlank()) {
                Text("风险：${c.risk}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922))
            }
            val reasons = c.reasons.filter { it.isNotBlank() }
            if (reasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                reasons.take(4).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
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

/** 聪明钱卡：ETF 份额跟踪 + 信号成熟度；未满 20 个交易日时只说事实，不外推仓位。 */
@Composable
private fun SmartMoneyCard(state: UiState, vm: SkappViewModel) {
    val sm = state.smartMoney
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("聪明钱 · 宽基 ETF 份额", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.smartMoneyLoading) "刷新中…" else "刷新",
                    Modifier.clickable { vm.loadSmartMoney() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (sm == null) {
                Text(
                    "暂无数据：etf_shares.json 不可达或解析失败。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                return@Column
            }
            Text(
                listOfNotNull(sm.ts, sm.source).joinToString(" · ").ifBlank { "时点暂无" },
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            if (sm.ready) {
                Text(
                    "信号成熟（${sm.progressText}）· 篮子 ${sm.etfsUsed} 只 · 窗口 ${sm.windowDays} 天 · " +
                        "仓位百分位 ${sm.positionPct?.let { String.format("%.1f", it) }}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "历史真值 ${sm.progressText}，不足 ${sm.minHistoryDays} 天：仅展示可核验的份额变动，不参与仓位调整",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922),
                )
            }
            val d1 = sm.d1NetYi
            val d5 = sm.d5NetYi
            Text(
                "篮子净申购：当日 " + (d1?.let { String.format("%+.2f 亿份", it) } ?: "样本不足") +
                    "　近 5 日 " + (d5?.let { String.format("%+.2f 亿份", it) } ?: "样本不足"),
                style = MaterialTheme.typography.bodySmall,
            )

            if (sm.etfs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // 表头
                Row(Modifier.fillMaxWidth()) {
                    Text("ETF", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(2.4f))
                    Text("份额(亿)", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1.3f))
                    Text("日", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("5日", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("20日", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1.05f))
                }
                HorizontalDivider()
                sm.etfs.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(2.4f)) {
                            Text(e.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "${e.code} · ${e.liveDays} 天历史",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                        }
                        Text(
                            e.sharesYi?.let { String.format("%.2f", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f),
                        )
                        PctCell(e.d1Pct, Modifier.weight(1f))
                        PctCell(e.d5Pct, Modifier.weight(1f))
                        PctCell(e.d20Pct, Modifier.weight(1.05f))
                    }
                }
                HorizontalDivider()
                Text(
                    "份额来自交易所日度披露（上交所 ETF 规模 / 深交所基金规模，单位亿份）；日/5日/20日为份额变动百分比，" +
                        "样本不足显示「—」，不用估算补齐。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            sm.positionNote?.let {
                Text("口径：$it", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun PctCell(v: Double?, modifier: Modifier) {
    val color = when {
        v == null -> Color.Gray
        v > 0 -> Color(0xFFF85149)
        v < 0 -> Color(0xFF3FB950)
        else -> Color.Gray
    }
    Text(
        v?.let { String.format("%+.2f%%", it) } ?: "—",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}
