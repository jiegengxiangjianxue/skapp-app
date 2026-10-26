package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.RemoteSources
import com.skapp.app.data.SkappApi
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 数据源自检页 —— 一键跑通 app 依赖的每一个远端 URL 并报告状态。
 *
 * 建这个页的原因（2026-10-25）：用户反馈「什么都加载不出来」，排查发现是
 * symbols.json 被当成裸数组解析（实际是 {ts,n,symbols:[...]}）导致代码表全空。
 * 这类「URL 通但解析形态不匹配」的问题没有页面可见，只能盲猜；本页把每个数据源
 * 的 HTTP 状态、解析结果条数、错误原因直接列出来，下次一眼定位。
 */
@Composable
fun DiagScreen(vm: SkappViewModel, state: UiState) {
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (state.registryStats.isEmpty()) vm.loadAllSources()
        rows = withContext(Dispatchers.IO) { probe() }
        running = false
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("数据源自检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SourceLedgerCard(state, vm)
        val stats = state.registryStats
        val registry = rows.firstOrNull { it.first == "源注册表" }?.second
        Text(
            if (running) "正在逐个探测…" else "端点检查：${rows.count { it.second.startsWith("OK") }} 成功 / ${rows.count { it.second.startsWith("FAIL") }} 失败 · registry total ${stats["total"] ?: "unknown"} / tested ${stats["tested"] ?: "unknown"} / success ${stats["success"] ?: "unknown"} / failure ${stats["failure"] ?: "unknown"} / stale ${stats["stale"] ?: "unknown"} / unknown ${stats["unknown"] ?: "unknown"} · ${registry ?: "registry unknown"}",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        rows.forEach { (name, result) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Row(Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("OK")) Color(0xFF3FB950) else Color(0xFFF85149),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLedgerCard(state: UiState, vm: SkappViewModel) {
    val ds = state.dataSources
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("数据源台账", fontWeight = FontWeight.SemiBold)
                Text(
                    "刷新",
                    Modifier.clickable { vm.loadDataSources() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (ds.total == 0) {
                Text("暂无数据：api/sources.json 不可达。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                return@Column
            }
            Text(
                "登记 ${ds.total} · 可用 ${ds.usable} · 已接执行器 ${ds.withFetcher} · 可用且已接 ${ds.live}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "可用但无执行器 ${ds.gap} · 不可用(blocked/unauth) ${ds.blocked} · 更新 ${ds.ts ?: "暂无"}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            if (ds.gapIds.isNotEmpty()) {
                Text(
                    "缺口（登记可用但没接）：${ds.gapIds.take(15).joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            if (ds.blockedIds.isNotEmpty()) {
                Text(
                    "不可用（不绕过）：${ds.blockedIds.take(10).joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        }
    }
}

private fun probe(): List<Pair<String, String>> {    fun http(url: String): String = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) skapp")
        }
        val code = conn.responseCode
        val body = if (code == 200) {
            val enc = conn.contentEncoding
            val s = if (enc == "gzip" || enc == "x-gzip") java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            s.bufferedReader().use { it.readText() }
        } else ""
        conn.disconnect()
        "$code|$body"
    } catch (e: Exception) {
        "ERR|${e.javaClass.simpleName}"
    }

    val out = ArrayList<Pair<String, String>>()
    fun check(name: String, url: String, verify: (String) -> String) {
        val r = http(url)
        val code = r.substringBefore("|")
        val body = r.substringAfter("|")
        out += name to if (code == "200") "OK HTTP200 ${verify(body)}" else "FAIL $code ${if (code=="ERR") body else ""}"
    }

    val registryResult = http(RemoteSources.allSources())
    val registryCode = registryResult.substringBefore("|")
    val registryBody = registryResult.substringAfter("|")
    out += "源注册表" to if (registryCode == "200") {
        val root = runCatching { JSONObject(registryBody) }.getOrNull()
        val sources = root?.optJSONArray("sources_registry")
            ?: root?.optJSONArray("sources")
            ?: root?.optJSONArray("registry")
        if (sources == null) "UNKNOWN：注册表未提供" else {
            var tested = 0
            var success = 0
            var failure = 0
            var stale = 0
            var unknown = 0
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i)
                if (source == null) {
                    unknown++
                    continue
                }
                val status = (source.optString("status") + " " + source.optString("result")).lowercase()
                when {
                    status.contains("success") || status.contains("ok") || status.contains("healthy") -> { tested++; success++ }
                    status.contains("failure") || status.contains("failed") || status.contains("error") -> { tested++; failure++ }
                    status.contains("stale") -> { tested++; stale++ }
                    else -> unknown++
                }
                val endpoint = source.optString("endpoint").ifBlank {
                    source.optString("url").ifBlank { source.optString("source_url") }
                }
                if (endpoint.isNotBlank()) out += "registry endpoint" to "UNKNOWN $endpoint"
            }
            "total=${sources.length()} tested=$tested success=$success failure=$failure stale=$stale unknown=$unknown"
        }
    } else "UNKNOWN：注册表不可达"

    // 代码表（本次故障点）
    check("代码表 symbols.json", RemoteSources.appFile(RemoteSources.OWNER_SYMBOLS, RemoteSources.REPO_SYMBOLS, "search/symbols.json")) { b ->
        val arr = runCatching { org.json.JSONArray(b) }.getOrNull()
            ?: runCatching { JSONObject(b).optJSONArray("symbols") }.getOrNull()
        if (arr != null) "解析${arr.length()}条" else "解析失败(形态不匹配)"
    }
    check("股票名称 stock_names", RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "stock_names.json")) { b ->
        runCatching { JSONObject(b).length() }.getOrNull()?.let { "${it}条" } ?: "解析失败"
    }
    check("推股 today_picks", RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "today_picks.json")) { b ->
        val p = runCatching { JSONObject(b).optJSONObject("picks") }.getOrNull()
        if (p == null) "无 picks 字段"
        else "信号${p.optJSONArray("signals")?.length() ?: 0}/评分${p.optJSONArray("values")?.length() ?: 0}/涨停${p.optJSONArray("limit_up")?.length() ?: 0}"
    }
    check("板块 api/sectors", RemoteSources.apiSectors()) { b ->
        val o = runCatching { JSONObject(b) }.getOrNull()
        "行业${o?.optJSONArray("industries")?.length() ?: 0}/概念${o?.optJSONArray("concepts")?.length() ?: 0}"
    }
    check("消息 api/news", RemoteSources.apiNews()) { b ->
        val o = runCatching { JSONObject(b) }.getOrNull()
        val hn = o?.optJSONObject("hot_news")?.optJSONArray("items")?.length() ?: 0
        val ex = o?.optJSONObject("extra")?.optJSONArray("items")?.length() ?: 0
        "热点$hn+附加$ex"
    }
    check("决策 decision", RemoteSources.appFile(RemoteSources.OWNER_A, RemoteSources.REPO_A, "decision.json")) { b ->
        val o = runCatching { JSONObject(b) }.getOrNull()
        "${o?.optJSONArray("cards")?.length() ?: 0}张卡"
    }
    check("市况 api/market", RemoteSources.apiMarket()) { b ->
        val o = runCatching { JSONObject(b) }.getOrNull()
        if (o?.optJSONObject("market") != null) "有市况" else "无 market 字段"
    }
    check("题材报告 theme_report", RemoteSources.themeReport()) { b ->
        val o = runCatching { JSONObject(b) }.getOrNull()
        "${o?.optJSONArray("themes")?.length() ?: 0}个题材"
    }
    check("自选实时行情(腾讯)", RemoteSources.quoteProbe()) { b ->
        if (b.contains("~")) "OK 报价可解析" else "响应异常"
    }
    check("7x24快讯(东财)", RemoteSources.news724Probe()) { b ->
        val n = runCatching { JSONObject(b).optJSONObject("data")?.optJSONArray("list")?.length() }.getOrNull()
        if (n != null) "${n}条实时" else "解析失败"
    }
    return out
}
