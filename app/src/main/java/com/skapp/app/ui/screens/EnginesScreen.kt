package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.EngineFeed
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 云端引擎结果展示页
 * 数据来源：qwerzxcva/stocks-ai-view/data/ （CI 每日盘前产出）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginesScreen(vm: SkappViewModel, state: UiState) {
    var feeds by remember { mutableStateOf<EngineFeed.EngineFeeds?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 拉取一次
    LaunchedEffect(Unit) {
        loading = true; error = null
        try {
            feeds = vm.loadEngineFeeds()
            loading = false
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotEmpty() } ?: "拉取失败"
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("引擎输出") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("数据加载失败: $error", color = Color.Red)
                }
            } else if (feeds == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无引擎数据", color = Color.Gray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    feeds?.let { f ->
                        item { FeedHeader(f) }
                        item { EngineV5Section(f.engineV5) }
                        item { CycleSection(f.cyclePool) }
                        item { RpsSection(f.rpsRotation) }
                        item { StrategySection(f.strategyCard) }
                        item { MarketPhaseSection(f.marketPhase) }
                        item { DecisionSection(f.decision) }
                        item { MissingInfo(f.missing) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedHeader(f: EngineFeed.EngineFeeds) {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(java.util.Date(f.ts))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("云端引擎产物", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("生成时点：$ts", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (f.missing.isNotEmpty()) {
                Text("拉取失败: ${f.missing.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Color.Red)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ── engine_v5 ───────────────────────────────────────────────

@Composable
private fun EngineV5Section(e: EngineFeed.EngineV5Payload?) {
    if (e == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("① 综合引擎 v5", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (e.top.isEmpty()) {
                Text("本周期无入选标的", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                Text("上榜 ${e.top.size} 只 ·  stale_top ${e.staleTop.size} 只", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                e.top.take(15).forEach { t ->
                    EngineV5Row(t)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EngineV5Row(t: EngineFeed.EngineV5Top) {
    val bullPct = (t.bull * 100.0 / maxOf(t.bull + t.bear + t.neutral, 1)).toInt()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${t.rank}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7280), modifier = Modifier.width(28.dp))
        Text(t.code, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "E=${String.format("%.1f", t.score)} 共振=${String.format("%.0f%%", t.resonance * 100)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            if (t.price != null) {
                Text("¥${String.format("%.2f", t.price)}  ${t.date.take(10)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.background(Color(0xFF3FB950).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("看 ${t.bull}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3FB950))
            }
            Box(Modifier.background(Color(0xFFF85149).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("空 ${t.bear}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF85149))
            }
        }
    }
}

// ── cycle_pool_v2 ──────────────────────────────────────────

@Composable
private fun CycleSection(c: EngineFeed.CycleMeta?) {
    if (c == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("② 周期轮动（cycle_pool_v2）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("指数：${c.indexName}（${c.index}）", style = MaterialTheme.typography.bodyMedium)
            Text("样本区间：${c.spanStart} ~ ${c.spanEnd}，共 ${c.nDays} 交易日", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ── rps_rotation ───────────────────────────────────────────

@Composable
private fun RpsSection(r: EngineFeed.RpsPayload?) {
    if (r == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("③ RPS 涨幅百分位（${r.date}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            r.top.take(10).forEach { t ->
                val rpsLabel = if (t.rps60 != null) String.format("60日%.0f", t.rps60)
                    else if (t.rps20 != null) String.format("20日%.0f", t.rps20)
                    else "—"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${t.rank}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7280), modifier = Modifier.width(28.dp))
                    Text(t.symbol, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                    Text(rpsLabel, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(2.dp))
            }
            if (r.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(r.note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ── strategy_card ───────────────────────────────────────────

@Composable
private fun StrategySection(s: EngineFeed.StrategyCard?) {
    if (s == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("④ 策略卡（${s.strategy}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (!s.tradeOk) {
                Text("⚠ 当前贸易通道关闭（global_score 不满足），无候选", style = MaterialTheme.typography.bodySmall, color = Color.Red)
            } else {
                s.candidates.take(10).forEach { c ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.symbol, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Text("¥${String.format("%.2f", c.close)}", style = MaterialTheme.typography.bodySmall)
                        Text("+${String.format("%.2f%%", c.retPct)}", style = MaterialTheme.typography.bodySmall, color = if (c.retPct >= 0) Color(0xFFF85149) else Color(0xFF3FB950))
                        Text("量比 ${String.format("%.2f", c.vratio)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ── market_phase ────────────────────────────────────────────

@Composable
private fun MarketPhaseSection(m: EngineFeed.MarketPhase?) {
    if (m == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("⑤ 市场情绪阶段", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            m.daily.takeLast(5).reversed().forEach { d ->
                val phase = findPhase(m.labels, d.seal, d.promo)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.date.take(10), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                    Text("封板${d.firstBoard}  ≥2连板${d.ge2}  高度${d.height}", style = MaterialTheme.typography.bodySmall)
                    Text(phase, style = MaterialTheme.typography.bodySmall.copy(color = phaseColor(phase)))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

private fun findPhase(labels: Map<String, String>, seal: Double, promo: Double): String =
    when {
        seal < 0.3 -> labels["ice"] ?: "冰点"
        seal < 0.6 -> labels["ebb"] ?: "退潮"
        promo > 0.5 -> labels["climax"] ?: "高潮"
        seal > 0.6 -> labels["rally"] ?: "主升"
        seal > 0.4 -> labels["ignite"] ?: "启动"
        else -> labels["repair"] ?: "修复"
    }

private fun phaseColor(label: String): Color = when {
    label.contains("冰") || label.contains("退潮") -> Color(0xFF3FB950)
    label.contains("高潮") || label.contains("主升") -> Color(0xFFF85149)
    else -> Color(0xFF6B7280)
}

// ── decision ────────────────────────────────────────────────

@Composable
private fun DecisionSection(d: EngineFeed.Decision?) {
    if (d == null) return
    val regimeColor = when (d.marketRegime) {
        "bull" -> Color(0xFFF85149)
        "bear" -> Color(0xFF3FB950)
        else -> Color(0xFF6B7280)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("⑥ 决策信号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("市场状态：${d.marketRegime.uppercase()}", style = MaterialTheme.typography.titleMedium, color = regimeColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("仓位建议：${d.positionAdvice.label}（上限 ${String.format("%.0f%%", d.positionAdvice.cap * 100)}%）", style = MaterialTheme.typography.bodyMedium)
            Text(d.positionAdvice.reason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            if (d.cards.isEmpty()) {
                Text("本周期无推荐决策卡", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                d.cards.take(15).forEach { card ->
                    DecisionCardRow(card)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DecisionCardRow(c: EngineFeed.DecisionCard) {
    val actionColor = when (c.action) {
        "buy" -> Color(0xFFF85149)
        "sell" -> Color(0xFF3FB950)
        "watch" -> Color(0xFF6B7280)
        else -> Color(0xFF6B7280)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(c.code, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
        Text(c.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        c.price?.let { p ->
            Text("¥${String.format("%.2f", p)}", style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.background(actionColor.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(c.action, color = actionColor, style = MaterialTheme.typography.labelSmall)
        }
        Text("评分 ${String.format("%.1f", c.composite)}", style = MaterialTheme.typography.labelSmall)
    }
}

// ── 兜底 ───────────────────────────────────────────────────

@Composable
private fun MissingInfo(missing: List<String>) {
    if (missing.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("部分引擎文件未返回", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF85149))
            Spacer(Modifier.height(4.dp))
            missing.forEach { f -> Text(f, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF85149)) }
        }
    }
    Spacer(Modifier.height(8.dp))
}
