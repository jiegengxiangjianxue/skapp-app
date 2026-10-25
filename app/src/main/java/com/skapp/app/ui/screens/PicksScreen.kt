package com.skapp.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skapp.app.data.PickSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
    val poolSignals = remember(state.pools) {
        state.pools.flatMap { pool ->
            pool.items.map { it ->
                com.skapp.app.data.PickSignal(
                    code = it.code, name = it.name, price = it.price,
                    pctChg = it.pctChg, score = it.score,
                    why = listOf("【${pool.name}】"),
                )
            }
        }
    }
    val tabs = listOf(
        "实时选股" to emptyList<com.skapp.app.data.PickSignal>(),
        "指标共振" to emptyList<com.skapp.app.data.PickSignal>(),
        "多维评分" to emptyList<com.skapp.app.data.PickSignal>(),
        "涨停" to emptyList<com.skapp.app.data.PickSignal>(),
        "多池" to poolSignals,
        "板块" to emptyList<com.skapp.app.data.PickSignal>(),
    )
    // 本地扫描四 tab 的扫描模式：score 达标池 / all 全分析池 / limitup 涨停扫描
    val tabModes = listOf("score", "all", "all", "limitup")
    // 进入对应 tab 自动拉取（本地 tab 直接扫描，多池拉网页版池）
    LaunchedEffect(tab) {
        when (tab) {
            in 0..3 -> vm.runScreen(tabModes[tab])
            4 -> if (state.pools.isEmpty() && !state.poolsLoading) vm.loadPools()
        }
    }

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

        // ── 本地扫描 Tab（0-3：设备直连行情 + 本地引擎，非 CI 快照）──
        if (tab <= 3) {
            val eligible = state.screened.filter { sc ->
                tab == 3 || (
                    sc.detailStatus == "ok" &&
                        sc.buyable == true &&
                        isMainBoardPick(sc.code) &&
                        (sc.pctChg ?: Double.POSITIVE_INFINITY) < 9.6
                    )
            }
            val sorted = when (tab) {
                1 -> eligible.sortedByDescending { resonanceOf(it) }
                3 -> state.screened.filter { isMainBoardPick(it.code) }
                    .sortedByDescending { it.pctChg ?: 0.0 }
                else -> eligible
            }
            val hasRun = state.screened.isNotEmpty() || state.screenMsg != null || state.screening
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${tabs[tab].first}（本地实时计算）", fontWeight = FontWeight.SemiBold)
                        Text(
                            "实时拉全市场行情 → 预筛 → 本地 12 引擎 + 29 因子现算（非云端快照）",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    if (state.screening) {
                        CircularProgressIndicator()
                    } else {
                        Card(modifier = Modifier.clickable { vm.runScreen(tabModes[tab], force = true) },
                            colors = CardDefaults.cardColors()) {
                            Text("开始实时扫描", Modifier.padding(10.dp),
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
                if (sorted.isEmpty() && !state.screening) {
                    // 本地扫描失败/无结果时降级：直接展示云端今日推股（真实数据，30信号/20评分/8涨停）
                    val fb = when (tab) { 1 -> p.signals; 2 -> p.values; 3 -> p.limitUp; else -> p.signals }
                        .filter { sig ->
                            isMainBoardPick(sig.code) && (tab == 3 || (sig.pctChg != null && sig.pctChg < 9.6))
                        }
                    if (fb.isNotEmpty()) {
                        Text(
                            "本地实时扫描暂不可用（${state.screenMsg ?: "网络受限"}），以下为云端今日推股（真实数据）",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922),
                        )
                        Spacer(Modifier.height(6.dp))
                        fb.forEach { sig -> PickCard(sig, tabs[tab].first, vm, state) }
                    } else {
                        Text("暂无结果：点击「开始实时扫描」（约需 10-60 秒，视网络）", color = Color.Gray)
                    }
                } else {
                    Text("命中 ${sorted.size} 只（${tabs[tab].first}排序 · 本地引擎现算）",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    sorted.forEach { sc ->
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
                                        "　共振 ${"%.0f".format(resonanceOf(sc) * 100)}%" +
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
            return@Column
        }

        // ── 网页版数据面板（多池/板块，CI 产出但与本地扫描互补）──
        if (tab >= 4) {
            Text(
                "该页为网页版 CI 产出（与本地实时扫描互补）；本地实时结果在「实时选股」等前四个页。",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD29922),
            )
        }
        if (tab == 5) {
            SectorsScreen(vm, state)
            return@Column
        }
        // 云端三 Tab 兜底主板红线过滤（上游若混入 300/301/688/北交所不渲染）
        // 推股不推涨停票：涨幅≥9.6%（主板涨停附近）的标的不进推荐
        val current = (tabs.getOrNull(tab)?.second ?: emptyList()).filter { sig ->
            isMainBoardPick(sig.code) && sig.pctChg != null && sig.pctChg < 9.6
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
                        "${tabs[tab].first} · ${current.size} 只 · ${state.pools.firstOrNull()?.ts ?: "时点暂无"}",
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
    val image = rememberRemoteImage(s.imageUrl)
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
                if (image != null) {
                    Image(image.asImageBitmap(), contentDescription = s.name ?: s.code,
                        modifier = Modifier.size(48.dp))
                } else if (s.imageUrl != null) {
                    Text("图片不可用", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Column(Modifier.weight(1f).padding(start = if (s.imageUrl != null) 8.dp else 0.dp)) {
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

@Composable
private fun rememberRemoteImage(url: String?): Bitmap? {
    if (url.isNullOrBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) return null
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) null
                else connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
            }.getOrNull()
        }
    }
    return bitmap
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

/** 主板代码红线：600/601/603/605/000/001/002（兼容带 sh/sz 前缀的编码） */
internal fun isMainBoardPick(code: String): Boolean =
    code.takeLast(6).take(3) in setOf("600", "601", "603", "605", "000", "001", "002")

/** 指标共振度：本地引擎 bullish 占比（0-1） */
private fun resonanceOf(sc: com.skapp.app.data.LocalScreener.Screened): Double {
    val es = sc.engine?.engines ?: return 0.0
    val valid = es.count { it.score != null }
    if (valid == 0) return 0.0
    return es.count { it.signal == "bullish" } * 1.0 / valid
}
