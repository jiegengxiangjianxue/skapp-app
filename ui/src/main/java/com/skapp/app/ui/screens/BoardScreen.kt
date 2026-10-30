package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.skapp.app.ui.components.SkCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown

/**
 * 看板 Tab —— 对应网页版「📊 看板」。
 * 指数 · 涨跌统计 · 涨跌幅榜 · 情绪 · 涨停池 · 板块热度。
 */
@Composable
fun BoardScreen(vm: SkappViewModel, state: UiState) {
    val b = state.board
    // 进页即重拉：盘前数据最容易「看着是今天的、其实是昨天的」
    LaunchedEffect(Unit) { vm.loadBoard() }
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
                    val tsText = b["ts"]?.toString() ?: "时点暂无"
                    val today = java.time.LocalDate.now().toString()
                    val tsDay = tsText.take(10)
                    val stale = tsText.length >= 10 && tsDay != today
                    Text(
                        tsText + if (stale) "（数据日期 $tsDay，非今日，仅供参考）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stale) MaterialTheme.colorScheme.error else SkColors.textSecondary,
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

        // 每日市场宽度（云端 CI 每日累积，不是实时快照）
        run {
            val breadth = state.marketBreadth
            @Suppress("UNCHECKED_CAST")
            val latest = (breadth["latest"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            @Suppress("UNCHECKED_CAST")
            val coverage = (breadth["coverage"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            if (latest.isNotEmpty()) {
                item { SectionTitle("市场宽度（每日累积）") }
                item { MarketBreadthCard(latest, coverage, breadth["caliber"]?.toString()) }
            }
        }

        // 市场状态机（进攻/防守）—— 决策根基，置顶
        state.marketState?.let { ms ->
            item { MarketStateCard(ms) }
        }

        // 网页版核心市场 KPI 前置，不再被次要面板挤到后面
        @Suppress("UNCHECKED_CAST")
        val market = (b["market"] as? Map<String, Any?>) ?: emptyMap()
        if (market.isNotEmpty()) {
            item { SectionTitle("市场概况") }
            item {
                SkCard(contentPadding = PaddingValues(0.dp)) {
                    Text(
                        market.entries.joinToString("　") { (k, v) -> "${marketLabel(k)} ${v ?: "—"}" },
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            item {
                Text(
                    "市场概况暂未加载：${b["reason"]?.toString() ?: "请点击刷新重试"}",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            }
        }

        // 每日总结（与 Dashboard 共用 ViewModel 数据源）
        item { DailyBriefCard(state.summary) }

        // 云端数据源健康度（190 源合并产物）
        state.allSourcesSummary?.let { s ->
            item {
                Text(
                    "数据源：$s",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // 首屏加载中：骨架提示，避免白屏
        if (state.loading && state.quotes.isEmpty() && b.isEmpty()) {
            item {
                SkCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.height(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text("  正在拉取行情与看板数据…", color = SkColors.textSecondary)
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
                                color = SkColors.textSecondary,
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

        // A 股指数（上证/深证/创业板/科创 50 等）
        if (state.aShareIndices.isNotEmpty()) {
            item { SectionTitle("A 股指数") }
            items(state.aShareIndices, key = { "ash_" + it.hashCode() }) { g ->
                val pct = (g["pctChg"] as? Number)?.toDouble()
                val price = (g["price"] as? Number)?.toDouble()
                SkCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text((g["name"] ?: g["code"] ?: "未知").toString(), fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                price?.let { String.format("%.2f", it) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                pct?.let { String.format("%+.2f%%", it) } ?: "—",
                                color = pctColor(pct),
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
                SkCard(contentPadding = PaddingValues(0.dp)) {
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
                    color = SkColors.textSecondary,
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
                SkCard(contentPadding = PaddingValues(0.dp)) {
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

        // 涨跌统计已在顶部展示；避免同一 KPI 重复占用页面
        @Suppress("UNUSED_EXPRESSION")
        val marketStatsDisplayed = market.isNotEmpty()

        // 情绪/阶段
        b["phase"]?.let { ph ->
            item { SectionTitle("市场阶段") }
            item {
                SkCard(contentPadding = PaddingValues(0.dp)) {
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
                                color = SkColors.textSecondary,
                            )
                        }
                        val boards = (g["boards"] ?: g["industry"]) as? List<*>
                        if (!boards.isNullOrEmpty()) {
                            Text(
                                boards.take(2).joinToString("、") { it.toString() },
                                style = MaterialTheme.typography.bodySmall,
                                color = SkColors.textTertiary,
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
        else -> SkColors.warn
    }
    SkCard(contentPadding = PaddingValues(0.dp)) {
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
                color = SkColors.textSecondary,
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
                            color = if (v > 0) UP else if (v < 0) DOWN else SkColors.textSecondary,
                        )
                        Text(
                            partLabel(k),
                            style = MaterialTheme.typography.labelSmall,
                            color = SkColors.textSecondary,
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
    pct == null -> SkColors.textSecondary
    pct > 0 -> UP
    pct < 0 -> DOWN
    else -> SkColors.textSecondary
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

/** 每日总结卡：网页版 daily_brief.json（盘后自动生成），取正文前 25 行展示 */

@Composable
private fun DailyBriefCard(summary: Map<String, Any?>) {
    val t = summary["text"]?.toString()?.takeIf { it.isNotBlank() } ?: return
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("每日总结（盘后自动生成）", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            t.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(">") }
                .take(25)
                .forEach {
                    Text(it.trimStart('#').trim(), style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
        }
    }
}

/** 每日市场宽度：涨停/跌停/成交额/涨跌家数，来自 data/api/market_breadth.json。 */
@Composable
private fun MarketBreadthCard(latest: Map<*, *>, coverage: Map<*, *>, caliber: String?) {
    fun n(key: String) = (latest[key] as? Number)?.toInt()
    fun f(key: String) = (latest[key] as? Number)?.toDouble()
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("交易日 ${latest["date"] ?: "暂无"}", fontWeight = FontWeight.SemiBold)
                f("amount_yi")?.let {
                    Text(
                        String.format("两市成交 %.0f 亿", it),
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("涨停 ${n("limit_up") ?: "—"}", style = MaterialTheme.typography.bodySmall, color = UP)
                Text("跌停 ${n("limit_down") ?: "—"}", style = MaterialTheme.typography.bodySmall, color = DOWN)
                Text("炸板 ${n("broken") ?: "—"}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                Text(
                    "封板率 " + (f("seal_rate_pct")?.let { String.format("%.1f%%", it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            }
            Text(
                "涨 ${n("advance") ?: "—"} / 跌 ${n("decline") ?: "—"} / 平 ${n("flat") ?: "—"}"
                    + "（上涨占比 " + (f("up_pct")?.let { String.format("%.1f%%", it) } ?: "—") + "）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "覆盖 ${coverage["days"] ?: "—"} 个交易日 · ${coverage["first"] ?: "—"} ~ ${coverage["last"] ?: "—"}",
                style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
            )
            Text(
                "口径：${caliber ?: "涨停/跌停按分板块涨跌幅限制统计"}",
                style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
            )
            n("limit_up_hist")?.let {
                Text(
                    "对照（±10% 平价口径）：涨停 $it / 跌停 ${n("limit_down_hist") ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}
