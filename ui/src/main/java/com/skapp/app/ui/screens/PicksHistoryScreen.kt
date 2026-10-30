package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.GitHubRepoWriter
import com.skapp.app.data.PicksArchiveQueue
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.components.SkTabBar
import com.skapp.app.ui.theme.SkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 推股历史：读仓库「开盘推股历史数据/」，按日期倒序展示每一轮推送，并给出区间汇总。
 *
 * 数据只来自远端归档。断网或未配 PAT 时给空态，不拿当前名单冒充历史——
 * 这一页的价值就是「当时推了什么」，造假比留空更糟。
 *
 * 归档是一天一个文件，季度档要读几十个文件，所以并发拉取；读取上限 [MAX_FILES]
 * 防止一次打开打满 GitHub 限流，汇总栏会显示实际覆盖的交易日数。
 */

private const val MAX_FILES = 60

private data class HistoryPick(val code: String, val name: String?, val score: Double?)

private data class HistoryRound(
    val time: String,
    val session: String,
    val status: String,
    val picks: List<HistoryPick>,
)

private data class HistoryDay(val date: String, val rounds: List<HistoryRound>)

private val SESSION_LABELS = mapOf(
    "premarket" to "盘前",
    "morning" to "早盘",
    "midday" to "午盘",
    "intraday" to "盘中",
)

@Composable
fun PicksHistoryScreen(onBack: () -> Unit) {
    var spanIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf<List<HistoryDay>>(emptyList()) }
    val span = listOf(7, 30, 60)[spanIndex]

    LaunchedEffect(spanIndex) {
        loading = true
        error = null
        runCatching { loadHistory(span) }
            .onSuccess { days = it }
            .onFailure { error = it.message ?: "读取失败" }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        item(key = "bar") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← 返回",
                    modifier = Modifier.clickable { onBack() }.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "推股历史",
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "span") {
            SkTabBar(
                tabs = listOf("近 7 天", "近 30 天", "近 60 天"),
                selected = spanIndex,
                onSelect = { spanIndex = it },
            )
        }

        if (loading) {
            item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("正在读取归档…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }
        error?.let {
            item(key = "error") {
                SkCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("读取失败", fontWeight = FontWeight.SemiBold, color = SkColors.warn)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    }
                }
            }
            return@LazyColumn
        }
        if (!GitHubRepoWriter.isConfigured()) {
            item(key = "nopat") {
                SkCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("未配置仓库访问", fontWeight = FontWeight.SemiBold)
                        Text(
                            "推股历史归档在 GitHub 仓库，需要先在设置里填 PAT。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SkColors.textSecondary,
                        )
                    }
                }
            }
            return@LazyColumn
        }
        if (days.isEmpty()) {
            item(key = "empty") {
                SkCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("暂无历史归档", fontWeight = FontWeight.SemiBold)
                        Text(
                            "归档在本地队列 flush 后才会出现在这里；刚算完的名单要等下一次 flush。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SkColors.textSecondary,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item(key = "summary") { SummaryCard(days, span) }
        days.forEach { day ->
            item(key = day.date) { DayCard(day) }
        }
    }
}

/** 区间汇总：这一栏就是「周/月/季报表」的正文，数字口径都写在标签上。 */
@Composable
private fun SummaryCard(days: List<HistoryDay>, span: Int) {
    val rounds = days.flatMap { it.rounds }
    val codes = rounds.flatMap { r -> r.picks.map { it.code } }.distinct()
    val bySession = rounds.groupBy { it.session }.mapValues { it.value.size }
    val topCodes = rounds.flatMap { r -> r.picks.map { it.code to (it.name ?: it.code) } }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(8)

    SkCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("近 $span 天汇总", fontWeight = FontWeight.SemiBold)
            Text(
                "覆盖 ${days.size} 个归档日 · ${rounds.size} 轮推送 · ${codes.size} 只票（去重）",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            Text(
                "时段分布：" + if (bySession.isEmpty()) "无" else bySession.entries.joinToString(" / ") {
                    "${SESSION_LABELS[it.key] ?: it.key} ${it.value} 轮"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            if (topCodes.isNotEmpty()) {
                Text(
                    "上榜次数最多：" + topCodes.joinToString("、") { "${it.key.second} ${it.value} 次" },
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DayCard(day: HistoryDay) {
    SkCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(day.date, fontWeight = FontWeight.SemiBold)
            day.rounds.take(6).forEach { round ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "${round.time}　${SESSION_LABELS[round.session] ?: round.session}" +
                            "　${round.picks.size} 只",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val line = round.picks.joinToString("、") { p ->
                        p.name?.takeIf { it.isNotBlank() } ?: p.code
                    }
                    Text(
                        line.ifBlank { "名单为空（${round.status}）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
            }
            if (day.rounds.size > 6) {
                Text(
                    "另有 ${day.rounds.size - 6} 轮未展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
        }
    }
}

private suspend fun loadHistory(limit: Int): List<HistoryDay> = withContext(Dispatchers.IO) {
    if (!GitHubRepoWriter.isConfigured()) return@withContext emptyList()
    val names = GitHubRepoWriter.listDir(PicksArchiveQueue.PUSH_DB_DIR)
        .filter { it.endsWith(".json") }
        .sortedDescending()
        .take(minOf(limit, MAX_FILES))
    coroutineScope {
        names.map { name ->
            async {
                runCatching {
                    val body = GitHubRepoWriter.readFile("${PicksArchiveQueue.PUSH_DB_DIR}/$name")?.body
                        ?: return@async null
                    HistoryDay(name.removeSuffix(".json"), parseRounds(body))
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }
}

private fun parseRounds(body: String): List<HistoryRound> {
    val arr = runCatching { JSONObject(body).optJSONArray("rounds") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val r = arr.optJSONObject(i) ?: return@mapNotNull null
        val ts = r.optString("ts")
        val picks = r.optJSONArray("picks")?.let { pa ->
            (0 until pa.length()).mapNotNull { j ->
                val p = pa.optJSONObject(j) ?: return@mapNotNull null
                val code = p.optString("code")
                if (code.isBlank()) null else HistoryPick(
                    code = code,
                    name = p.optString("name").takeIf { it.isNotBlank() },
                    score = if (p.has("score")) p.optDouble("score") else null,
                )
            }
        }.orEmpty()
        HistoryRound(
            time = if (ts.length >= 16) ts.substring(11, 16) else ts,
            session = r.optString("session").ifBlank { "intraday" },
            status = r.optString("status"),
            picks = picks,
        )
    }.sortedByDescending { it.time }
}
