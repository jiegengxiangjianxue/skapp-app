package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.skapp.app.ui.components.ParityStrip
import com.skapp.app.ui.components.parityViewOfArtifact
import com.skapp.app.ui.components.SkCard
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
private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown

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
                        (b["ts"]?.toString() ?: "时点暂无") +
                            " · " + when {
                                b.isEmpty() -> "未加载"
                                b["source"] == "local" -> "本地实时"
                                else -> "库表回看"
                            } +
                            " · " + heartbeatText(state),
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                    )
                }
                Text("刷新", Modifier.clickable { vm.loadBoard(force = true) },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── KPI 8 格：涨停/跌停/炸板/封板率/上涨/下跌/成交/阶段（网页版 kpis 对齐）──
        item {
            // 字段契约以 LocalSectors.boardMap 为准：统计在 market 内用中文键，相位在顶层 phase{label,score}
            val limitUp = numOf(market["涨停"])
            val limitDown = numOf(market["跌停"])
            val broken = numOf(market["炸板"])
            val advance = numOf(market["涨"])
            val decline = numOf(market["跌"])
            val turnover = numOf(market["成交额(亿)"])
            val phaseMeta = b["phase"] as? Map<*, *>
            val phase = phaseMeta?.get("label")?.toString() ?: "—"
            val phaseScore = numOf(phaseMeta?.get("score"))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("涨停", limitUp?.toInt()?.toString(), UP, Modifier.weight(1f))
                    KpiCard("跌停", limitDown?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("炸板", broken?.toInt()?.toString(), SkColors.warn, Modifier.weight(1f))
                    KpiCard("上涨", advance?.toInt()?.toString(), UP, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("下跌", decline?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("成交(亿)", turnover?.let { String.format("%.0f", it) }, SkColors.textTertiary, Modifier.weight(1f))
                    KpiCard("阶段", phase, SkColors.primary, Modifier.weight(1f))
                    KpiCard("阶段分", phaseScore?.let { String.format("%.1f", it) }, SkColors.primary, Modifier.weight(1f))
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
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                    )
                }
            }
        }

        // 盘面解读只保留看板这一处；实时总结复用同一份 summary，不再重复展示正文。
        item { CommentaryCard(state.summary) }

        // ── 个股查（panel-stock）：搜索入口 ──
        item { StockSearchCard(vm, state) }

        // ── 选股跟踪：今日推股 vs 实时报价（表格化，可点进详情）──
        item { PickTrackingSection(state, vm) }

        // ── 指数与全球市场：表格化，支持按幅度排序 ──
        item { IndexTableSection(state) }

        // ── 市场状态机（进攻/防守）──
        state.marketState?.let { ms ->
            item { MarketStateCard(ms) }
        }

        // ── 云端数据源健康度 ──
        state.allSourcesSummary?.let { s ->
            item {
                Text(
                    "数据源 $s",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
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

@Composable
private fun KpiCard(label: String, value: String?, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(value ?: "—", fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CommentaryCard(summary: Map<String, Any?>) {
    val t = (summary["premarket"] ?: summary["text"])?.toString()?.takeIf { it.isNotBlank() } ?: return
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("盘前分析", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            t.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(">") }
                .take(18)
                .forEach {
                    Text(it.trimStart('#').trim(),
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
        }
    }
}

@Composable
private fun StockSearchCard(vm: SkappViewModel, state: UiState) {
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    SkCard(contentPadding = PaddingValues(0.dp)) {
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
                Text("暂无匹配股票，请检查代码或名称。", color = SkColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyNote(msg: String) {
    Text(msg, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
}

/** 本文件的 SectionTitle（原引 BoardScreen 的 private 版本，编译报 private-in-file） */
@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** 市场状态卡（简化版；原引 BoardScreen 的 private MarketStateCard） */
@Composable
private fun MarketStateCard(ms: com.skapp.app.data.MarketState) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("市场状态机", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${ms.state} · E=${String.format("%.1f", ms.E)} · agreement=${String.format("%.0f", ms.agreement * 100)}%",
                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 表格化区块：排序状态 + 选股跟踪 + 指数与全球市场
// ─────────────────────────────────────────────────────────────

private enum class PctSort { NONE, DESC, ASC }

private fun nextSort(s: PctSort): PctSort = when (s) {
    PctSort.NONE -> PctSort.DESC
    PctSort.DESC -> PctSort.ASC
    PctSort.ASC -> PctSort.NONE
}

private fun sortLabel(s: PctSort): String = when (s) {
    PctSort.NONE -> "幅度：默认"
    PctSort.DESC -> "幅度：↓"
    PctSort.ASC -> "幅度：↑"
}

@Composable
private fun IndexTableSection(state: UiState) {
    val a = state.aShareIndices
    val g = state.globalIndices
    if (a.isEmpty() && g.isEmpty()) {
        EmptyNote("暂无数据：指数与全球市场尚未返回。")
        return
    }
    var sort by remember { mutableStateOf(PctSort.NONE) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("指数与全球市场")
            Text(
                sortLabel(sort),
                Modifier.clickable { sort = nextSort(sort) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (a.isNotEmpty()) IndexTable("A 股指数", a, sort)
        if (g.isNotEmpty()) IndexTable("全球市场", g, sort)
        Text(
            "涨跌口径为当日涨跌幅；点右上角切换排序。全球市场仅供参考，不入选股池。",
            style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
        )
    }
}

@Composable
private fun IndexTable(title: String, rows: List<Map<String, Any?>>, sort: PctSort) {
    val sorted = when (sort) {
        PctSort.NONE -> rows
        PctSort.DESC -> rows.sortedByDescending { numOf(it["pctChg"]) ?: Double.NEGATIVE_INFINITY }
        PctSort.ASC -> rows.sortedBy { numOf(it["pctChg"]) ?: Double.POSITIVE_INFINITY }
    }
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("${rows.size} 项", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("名称", modifier = Modifier.weight(2.2f), style = HeadCell)
                Text("最新", modifier = Modifier.weight(1.3f), style = HeadCell)
                Text("涨跌", modifier = Modifier.weight(1.1f), style = HeadCell)
                Text("幅度", modifier = Modifier.weight(1.2f), style = HeadCell)
            }
            HorizontalDivider()
            sorted.forEach { item ->
                val name = (item["name"] ?: item["code"])?.toString() ?: "—"
                val code = item["code"]?.toString()
                val pct = numOf(item["pctChg"])
                val price = numOf(item["price"])
                val chg = numOf(item["chg"])
                val color = pct?.let { if (it >= 0) UP else DOWN } ?: SkColors.textSecondary
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(2.2f)) {
                        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (code != null && code != name) Text(code, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    }
                    Text(
                        price?.let { String.format("%.2f", it) } ?: "—",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f),
                    )
                    Text(
                        chg?.let { String.format("%+.2f", it) } ?: "—",
                        style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1.1f),
                    )
                    Text(
                        pct?.let { String.format("%+.2f%%", it) } ?: "—",
                        style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1.2f),
                    )
                }
            }
        }
    }
}

private val HeadCell = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = SkColors.textSecondary)

/** 选股跟踪：今日推股信号价 vs 实时报价（state.pickQuotes），可点进详情。 */
@Composable
private fun PickTrackingSection(state: UiState, vm: SkappViewModel) {
    val picks = state.picks
    val rows = picks.signals.ifEmpty { picks.values }
    if (rows.isEmpty()) {
        EmptyNote("选股跟踪：今日尚无推股信号（上游未产出或非交易日）。")
        return
    }
    val ts = picks.ts ?: picks.date
    val liveCount = rows.count { state.pickQuotes[it.code]?.price != null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle("选股跟踪")
            Text(
                "实时 $liveCount/${rows.size}",
                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
            )
        }
        // 口径标签：分数旁边必须能读到「今天是什么环境、权重是不是云端标定的」。
        // 数据来自推股产物 metadata（LocalPicks 写入），界面不自己算。
        ParityStrip(
            parityViewOfArtifact(
                if (state.localMiddayPicks.picks.isNotEmpty()) state.localMiddayPicks.metadata
                else state.localPremarketPicks.metadata,
            ),
        )
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "信号时点 ${ts ?: "暂无"} · 实时价来自腾讯行情" + if (liveCount == 0) "（休市或未取到，显示信号价）" else "",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text("名称", modifier = Modifier.weight(2.2f), style = HeadCell)
                    Text("现价", modifier = Modifier.weight(1.2f), style = HeadCell)
                    Text("涨跌", modifier = Modifier.weight(1.1f), style = HeadCell)
                    Text("信号价", modifier = Modifier.weight(1.2f), style = HeadCell)
                    Text("较信号", modifier = Modifier.weight(1.2f), style = HeadCell)
                }
                HorizontalDivider()
                rows.forEach { s ->
                    val q = state.pickQuotes[s.code]
                    val livePrice = q?.price
                    val livePct = q?.pctChg
                    val refPrice = s.price
                    val diff = if (livePrice != null && refPrice != null && refPrice != 0.0) {
                        (livePrice - refPrice) / refPrice * 100
                    } else null
                    val color = livePct?.let { if (it >= 0) UP else DOWN } ?: SkColors.textSecondary
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openDetail(s.code) }.padding(vertical = 3.dp),
                    ) {
                        Column(Modifier.weight(2.2f)) {
                            Text(
                                s.name ?: s.code,
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                listOfNotNull(s.code, s.score?.let { String.format("分 %.1f", it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                            )
                        }
                        Text(
                            (livePrice ?: refPrice)?.let { String.format("%.2f", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f),
                        )
                        Text(
                            livePct?.let { String.format("%+.2f%%", it) }
                                ?: s.pctChg?.let { String.format("%+.2f%%", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1.1f),
                        )
                        Text(
                            refPrice?.let { String.format("%.2f", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary, modifier = Modifier.weight(1.2f),
                        )
                        Text(
                            diff?.let { String.format("%+.2f%%", it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = diff?.let { if (it >= 0) UP else DOWN } ?: SkColors.textSecondary,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
                Text(
                    "点任意行看详情；「较信号」= 现价相对信号价的变动，用于判断是否已追高。",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                )
            }
        }
    }
}

/** 盘中心跳时点：让「数据是不是刚刚的」一眼可见，而不是靠猜。 */
private fun heartbeatText(state: UiState): String {
    val phase = com.skapp.app.data.MarketHours.phase()
    val inSession = com.skapp.app.data.MarketHours.isTrading() || com.skapp.app.data.MarketHours.isAuction()
    if (state.heartbeatAt <= 0L) {
        if (inSession) return "心跳未启动"
        return when (phase) {
            com.skapp.app.data.MarketHours.Phase.CLOSED_DAY -> "休市日 · 心跳暂停"
            com.skapp.app.data.MarketHours.Phase.PRE_OPEN -> "盘前 · 心跳暂停"
            com.skapp.app.data.MarketHours.Phase.LUNCH_BREAK -> "午间休市 · 心跳暂停"
            com.skapp.app.data.MarketHours.Phase.AFTER_HOURS -> "盘后 · 心跳暂停"
            else -> "非交易时段 · 心跳暂停"
        }
    }
    val age = ((System.currentTimeMillis() - state.heartbeatAt) / 1000).coerceAtLeast(0)
    val label = state.heartbeatLabel.takeIf { it.isNotBlank() } ?: "运行中"
    return "心跳 ${age}s 前 · $label"
}
