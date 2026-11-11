package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.theme.SkColors

@Composable
fun ResearchHubScreen(vm: SkappViewModel, state: UiState, selected: Int, onSelect: (Int) -> Unit) {
    BacktestScreen(vm, state)
}

@Composable
private fun ResearchHeader(title: String, label: String, state: UiState, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f).padding(vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (label in state.loadingSections) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
        else TextButton(onClick = refresh) { Text("刷新") }
    }
    state.dataGaps[label]?.let { Text(it, color = SkColors.warn, style = MaterialTheme.typography.bodySmall) }
}

private fun pct(value: Double?): String = value?.let { "%+.2f%%".format(it) } ?: "证据不足"

@Composable
fun MomentumScreen(vm: SkappViewModel, state: UiState) {
    LaunchedEffect(Unit) { if (state.momentum == null) vm.loadMomentum() }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ResearchHeader("板块动量观察", "板块动量", state) { vm.loadMomentum(true) } }
        item {
            Text("量价、资金与市场宽度分开看；单日强弱不等于持续轮动。",
                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
        }
        val result = state.momentum
        if (result == null) {
            item { Text(if ("板块动量" in state.loadingSections) "正在聚合实际行情…" else "暂无可用结果，可刷新重试。") }
        } else {
            item {
                Text("来源 ${result.source}\n抓取 ${result.snapshotAt ?: "未知"} · 映射 ${result.mapped}/${result.total} · 状态 ${result.status}",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            items(result.rows, key = { it.name }) { row ->
                SkCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.name, fontWeight = FontWeight.SemiBold)
                        Text("单日 ${pct(row.return1d)} · 5日 ${pct(row.return5d)} · 20日 ${pct(row.return20d)}")
                        Text("资金净额：${row.mainNet?.let { "%.2f亿元".format(it / 1e8) } ?: "暂无"} · 上涨占比 ${row.breadth?.let { "%.1f%%".format(it * 100) } ?: "暂无"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("成分 ${row.memberCount} · 单日样本 ${row.sampleCount1d} · 5/20日样本 ${row.sampleCount5d}/${row.sampleCount20d} · 资金样本 ${row.flowSampleCount}",
                            style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                        Text("数据日期 ${row.dataDate ?: "来源未提供"} · ${row.adjustmentEvidence ?: "长期复权证据未就绪，不输出长期结论"}",
                            style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    }
                }
            }
            item { result.issues.distinct().take(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary) } }
        }
    }
}

@Composable
fun BacktestScreen(vm: SkappViewModel, state: UiState) {
    LaunchedEffect(Unit) { if (state.backtest == null) vm.loadBacktest() }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ResearchHeader("历史推股验证", "历史回测", state) { vm.loadBacktest(true) } }
        item {
            SkCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("真实归档，不用均线实验冒充推股成绩", fontWeight = FontWeight.SemiBold)
                    Text("执行假设：信号后的首个真实交易日开盘买入；默认单边成本0.10%。22/60交易日内曾回本与期末净收益分别报告，不是实际成交回报。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("版本、时段、交易日历或同口径复权证据缺失的记录保留展示，但不计入胜负。",
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
            }
        }
        val result = state.backtest
        if (result == null) {
            item { Text(if ("历史回测" in state.loadingSections) "正在读取真实推股归档…" else "暂无可验证记录，可刷新查看数据缺口。") }
        } else {
            item {
                Text("已读 ${result.archive.filesRead} 个归档 · ${result.rows.size} 条信号 · 状态 ${result.status}")
                Text("成熟22日 ${result.mature22} · 成熟60日 ${result.mature60}", style = MaterialTheme.typography.bodySmall)
                if (result.archive.truncated) Text("已达本次读取上限，不代表完整历史。", color = SkColors.warn)
            }
            item {
                Text("22日回本率 ${result.recoupRate22?.let { "%.1f%%".format(it * 100) } ?: "证据不足或口径不可混算"}\n60日回本率 ${result.recoupRate60?.let { "%.1f%%".format(it * 100) } ?: "证据不足或口径不可混算"}")
            }
            items(result.rows.take(80)) { row ->
                SkCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${row.record.name ?: row.record.code} · ${row.record.session ?: "时段未知"}", fontWeight = FontWeight.SemiBold)
                        Text("信号 ${row.record.ts ?: "未知"}\n${row.record.engine ?: "引擎未知"} · ${row.record.version ?: "版本未知"}", style = MaterialTheme.typography.bodySmall)
                        Text("22日净收益 ${pct(row.netReturn22)} · 60日 ${pct(row.netReturn60)}")
                        Text("基准22日 ${pct(row.benchmarkNetReturn22)} · 60日 ${pct(row.benchmarkNetReturn60)}", style = MaterialTheme.typography.bodySmall)
                        Text(row.reasons.distinct().joinToString("；"), style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    }
                }
            }
            item {
                result.archive.failures.take(5).forEach { Text("${it.source}：${it.reason}", style = MaterialTheme.typography.bodySmall, color = SkColors.warn) }
                result.reasons.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary) }
            }
        }
    }
}
