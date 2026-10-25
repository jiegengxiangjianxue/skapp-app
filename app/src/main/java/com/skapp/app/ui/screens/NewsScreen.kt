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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
import com.skapp.app.data.NewsCalc
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

/**
 * 消息 Tab：只剩「快讯」和「主线」两个顶栏。
 * 每条快讯标题下方**直接显示实时算法计算**（标题命中的股票 → 实时行情 + 本地引擎）。
 */
@Composable
fun NewsScreen(vm: SkappViewModel, state: UiState) {
    var tab by remember { mutableStateOf(0) }   // 0 快讯 / 1 主线
    // 消息只在用户点击刷新时拉取，避免进入页面自动触发慢请求。
    val calcs = state.newsCalcs
    val items = calcs.filter { n ->
        if (tab == 1) n.newsTitle.startsWith("【主线】") else !n.newsTitle.startsWith("【主线】")
    }
    val expandedThemes = remember { mutableStateOf(setOf<String>()) }
    val themeNames = state.news.themes.mapNotNull { (it["name"] ?: it["theme"])?.toString() }.filter { it.isNotBlank() }
    val grouped = items.groupBy { item ->
        themeNames.firstOrNull { item.newsTitle.contains(it, ignoreCase = true) } ?: "未知"
    }

    Column(Modifier.fillMaxWidth()) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            NewsTab("快讯", calcs.count { !it.newsTitle.startsWith("【主线】") }, tab == 0) { tab = 0 }
            NewsTab("主线", calcs.count { it.newsTitle.startsWith("【主线】") }, tab == 1) { tab = 1 }
        }
        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "标题下方为实时算法计算结果（实时行情 + 本地引擎）",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            if (state.newsCalcLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    "刷新",
                    Modifier.clickable { vm.loadNewsCalcs() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (items.isEmpty() && !state.newsCalcLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("暂无数据：快讯未拉到或未命中。", color = Color.Gray)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                grouped.forEach { (theme, rows) ->
                    item(key = "theme:$theme") {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val current = expandedThemes.value
                                expandedThemes.value = if (theme in current) current - theme else current + theme
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$theme · ${rows.size} 条", fontWeight = FontWeight.SemiBold)
                            Text(if (theme in expandedThemes.value) "收起" else "展开",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (theme in expandedThemes.value) {
                        items(rows, key = { "news:${theme}:${it.newsTitle}:${it.ts.orEmpty()}" }) { n ->
                            NewsCardWithCalc(n, vm)
                        }
                    }
                }
            }
        }
    }
}

/** 一条快讯：标题 + 情绪 + 标题下的实时算法结果 */
@Composable
private fun NewsCardWithCalc(n: NewsCalc, vm: SkappViewModel) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            // 标题与远端图片；仅允许 HTTP(S)，失败显示占位。
            n.imageUrl?.let { url ->
                val image = rememberNewsImage(url)
                if (image != null) Image(image.asImageBitmap(), n.newsTitle, Modifier.fillMaxWidth().height(120.dp))
                else Text("图片不可用", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text(n.newsTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

            // 来源 + 时间 + 情绪
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                n.source?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                n.ts?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                val (sentiText, sentiColor) = when {
                    n.verdict.startsWith("真利好") -> "真利好" to UP
                    n.verdict.startsWith("利好·") -> n.verdict to Color(0xFFD29922)
                    n.verdict == "利空" -> "利空" to DOWN
                    n.sentiment == "pos" -> "利好" to UP
                    n.sentiment == "neg" -> "利空" to DOWN
                    else -> "中性" to Color.Gray
                }
                Text(sentiText, style = MaterialTheme.typography.bodySmall, color = sentiColor)
                Text(
                    if (n.verdict.isBlank() || n.verdict == "insufficient") "分析：insufficient" else n.verdict,
                    style = MaterialTheme.typography.bodySmall, color = sentiColor,
                )
                Text(
                    "方向：${n.direction.ifBlank { "insufficient" }} · 正面：${n.positiveReason?.takeIf { it.isNotBlank() } ?: "insufficient"} · 负面：${n.negativeReason?.takeIf { it.isNotBlank() } ?: "insufficient"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text(
                    "理由：${n.note?.takeIf { it.isNotBlank() } ?: "insufficient"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                if (n.sentimentHits.isNotEmpty()) {
                    Text(
                        "命中：${n.sentimentHits.take(4).joinToString(" ")}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                if (n.impactCodes.isNotEmpty()) {
                    Text(
                        "影响个股：${n.impactCodes.take(6).joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF58A6FF),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // ── 标题下的实时计算 ──
            when (n.status) {
                "no_stock" -> Text(
                    "该条未提及具体股票，无个股计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                else -> {
                    if (n.stocks.isEmpty()) {
                        Text("计算中或数据不足。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    n.stocks.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { vm.openDetail(s.code) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.name ?: s.code,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        s.code,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                    )
                                }
                                // 实时价 + 涨跌 + 量比
                                Text(
                                    "实时价 ${s.price?.let { String.format("%.2f", it) } ?: "暂无"}" +
                                        "　涨跌 ${s.pctChg?.let { String.format("%.2f%%", it) } ?: "—"}" +
                                        (s.volRatio?.let { "　量比 ${String.format("%.2f", it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pctColor(s.pctChg),
                                )
                                // 本地引擎分 + 信号 + 理由
                                Text(
                                    "本地引擎 ${s.localScore?.let { String.format("%.1f", it) } ?: "insufficient"}" +
                                        "　${signalText(s.signal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = signalColor(s.signal),
                                )
                                s.topReasons.take(2).forEach {
                                    Text(
                                        "· $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray,
                                    )
                                }
                            }
                            Text(
                                "详情 ›",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsTab(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }, colors = CardDefaults.cardColors()) {
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

private fun pctColor(pct: Double?): Color = when {
    pct == null -> Color.Gray
    pct > 0 -> UP
    pct < 0 -> DOWN
    else -> Color.Gray
}

private fun signalText(s: String?): String = when (s) {
    "bullish" -> "偏多"
    "bearish" -> "偏空"
    "neutral" -> "中性"
    else -> "数据不足"
}

private fun signalColor(s: String?): Color = when (s) {
    "bullish" -> UP
    "bearish" -> DOWN
    else -> Color.Gray
}

@Composable
private fun rememberNewsImage(url: String): Bitmap? {
    if (!(url.startsWith("https://") || url.startsWith("http://"))) return null
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                if (c.responseCode != HttpURLConnection.HTTP_OK) null
                else c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
            }.getOrNull()
        }
    }
    return bitmap
}
