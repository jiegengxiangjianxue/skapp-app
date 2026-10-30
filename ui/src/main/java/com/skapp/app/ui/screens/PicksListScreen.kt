package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.LocalPicks
import com.skapp.app.data.PickSignal
import com.skapp.app.data.StructuredPickArtifact
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.theme.SkColors

/**
 * 推股名单（盘前 / 实时两种口径）。
 *
 * ## 为什么单独成页
 *
 * 名单以前塞在「实时总结」里，和 AI 总结、盘后复盘、胜率矩阵滚在一条长列表上：
 * 想看一眼今天推了什么票，得先滚过三段总结文字；而总结本身又被名单挤到看不见。
 * 名单是**执行用**的，总结是**复盘用**的，节奏完全不同，因此拆成两个独立入口：
 *
 * - 盘前推股名单：T-1 收盘口径（当日冻结），开盘前用来定今天的观察清单
 * - 实时推股名单：当日实时行情口径，盘中用来看信号有没有兑现、有没有新进
 *
 * 两份名单都来自设备本地深算（`LocalPicks`），不依赖云端 CI 产物，断网也能看已有存档。
 */

/** 名单口径。文案与口径说明都挂在这里，避免两页各写一套导致口径漂移。 */
enum class PickListSession(
    val title: String,
    val session: LocalPicks.Session,
) {
    PREMARKET(title = "盘前推股名单", session = LocalPicks.Session.PREMARKET),
    REALTIME(title = "实时推股名单", session = LocalPicks.Session.MIDDAY);

    /** 口径说明直接取数据层常量，避免 UI 另写一套导致口径漂移。 */
    val basis: String get() = session.basis
}

@Composable
fun PremarketPicksScreen(vm: SkappViewModel, state: UiState) {
    PicksListScreen(vm, state, PickListSession.PREMARKET)
}

@Composable
fun RealtimePicksScreen(vm: SkappViewModel, state: UiState) {
    PicksListScreen(vm, state, PickListSession.REALTIME)
}

@Composable
fun PicksListScreen(vm: SkappViewModel, state: UiState, mode: PickListSession) {
    val artifact: StructuredPickArtifact = when (mode) {
        PickListSession.PREMARKET -> state.localPremarketPicks
        PickListSession.REALTIME -> state.localMiddayPicks
    }
    // 已有当日存档就直接复用，不重复拉行情；没有才首次计算
    LaunchedEffect(mode) { vm.ensurePicksFor(mode.session) }
    // 池由 rebuildLocalPicks 在算名单时同步取过（算法层要用它加权），这里不再另抓一遍：
    // 两条协程各拉 18 个池文件既是重复请求，也可能让后到的空结果覆盖掉已有池。
    val poolHits = remember(state.pools) {
        val idx = mutableMapOf<String, MutableList<String>>()
        state.pools.forEach { pool ->
            pool.items.forEach { item ->
                idx.getOrPut(item.code) { mutableListOf() }.add(pool.name)
            }
        }
        idx
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        mode.basis,
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
                if (state.localPicksLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "重算",
                        modifier = Modifier
                            .clickable { vm.refreshLocalPicks(mode.session) }
                            .padding(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        state.localPicksMsg?.let { msg ->
            item(key = "msg") {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("重算失败")) MaterialTheme.colorScheme.error else SkColors.textSecondary,
                )
            }
        }

        // 顺序以算法产物为准（LocalPicks 已按可买/池命中/评分做过排序与板块上限），
        // 界面不再二次排序，否则「显示的名单」和「算出来的名单」会各说一套。
        val picks = artifact.picks
        if (picks.isEmpty()) {
            item(key = "empty") { EmptyPickList(artifact, mode) }
        } else {
            item(key = "meta") {
                Text(
                    "${picks.size} 只 · 计算 ${artifact.calculationTime ?: artifact.generatedAt ?: "时点暂无"}" +
                        " · 数据日 ${artifact.date ?: "暂无"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
            items(picks, key = { it.code }) { pick ->
                PickRow(pick, poolHits[pick.code].orEmpty(), vm, state.picksTrend[pick.code])
            }
        }
    }
}

/** 名单为空时不能只说「暂无数据」：要说清是没算、算挂了，还是不满足条件。 */
@Composable
private fun EmptyPickList(artifact: StructuredPickArtifact, mode: PickListSession) {
    SkCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("名单为空", fontWeight = FontWeight.SemiBold)
            Text(
                "状态：${artifact.status}",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            artifact.reason?.takeIf { it.isNotBlank() }?.let {
                Text("原因：$it", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            Text(
                when (mode) {
                    PickListSession.PREMARKET ->
                        "盘前名单用上一交易日收盘数据算，若上一交易日数据缺失会留空，不做降级填充。"
                    PickListSession.REALTIME ->
                        "实时名单随当日行情变化；开盘前没有盘中数据，留空是正常的。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            Text(
                "点右上角「重算」可强制重跑本地深算。",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
        }
    }
}

/** 一行一只票：名称/代码 → 分数与共振 → 现价涨跌 → 理由。点开看详情。 */
@Composable
private fun PickRow(
    pick: PickSignal,
    pools: List<String>,
    vm: SkappViewModel,
    trend: com.skapp.app.data.TrendForecast.Result? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { vm.openDetail(pick.code) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TrendLine(trend)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pick.name?.takeIf { it.isNotBlank() } ?: pick.code,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            pick.score?.let {
                Text("评分 ${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(pick.code, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            pick.price?.let {
                Text(
                    "  ${"%.2f".format(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
            pick.pctChg?.let {
                Text(
                    "  ${"%+.2f".format(it)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it >= 0) SkColors.success else SkColors.errorText,
                )
            }
            pick.resonance?.let {
                Text(
                    "  共振 ${"%.0f".format(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (pick.buyable == false) {
                Text("不可买", style = MaterialTheme.typography.labelSmall, color = SkColors.errorText)
            }
        }
        if (pools.isNotEmpty()) {
            Text(
                "命中 ${pools.size} 个池：${pools.joinToString("、")}",
                style = MaterialTheme.typography.bodySmall,
                color = if (pools.size >= 2) MaterialTheme.colorScheme.primary else SkColors.textSecondary,
            )
        }
        pick.engine?.takeIf { it.isNotBlank() }?.let {
            Text("引擎：$it", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
        }
        val why = pick.reason?.takeIf { it.isNotBlank() }
            ?: pick.why.take(3).joinToString("；").takeIf { it.isNotBlank() }
        if (why != null) {
            Text(why, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
        }
        HorizontalDivider(color = SkColors.textSecondary.copy(alpha = 0.12f))
    }
}

/**
 * 推股卡的趋势行。
 *
 * 空态要区分「没算出来」与「样本不足」：前者是拉取失败（可重试），后者是这只票日 K 不够 60 根。
 * 写成统一的「暂无趋势」会让人以为是该股没有趋势，用户就不知道该不该再刷一次。
 */
@Composable
private fun TrendLine(trend: com.skapp.app.data.TrendForecast.Result?) {
    if (trend == null) {
        Text(
            "多日趋势：未算出（日 K 拉取失败或不足 60 根，可点详情重试）",
            style = MaterialTheme.typography.bodySmall,
            color = SkColors.warn,
        )
        return
    }
    if (trend.horizons.isEmpty()) {
        Text(
            "多日趋势：${trend.notes.firstOrNull() ?: "样本不足"}",
            style = MaterialTheme.typography.bodySmall,
            color = SkColors.warn,
        )
        return
    }
    Text(
        "多日趋势：" + trend.horizons.joinToString(" · ") { h ->
            "${h.days}日${h.direction} ${(h.confidence * 100).toInt()}% 区间" +
                String.format(java.util.Locale.US, "%+.1f~%+.1f%%", h.lowPct, h.highPct)
        },
        style = MaterialTheme.typography.bodySmall,
        color = SkColors.textSecondary,
    )
}
