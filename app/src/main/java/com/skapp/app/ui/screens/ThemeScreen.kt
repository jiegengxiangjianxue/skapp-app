package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import org.json.JSONObject

/**
 * 题材挖掘页 —— 云端 theme_report.json（每日收盘后由 theme_mining 引擎产出）。
 *
 * 方法（公众号概念挖掘方法论，用户指定借鉴 2026-10-24）：
 *   题材拆分支（不是把板块当标签）→ 在炒的 vs 未启动补涨 → 补涨星级 → 炒作路径时间线。
 * 展示不编造：所有数值来自引擎实测（连板高度/20日涨幅/60日位置/板块时序）。
 */

/** 读远端文本（含 gzip 解压）。raw.githubusercontent.com 会按 gzip 返回，
 *  HttpURLConnection 默认请求 gzip 但不自动解压，直接读流是乱码 → JSON 解析失败 → 页面空白。 */
private fun readRemoteText(url: String): String? = try {
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 20000; readTimeout = 20000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
    }
    val code = conn.responseCode
    if (code != java.net.HttpURLConnection.HTTP_OK) { conn.disconnect(); null }
    else {
        val enc = conn.contentEncoding
        val stream = if (enc == "gzip" || enc == "x-gzip")
            java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
        val txt = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        txt
    }
} catch (e: Exception) { null }

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ThemeScreen(vm: SkappViewModel, state: UiState) {
    var json by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val txt = withContext(Dispatchers.IO) {
            readRemoteText(
                "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data/theme_report.json")
        }
        json = txt?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) failed = true
        loading = false
    }

    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            loading -> Text("正在加载题材报告…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            failed -> Text(
                "题材报告加载失败：请检查网络；云端文件存在时会显示题材数与更新时间。",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                val root = json!!
                Text(
                    "生成 ${root.optString("ts")} · ${root.optInt("n_themes")} 个题材",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                val themes = root.optJSONArray("themes")
                if (themes == null || themes.length() == 0) {
                    Text("本轮无题材产出。", color = Color.Gray)
                } else {
                    for (i in 0 until themes.length()) {
                        ThemeCard(themes.optJSONObject(i) ?: continue, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(t: JSONObject, vm: SkappViewModel) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            // 标题 + tier + 龙头
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(t.optString("theme"), fontWeight = FontWeight.Bold)
                Text(
                    "${t.optString("tier")} · 龙头 ${t.opt("leader") ?: "暂无"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            Spacer(Modifier.height(4.dp))

            // 炒作路径
            val path = t.optJSONArray("path")
            if (path != null && path.length() > 0) {
                Text("炒作路径", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall)
                for (i in 0 until path.length()) {
                    Text("· ${path.optString(i)}",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFD29922))
                }
                Spacer(Modifier.height(4.dp))
            }

            // 板块涨幅
            Text(
                "板块 1日 ${t.opt("chg_1d") ?: "—"} / 5日 ${t.opt("chg_5d") ?: "—"} / 20日 ${t.opt("chg_20d") ?: "—"}　热度 ${t.opt("score") ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            // 在炒的（连板梯队）
            val hot = t.optJSONArray("hot")
            Text("在炒的（涨停梯队 ${hot?.length() ?: 0} 只）", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            if (hot != null) {
                for (i in 0 until hot.length()) {
                    val h = hot.optJSONObject(i) ?: continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${h.optString("name")} ${h.optString("code")}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${h.opt("lianban") ?: 0}板 · 封板${h.optString("seal_label")} · 20日${h.opt("chg20") ?: "—"}%",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // 补涨候选
            val watch = t.optJSONArray("watchlist")
            Text("未启动补涨候选（${watch?.length() ?: 0} 只 · 星级越高越优先）",
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            if (watch != null) {
                for (i in 0 until watch.length()) {
                    val w = watch.optJSONObject(i) ?: continue
                    val star = w.optInt("star", 0)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).let {
                        it
                    }, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${w.optString("code")}　${"★".repeat(star)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("20日 ${w.opt("chg20") ?: "—"}% · 位置 ${w.opt("pos") ?: "—"}% · 现价 ${w.opt("close") ?: "—"}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            val deg = t.optJSONArray("degraded")
            if (deg != null && deg.length() > 0) {
                Spacer(Modifier.height(4.dp))
                for (i in 0 until deg.length()) {
                    Text("降级：${deg.optString(i)}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}
