package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.skapp.app.ui.components.SkCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.CloseReview
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.LocalPicks
import com.skapp.app.data.PickSignal
import com.skapp.app.data.StructuredPickArtifact
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun RealtimeSummaryScreen(vm: SkappViewModel, state: UiState) {
    val today = java.time.LocalDate.now().toString()
    // 进页即拉一次本地深算与异动，避免“打开就是空页”
    LaunchedEffect(Unit) {
        if (state.screened.isEmpty() && !state.screening) vm.runScreen("all")
        if (state.marketPulse.isEmpty()) vm.refreshPulse()
        vm.refreshLocalAnalytics()
        // 本地推股：当前时段还没有当日存档才真的算一次（有存档直接复用，不重复拉行情）
        vm.ensureLocalPicks()
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实时总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${state.marketPhase} · ${state.quoteTs ?: "时点暂无"}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
                if (state.summaryRefreshing) CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                else Text("刷新", Modifier.clickable { vm.refreshRealtimeSummary("all") }.padding(10.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            state.summaryRefreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("刷新失败")) MaterialTheme.colorScheme.error else SkColors.textSecondary) }
        }
        item { SummaryTextCard(state.summary) }
        // 本地部分置顶：本地深算候选（次日视角）排在所有云端产物之前
        item { TomorrowPicksCard(state, vm) }
        // 盘前/午盘推股改为本地深算（设备直连行情 + 12 引擎），不再依赖云端 CI 产物
        item {
            LocalPickCard(
                title = "盘前推股（本地）",
                artifact = state.localPremarketPicks,
                basis = "口径：截至上一交易日收盘（已剔除当日未完成日 K）",
                today = today,
                loading = state.localPicksLoading,
                msg = state.localPicksMsg,
                vm = vm,
                session = LocalPicks.Session.PREMARKET,
            )
        }
        item { PremarketDraftCard(state) }
        item {
            LocalPickCard(
                title = "午盘推股（本地）",
                artifact = state.localMiddayPicks,
                basis = "口径：当日实时行情（含当日未完成日 K）",
                today = today,
                loading = state.localPicksLoading,
                msg = state.localPicksMsg,
                vm = vm,
                session = LocalPicks.Session.MIDDAY,
            )
        }
        item { CloseReviewCard(state.closeReview, today) }
        item { WinrateCard(state) }
    }
}

/** 明日推股：本地引擎候选（次日视角），不依赖云端尚未提供的明日产物。 */
@Composable
private fun TomorrowPicksCard(state: UiState, vm: SkappViewModel) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("明日推股", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "本地引擎计算 · ${state.screened.size} 个候选（次日观察）",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
                Text(
                    "重算",
                    Modifier.clickable { vm.runScreen("all", true) }.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val at = state.screenedAt ?: ""
            val atLabel = if (at.length >= 16) at.replace("T", " ").take(19) else "尚未计算"
            val staleMinutes = if (at.isBlank()) null else runCatching {
                java.time.Duration.between(java.time.OffsetDateTime.parse(at), java.time.OffsetDateTime.now())
                    .toMinutes()
            }.getOrNull()
            Text(
                "计算时点 $atLabel" + (staleMinutes?.let { "（${it} 分钟前）" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (staleMinutes != null && staleMinutes > 30) MaterialTheme.colorScheme.error else SkColors.textSecondary,
            )
            if (state.screening) {
                Text(state.screenMsg ?: "正在本地全市场深算…", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            if (state.screened.isEmpty()) {
                Text(
                    state.screenMsg ?: "暂无数据：本地深算尚未产出候选。",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
                Text(
                    "重新筛选",
                    Modifier.clickable { vm.runScreen("all", true) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                state.screened.take(10).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openDetail(item.code) }.padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${item.name?.takeIf { it.isNotBlank() } ?: "暂无名称"} ${item.code} · " +
                                (item.centralScore?.let { "%.1f".format(it) } ?: "暂无评分"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("详情 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 早盘名单：本地深算为底、消息面修正。09:30 前可随时变，之后冻结。 */
@Composable
private fun PremarketDraftCard(state: UiState) {
    val draft = state.premarketDraft ?: return
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("早盘推股（消息修正）", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (draft.status == com.skapp.app.data.PremarketPicks.Status.FROZEN) "已冻结"
                    else "草稿 · 09:30 前可更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draft.status == com.skapp.app.data.PremarketPicks.Status.FROZEN) SkColors.textSecondary
                    else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "更新 ${draft.updatedAt.replace("T", " ").take(19)}",
                style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
            )
            if (draft.dataGap) {
                Text(
                    "消息库覆盖不足（" + (draft.coveredUntil ?: "无记录") + " 之后无消息），早盘结论置信度下降",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                )
            }
            if (draft.picks.isEmpty()) {
                Text(
                    draft.note ?: "暂无达标候选（弱市不凑数，请观察或空仓）",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            } else {
                draft.picks.take(10).forEach { p ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${p.name?.takeIf { it.isNotBlank() } ?: p.code} ${p.code} · " +
                                    "${p.finalScore.let { "%.1f".format(it) }}" +
                                    (if (p.newsDelta != 0.0) "（消息 ${if (p.newsDelta > 0) "+" else ""}%.1f".format(p.newsDelta) + "）" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (p.benefit) Text("受益", style = MaterialTheme.typography.labelSmall, color = SkColors.success)
                        }
                        p.why.take(2).forEach { line ->
                            Text("· $line", style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary)
                        }
                    }
                }
                Text(
                    "以上为本地深算 + 消息面修正的早盘候选，不是买入建议。",
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun LocalPickCard(
    title: String,
    artifact: StructuredPickArtifact,
    basis: String,
    today: String,
    loading: Boolean,
    msg: String?,
    vm: SkappViewModel,
    session: LocalPicks.Session,
) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "重算",
                    Modifier.clickable { vm.refreshLocalPicks(session) }.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(basis, style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary)
            Text(
                "生成 ${artifact.generatedAt?.replace("T", " ")?.take(19) ?: "暂无数据"} · " +
                    "计算时点 ${artifact.calculationTime?.replace("T", " ")?.take(19) ?: "暂无数据"}",
                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
            )
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp), strokeWidth = 2.dp)
                    Text(
                        msg ?: "正在本地深算…",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                    )
                }
            }
            val staleDate = artifact.date != null && artifact.date != today
            if (staleDate && artifact.picks.isNotEmpty()) {
                Text(
                    "存档日期 ${artifact.date}，不是今日；仅作历史参考，不当今日名单用。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                )
            }
            if (artifact.picks.isEmpty()) {
                Text(
                    artifact.reason ?: msg ?: "暂无数据：本地深算尚未产出（当前非生成时段）。",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            } else {
                Text(
                    "数据源：${artifact.engine ?: "本地深算"} · ${artifact.picks.size} 条达标（均未涨停）",
                    style = MaterialTheme.typography.bodySmall,
                )
                itemsPreview(artifact.picks, vm)
                // 未达标只当观察位展示，并显式标注不构成推荐
                val observed = (artifact.metadata["observed"] as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    .orEmpty()
                if (observed.isNotEmpty()) {
                    Text(
                        "未达标观察（评分<55 或涨幅已达涨停附近，不构成推荐）：" + observed.joinToString("、") { o ->
                            val name = o["name"]?.toString().orEmpty().ifBlank { o["code"]?.toString().orEmpty() }
                            val score = (o["score"] as? Number)?.let { "%.1f".format(it) } ?: "暂无分"
                            "$name（$score）"
                        },
                        style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                    )
                }
                Text(
                    "以上为本地算法候选（12 引擎 / ${ExtendedIndicators.factorCount} 因子），不是买入建议；交易决策需自行复核。",
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun itemsPreview(items: List<PickSignal>, vm: SkappViewModel) {
    items.take(20).forEach { pick ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.openDetail(pick.code) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${pick.name?.takeIf { it.isNotBlank() } ?: "暂无名称"}  ${pick.code}", fontWeight = FontWeight.Medium)
                Text(
                    "生成/计算 ${pick.calculationTime ?: "暂无数据"} · 引擎 ${pick.engine ?: "暂无数据"}",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
                Text(
                    "评分 ${pick.score?.let { "%.1f".format(it) } ?: "暂无数据"} · 共振 ${pick.resonance?.let { "%.1f%%".format(it * 100) } ?: "暂无数据"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("理由：${pick.reason ?: pick.why.take(2).joinToString("；").ifBlank { "暂无数据" }}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            Text("详情 ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CloseReviewCard(review: CloseReview, today: String) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("盘后总结", fontWeight = FontWeight.SemiBold)
            Text("生成 ${review.generatedAt ?: "暂无数据"} · 状态 ${review.status}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            if (review.status != "ok") Text(review.reason ?: "本交易日盘后总结未生成或数据不足。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            else {
                if (review.date != today) Text("源日期 ${review.date ?: "未知"}，不是今日数据；以下仅作历史参考。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                review.stages.forEach { (name, data) ->
                    val summary = data["summary"] as? Map<*, *>
                    Text("$name：${summary?.get("n") ?: summary?.get("n_requested") ?: "暂无数据"} 条 · 胜率 ${summary?.get("hit_rate_pct") ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryTextCard(summary: Map<String, Any?>) {
    val source = summary["source"]?.toString()
    val isLocal = source == "local_realtime"
    val ok = summary["status"]?.toString() == "ok"
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("实时总结", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isLocal -> "本地实时计算"
                        source != null -> "云端 $source"
                        else -> "暂无来源"
                    },
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
            if (!ok) {
                Text(
                    summary["reason"]?.toString() ?: "暂无数据：本地行情/消息/深算均未就绪，点上方「刷新」重试。",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            } else {
                val text = summary["text"]?.toString()
                Text(
                    if (text.isNullOrBlank()) "暂无数据：总结正文为空。" else text,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "时点 ${summary["ts"] ?: "暂无"} · 交易日 ${summary["date"] ?: "暂无"}",
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun WinrateCard(state: UiState) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("推股胜率", fontWeight = FontWeight.SemiBold)
            Text("生成 ${state.winrateMatrix.ts ?: "时点暂无"} · 源数据 ${state.winrateMatrix.sourceTs ?: "暂无数据"} · 来源 ${state.winrateMatrix.source ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            val sourceDate = state.winrateMatrix.sourceTs?.take(10)
            val fresh = sourceDate == java.time.LocalDate.now().toString()
            if (!fresh) Text("暂无数据：胜率源数据不是当前交易日，不能作为今日统计。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            if (state.winrateMatrix.horizons.isEmpty() || !fresh) Text("暂无数据：当前无可用当日胜率。", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            else state.winrateMatrix.horizons.toSortedMap().forEach { (horizon, data) ->
                Text("${horizon}日：胜率 ${formatPercent(data["overall"])} · 平均收益 ${data["avg_ret_pct"] ?: "暂无数据"}% · 样本 ${data["n"] ?: "暂无数据"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatPercent(value: Any?): String = when (value) {
    is Number -> "%.1f%%".format(value.toDouble() * 100)
    else -> "暂无数据"
}
