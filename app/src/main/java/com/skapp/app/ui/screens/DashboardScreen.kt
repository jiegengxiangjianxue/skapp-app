package com.skapp.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.skapp.app.data.LocalScreener
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 看板首页 —— 网页版 panel-board 完整对齐重构。
 *
 * 网页版结构（index.html）：顶部 KPI 8 格（涨停/跌停/炸板/封板率 + 宽度热力条）
 *   + 盘面解读(commentary) + 主线(mainlines) + 行业排行(panel-industry)
 *   + 个股查(panel-stock) + 板块池(panel-board)。
 * 本页按同一 IA 重排，每区独立空态（数据缺哪个就标哪个，不再整页空白）。
 */
private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

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
                        (b["ts"]?.toString() ?: "时点暂无") + " · 网页版 panel-board",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Text("刷新", Modifier.clickable { vm.loadBoard() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── KPI 8 格：涨停/跌停/炸板/封板率/上涨/下跌/成交/阶段（网页版 kpis 对齐）──
        item {
            val limitUp = numOf(market["limit_up"])
            val limitDown = numOf(market["limit_down"])
            val broken = numOf(market["broken"])
            val advance = numOf(market["advance"])
            val decline = numOf(market["decline"])
            val turnover = numOf(market["turnover_yi"] ?: (market["turnover"] as? Map<*, *>)?.get("total_yi"))
            val phase = market["phase"]?.toString() ?: "—"
            val phaseScore = numOf(market["phase_score"])
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("涨停", limitUp?.toInt()?.toString(), UP, Modifier.weight(1f))
                    KpiCard("跌停", limitDown?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("炸板", broken?.toInt()?.toString(), Color(0xFFD29922), Modifier.weight(1f))
                    KpiCard("上涨", advance?.toInt()?.toString(), UP, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KpiCard("下跌", decline?.toInt()?.toString(), DOWN, Modifier.weight(1f))
                    KpiCard("成交(亿)", turnover?.let { String.format("%.0f", it) }, Color.LightGray, Modifier.weight(1f))
                    KpiCard("阶段", phase, Color(0xFF58A6FF), Modifier.weight(1f))
                    KpiCard("阶段分", phaseScore?.let { String.format("%.1f", it) }, Color(0xFF58A6FF), Modifier.weight(1f))
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
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
        }

        // ── 盘面解读（commentary）：每日总结正文 ──
        item { CommentaryCard() }

        // ── 主线（mainlines）：当前主线题材 ──
        item {
            val ml = b["mainlines"] as? List<*>
            if (!ml.isNullOrEmpty()) {
                SectionTitle("主线题材（${ml.size}）")
                ml.take(6).forEach { it2 ->
                    val m = it2 as? Map<*, *> ?: return@forEach
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((m["name"] ?: m["theme"])?.toString() ?: "—", fontWeight = FontWeight.Medium)
                            Text("${(m["heat"] as? Number)?.toDouble()?.let { String.format("%.0f", it) } ?: "—"} 热度",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            } else {
                EmptyNote("主线题材：latest.json 未产出 mainlines")
            }
        }

        // ── 行业排行（panel-industry）──
        item {
            val secs = b["sectors"] as? List<*>
            if (!secs.isNullOrEmpty()) {
                SectionTitle("行业/板块热度（${secs.size}）")
                secs.take(10).forEach { it2 ->
                    val m = it2 as? Map<*, *> ?: return@forEach
                    val chg = (m["chg"] ?: m["pctChg"] ?: m["avg_chg_1d"]) as? Number
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((m["name"] ?: m["sector"] ?: m["board"])?.toString() ?: "—",
                                fontWeight = FontWeight.Medium)
                            Text(chg?.let { String.format("%+.2f%%", it.toDouble()) } ?: "—",
                                color = if ((chg?.toDouble() ?: 0.0) >= 0) UP else DOWN,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                EmptyNote("行业排行：latest.json 未产出 sectors/tx_sector_ranking/themes")
            }
        }

        // ── 涨停池（limit_up_pool）──
        item {
            val lu = b["limit_up"] as? List<*>
            if (!lu.isNullOrEmpty()) {
                SectionTitle("涨停池（${lu.size}）")
                lu.take(12).forEach { it2 ->
                    val m = it2 as? Map<*, *> ?: return@forEach
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(m["name"] ?: m["code"])?.toString() ?: "—"}",
                                fontWeight = FontWeight.Medium)
                            Text("${m["lianban"] ?: m["board_num"] ?: "1"}板 · ${m["seal_label"] ?: m["industry"] ?: ""}",
                                style = MaterialTheme.typography.bodySmall, color = UP)
                        }
                    }
                }
            } else {
                EmptyNote("涨停池：latest.json 未产出 limit_up_pool")
            }
        }

        // ── 个股查（panel-stock）：搜索入口 ──
        item { StockSearchCard(vm, state) }

        // ── 指数与全球市场（A 股 + 全球，原两区已并入）──
        item {
            if (state.aShareIndices.isNotEmpty() || state.globalIndices.isNotEmpty()) {
                SectionTitle("指数与全球市场")
                (state.aShareIndices + state.globalIndices).forEach { g ->
                    val pct = (g["pctChg"] as? Number)?.toDouble()
                    val price = (g["price"] as? Number)?.toDouble()
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text((g["name"] ?: g["code"])?.toString() ?: "—",
                                fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(price?.let { String.format("%.2f", it) } ?: "—",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(pct?.let { String.format("%+.2f%%", it) } ?: "—",
                                    color = if ((pct ?: 0.0) >= 0) UP else DOWN,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── 市场状态机（进攻/防守）──
        state.marketState?.let { ms ->
            item { MarketStateCard(ms) }
        }

        // ── 云端数据源健康度 ──
        state.allSourcesSummary?.let { s ->
            item {
                Text(
                    "数据源 $s",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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

/** 读远端文本（含 gzip 解压；raw 会按 gzip 返回，直接读流是乱码） */
private fun readRemoteText(url: String): String? = try {
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 20000; readTimeout = 20000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
    }
    if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) { conn.disconnect(); null }
    else {
        val enc = conn.contentEncoding
        val st = if (enc == "gzip" || enc == "x-gzip") java.util.zip.GZIPInputStream(conn.inputStream)
                 else conn.inputStream
        st.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }
} catch (e: Exception) { null }

@Composable
private fun KpiCard(label: String, value: String?, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(2.dp))
            Text(value ?: "—", fontWeight = FontWeight.Bold, color = color, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CommentaryCard() {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = readRemoteText(
            "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data/daily_brief.json")
            ?.let { runCatching { org.json.JSONObject(it).optString("text") }.getOrNull() }
    }
    val t = text ?: return
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("盘面解读（盘后日报）", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            t.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(">") }
                .take(18)
                .forEach {
                    Text(it.trimStart('#').trim(),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
        }
    }
}

@Composable
private fun StockSearchCard(vm: SkappViewModel, state: UiState) {
    var query by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("个股查（panel-stock）", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                label = { Text("输入股票代码或名称，如 600519") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (query.isNotBlank() && state.searchResults.isNotEmpty()) {
                state.searchResults.take(6).forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.openDetail(s.code) },
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${s.name ?: s.code}", fontWeight = FontWeight.Medium)
                        Text("查看 ›", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(msg: String) {
    Text(msg, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}
