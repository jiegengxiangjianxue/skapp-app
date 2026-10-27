package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    // 进页面就自动算一次因子否决板（内部有缓存与去重，重复进入不会重复拉云端）
    LaunchedEffect(Unit) { vm.loadFactorBoard() }
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
                        // 仓位链路（只减不增，每一步都必须说明来源）：
                        //   基准上限(云端) → ETF 份额调档(聪明钱) → 样本外/市况校准闸门
                        val (smCap, adjReason) = state.smartMoney?.capAdjust(baseCap) ?: (null to "")
                        if (adjReason.isNotBlank()) {
                            val changed = smCap != null && baseCap != null && smCap != baseCap
                            Text(
                                "① ETF 份额信号：$adjReason",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (changed) MaterialTheme.colorScheme.primary else Color.Gray,
                            )
                        }

                        val gate = state.factorBoard?.gate
                        val afterSm = smCap ?: baseCap
                        if (gate != null && afterSm != null) {
                            val capped = gate.applyCap(afterSm)
                            Text(
                                "② 校准闸门：${gate.confidenceLabel} · 可信度 ${(gate.confidence * 100).toInt()}%" +
                                    " × 买入折扣 ${(gate.buyFactor * 100).toInt()}% = 仓位系数 ${gate.positionPctLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (capped < afterSm) MaterialTheme.colorScheme.primary else Color.Gray,
                            )
                            if (capped < afterSm) {
                                Text(
                                    "校准后仓位上限：${String.format("%.0f%%", capped * 100)}" +
                                        "（基准 ${String.format("%.0f%%", baseCap!! * 100)}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 聪明钱：宽基 ETF 份额跟踪（按指数聚合交易所日度总份额）──
        item { SmartMoneyCard(state, vm) }

        // ── 板块主线：云端 sector_engine 的 27 个板块分档（与因子否决并列的第二道过滤）──
        item { SectorBoardCard(state) }

        // ── 因子否决：云端 v3 标定权重 × 端上 16 指标，外加样本外校准闸门 ──
        item { FactorBoardCard(state, vm) }

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
            sm.staleText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8860B))
            }
            Spacer(Modifier.height(6.dp))
            if (sm.ready) {
                Text(
                    "信号成熟（${sm.progressText}）· ${sm.etfsUsed} 个宽基指数合计 · 窗口 ${sm.windowDays} 天 · " +
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
                                if (e.nEtfs > 1) "${e.nEtfs} 只 ETF 合计 · ${e.liveDays} 天历史"
                                else "${e.code} · ${e.liveDays} 天历史",
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
                    "每个指数取该指数下全部 ETF 的份额合计（沪深300 就聚合了 30 只），来源为上交所 ETF 规模 / " +
                        "深交所基金规模日度披露，单位亿份；日/5日/20日为份额变动百分比，样本不足显示「—」，不用估算补齐。",
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

/**
 * 因子否决卡：把云端 weight_calibrator_v3 的标定用在端上。
 *
 * 界面刻意不写「评分越高越该买」——云端实测这 16 个指标的看多胜率只有 43~45%，
 * 低于 50% 基准，所以这里只表达「有没有被否掉」，并且把基准胜率与校准系数一起摊开。
 */
@Composable
private fun FactorBoardCard(state: UiState, vm: SkappViewModel) {
    val fb = state.factorBoard
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("因子否决 · 云端 v3 标定", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.factorBoardLoading) "计算中…" else "重算",
                    Modifier.clickable { vm.loadFactorBoard(force = true) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (fb == null) {
                Text(
                    "点「重算」加载：需要云端 weight_calibration.json + 本地日 K（≥60 根）。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                return@Column
            }

            val calib = fb.calib
            if (calib != null) {
                Text(
                    "${calib.engine} · ${calib.horizon} 日持有 · ${calib.specs.size} 个指标 · " +
                        "看多基准 ${calib.bullBaseRate?.let { String.format("%.1f", it) } ?: "—"}% / " +
                        "看空 ${calib.bearBaseRate?.let { String.format("%.1f", it) } ?: "—"}%",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            } else {
                Text("云端标定不可用，无法打分。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            fb.gate?.let { g ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "校准闸门：可信度 ${(g.confidence * 100).toInt()}%（${g.confidenceLabel}）· " +
                        "买入折扣 ${(g.buyFactor * 100).toInt()}% · 仓位系数 ${g.positionPctLabel}",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                )
                g.notes.take(2).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(fb.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)

            fb.scored.take(8).forEach { r ->
                val sc = r.score ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(
                                r.code,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                sc.verdict.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = when (sc.verdict) {
                                    com.skapp.app.data.FactorWeightStrategy.Verdict.AVOID ->
                                        MaterialTheme.colorScheme.error
                                    com.skapp.app.data.FactorWeightStrategy.Verdict.WATCH -> Color(0xFFB8860B)
                                    else -> Color.Gray
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "多${sc.bullCount}/空${sc.bearCount}",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                        }
                        sc.reasons.take(2).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Text(
                        String.format("%+.2f", sc.netScore),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sc.netScore < 0) MaterialTheme.colorScheme.error else Color.Gray,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                fb.scored.firstOrNull()?.score?.caveat
                    ?: "AVOID 才是有信息量的结论；PASS 只表示本模块没有否决它。",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            if (fb.dataNote.isNotBlank()) {
                Text("云端数据：${fb.dataNote}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

/**
 * 板块主线卡。
 *
 * 只陈述云端 sector_engine 已经把话说死的东西：哪些板块是主线、哪些在回避名单、分数和分档是多少。
 * 个股归属用 industry_map 反查，**查不到就直说「未知板块」**，不猜、不做模糊匹配——
 * 前面查过两份映射源，一份覆盖率只有 1050 只、另一份每板块只有 1 只代表股，
 * 硬凑出来的归属比没有更糟。
 */
@Composable
private fun SectorBoardCard(state: UiState) {
    val sb = state.sectorBoard ?: return
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("板块主线 · sector_engine", fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(sb.date.ifBlank { null }, "${sb.sectors.size} 个板块")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }

            if (sb.mainline.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "主线：" + sb.mainlineRows.joinToString("、") {
                        "${it.name}（${it.grade} ${String.format("%.0f", it.total)}）"
                    }.ifBlank { sb.mainline.joinToString("、") },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                sb.mainlineRows.firstOrNull()?.let { top ->
                    Text(
                        listOfNotNull(
                            top.leader?.let { "龙头 $it ${top.leaderPct?.let { p -> String.format("%+.1f%%", p) } ?: ""}" },
                            if (top.nZt > 0) "涨停 ${top.nZt}" else null,
                            top.chg5d?.let { "5日 ${String.format("%+.1f%%", it)}" },
                            top.momentum?.let { "动量 ${String.format("%.0f", it)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("强势板块", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            sb.topSectors(5).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${r.grade} ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when (r.grade) {
                            "B" -> MaterialTheme.colorScheme.primary
                            "D" -> MaterialTheme.colorScheme.error
                            else -> Color.Gray
                        },
                    )
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(
                        String.format("%.1f", r.total),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    )
                    r.chg5d?.let {
                        Text(
                            "  5日" + String.format("%+.1f%%", it),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (sb.avoid.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "云端回避名单：" + sb.avoid.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 个股归属：说清用的是哪份源、覆盖多少，覆盖率太低就直接警告——
            // industry_map_em 兜底时只有 1% 的票能落到板块上，这种「有映射」其实没有意义
            val cov = com.skapp.app.data.SectorLink.coverage(sb)
            when (sb.mapSource) {
                com.skapp.app.data.SectorLink.MapSource.STOCK_SECTOR_MAP -> Text(
                    "个股归属：成分股反查 ${sb.stockToSector.size} 只，其中 " +
                        "${com.skapp.app.data.SectorLink.mappedCount(sb)} 只落在本板块表" +
                        (if (sb.mappedSectorTotal > 0) "（板块表只跟踪 ${sb.sectors.size}/${sb.mappedSectorTotal} 个板块）" else ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                com.skapp.app.data.SectorLink.MapSource.INDUSTRY_MAP_EM -> Text(
                    "个股归属：只有兜底的行业名映射，覆盖 ${String.format("%.1f%%", cov * 100)}" +
                        "（行业层级与板块不一致，多数票只能标「未知板块」）",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8860B),
                )
                com.skapp.app.data.SectorLink.MapSource.NONE -> Text(
                    "缺个股行业映射：只显示板块级强弱，个股归属不可用",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8860B),
                )
            }
        }
    }
}
