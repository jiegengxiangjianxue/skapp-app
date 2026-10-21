package com.skapp.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.skapp.app.data.PickSignal
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 推股 Tab：顶栏切换「指标共振 / 多维评分 / 涨停 / 回避」，
 * 不再靠滑动翻页。市场概况含全球股市 + A 股指数涨跌。
 */
@Composable
fun PicksScreen(vm: SkappViewModel, state: UiState) {
    val p = state.picks
    var tab by remember { mutableStateOf(0) }

    // 本地实时为默认（用户核心诉求：不用云端旧快照）；云端池做兜底
    val tabs = listOf(
        "本地实时" to emptyList<com.skapp.app.data.PickSignal>(),
        "指标共振" to p.signals,
        "多维评分" to p.values,
        "涨停" to p.limitUp,
    )

    Column(Modifier.fillMaxWidth()) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "推股",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            tabs.forEachIndexed { i, (name, list) ->
                TopTab(name, list.size, tab == i) { tab = i }
            }
        }
        HorizontalDivider()

        // ── 市场概况（含全球股市 + A 股指数）──
        MarketOverview(state)

        HorizontalDivider()

        // ── 本地实时选股 Tab（默认，实时数据 + 本地算法引擎）──
        // 首次进入自动跑（盘中才跑；休市时点按钮手动跑）
        if (tab == 0) {
            if (state.screened.isEmpty() && !state.screening && !(state.screenMsg?.contains("休市") ?: false)) {
                LaunchedEffect(Unit) { vm.runScreen() }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本地实时选股", fontWeight = FontWeight.SemiBold)
                        Text(
                            "实时拉全市场行情 → 预筛 → 本地 12 引擎 + 29 因子现算（非云端快照）",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    if (state.screening) {
                        CircularProgressIndicator()
                    } else {
                        Card(modifier = Modifier.clickable { vm.runScreen() },
                            colors = CardDefaults.cardColors()) {
                            Text("开始实时选股", Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.screenMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922))
                }
                Spacer(Modifier.height(8.dp))
                if (state.screened.isEmpty() && !state.screening) {
                    Text("暂无结果：点击「开始实时选股」（约需 10-40 秒，视网络）", color = Color.Gray)
                } else {
                    Text("命中 ${state.screened.size} 只（按本地引擎综合分降序）",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    state.screened.forEach { sc ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable { vm.openDetail(sc.code) },
                            colors = CardDefaults.cardColors(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(sc.name ?: sc.code, fontWeight = FontWeight.Medium)
                                        Text(sc.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            sc.price?.let { String.format("%.2f", it) } ?: "暂无",
                                            color = pctColorS(sc.pctChg),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "实时 " + (sc.pctChg?.let { String.format("%.2f%%", it) } ?: "—"),
                                            color = pctColorS(sc.pctChg),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "本地引擎 ${sc.engine?.composite?.let { String.format("%.1f", it) } ?: "insufficient"}" +
                                        "（${sc.engine?.nValid ?: 0}/12 引擎）" +
                                        (sc.volRatio?.let { "　量比 ${String.format("%.2f", it)}" } ?: "") +
                                        (sc.mv?.let { "　市值 ${String.format("%.0f 亿", it / 1e8)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scoreColorS(sc.engine?.composite),
                                )
                                sc.engine?.engines?.filter { it.score != null }
                                    ?.sortedByDescending { it.score }?.take(3)
                                    ?.forEach { e ->
                                        Text(
                                            "· ${engineLabel(e.engine)} ${String.format("%.0f", e.score!!)} " +
                                                (e.reasons.firstOrNull() ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.LightGray,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // ── 云端快照列表（非实时，标注数据来源与年龄）──
        val current = tabs.getOrNull(tab)?.second ?: emptyList()
        if (tab > 0) {
            Text(
                "该页为云端 CI 快照（ts=${p.ts ?: "未知"}），盘中非实时；本地实时数据在「本地实时」页。",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD29922),
            )
        }
        if (current.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无数据：上游未产出或文件不可达。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${tabs[tab].first} · ${current.size} 只　·　${listOfNotNull(p.date, p.session, p.ts).joinToString(" · ").ifEmpty { "时点暂无" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                items(current, key = { it.code + "_" + tabs[tab].first }) { s ->
                    PickCard(s, tabs[tab].first, vm, state)
                }
            }
        }
    }
}

@Composable
private fun TopTab(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                name,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                "$count",
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 市场概况：A 股涨跌统计 + 成交 + A股指数 + 全球指数 */
@Composable
private fun MarketOverview(state: UiState) {
    val p = state.picks
    val m = p.market
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.isNotEmpty()) {
            Text("市场概况", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                m.entries.joinToString("　") { (k, v) -> "${marketLabel(k)} ${fmt(v)}" }
                    .ifEmpty { "暂无数据" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.aShareIndices.isNotEmpty()) {
            Text(
                "A 股指数：" + state.aShareIndices.joinToString("　") { g ->
                    val name = (g["name"] ?: g["code"] ?: "").toString()
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    "$name ${pct?.let { String.format("%+.2f%%", it) } ?: "—"}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.globalIndices.isNotEmpty()) {
            Text(
                "全球股市：" + state.globalIndices.joinToString("　") { g ->
                    val name = (g["name"] ?: g["code"] ?: "").toString()
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    "$name ${pct?.let { String.format("%+.2f%%", it) } ?: "—"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
            )
        }
    }
}

@Composable
private fun PickCard(s: PickSignal, group: String, vm: SkappViewModel, state: UiState) {
    // 实时报价优先；休市时回落到云端快照价（快照即收盘价）
    val live = state.pickQuotes[s.code]
    val price = live?.price ?: s.price
    val pct = live?.pctChg ?: s.pctChg
    val priceTag = if (live != null) "实时" else "快照"
    val color = when {
        pct == null -> Color.Gray
        pct > 0 -> Color(0xFFF85149)
        pct < 0 -> Color(0xFF3FB950)
        else -> Color.Gray
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(s.code) },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(s.name ?: s.code, fontWeight = FontWeight.Medium)
                    Text(s.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        price?.let { String.format("%.2f", it) } ?: "暂无数据",
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        (pct?.let { String.format("%.2f%%", it) } ?: "—") + "　$priceTag",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            s.score?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "评分 ${String.format("%.2f", it)}" + (s.engine?.let { e -> "　·　$e" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val why = s.why.filter { it.isNotBlank() }
            if (why.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                why.take(3).forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            val tech = s.detail?.technical
            if (!tech.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tech.entries.filter { it.value != null }
                        .joinToString("　") { "${it.key.uppercase()} ${String.format("%.2f", it.value!!)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            if (s.detail != null && s.detail.status == "insufficient") {
                Spacer(Modifier.height(2.dp))
                Text(
                    "个股详情：insufficient",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD29922),
                )
            }
        }
    }
}

private fun marketLabel(k: String): String = when (k) {
    "advance" -> "涨"
    "decline" -> "跌"
    "limit_up" -> "涨停"
    "limit_down" -> "跌停"
    "turnover_yi" -> "成交(亿)"
    "turnover" -> "成交"
    else -> k
}

private fun pctColorS(pct: Double?): Color = when {
    pct == null -> Color.Gray
    pct > 0 -> Color(0xFFF85149)
    pct < 0 -> Color(0xFF3FB950)
    else -> Color.Gray
}

private fun scoreColorS(s: Double?): Color = when {
    s == null -> Color.Gray
    s >= 65 -> Color(0xFFF85149)
    s <= 35 -> Color(0xFF3FB950)
    else -> Color.Gray
}

private fun engineLabel(k: String): String = when (k) {
    "trend" -> "趋势"; "momentum" -> "动量"; "volume" -> "量能"
    "boll" -> "布林"; "macd" -> "MACD"; "supertrend" -> "SuperTrend"
    "keltner" -> "Keltner"; "choppiness" -> "震荡"; "mfi" -> "MFI"
    "vortex" -> "涡旋"; "obv" -> "OBV"; "position" -> "位置"
    else -> k
}

private fun fmt(v: Any?): String = when (v) {
    null -> "暂无数据"
    is Number -> String.format("%.2f", v.toDouble())
    else -> v.toString()
}
