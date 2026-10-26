package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun PoolsScreen(vm: SkappViewModel, state: UiState) {
    var tab by remember { mutableStateOf(0) }
    val pools = remember(state.pools) {
        val merged = state.pools
            .filterNot { it.id in setOf("composite", "moneyflow") }
            .map { pool ->
                when (pool.id) {
                    "ai" -> pool.copy(id = "recommend", name = "推荐池", subtitle = "推荐池 · AI 合并")
                    "ban" -> pool.copy(id = "limitup", name = "涨停池", subtitle = "涨停池 · 打板合并")
                    else -> pool
                }
            }
        merged.groupBy { it.id }.values.map { group ->
            val first = group.first()
            if (group.size == 1) first else first.copy(
                items = group.flatMap { it.items }.distinctBy { normalizedPoolKey(it) },
                subtitle = listOfNotNull(first.subtitle, "合并 ${group.size} 个来源").joinToString(" · ")
            )
        }
    }
    val current = pools.getOrNull(tab.coerceAtMost((pools.size - 1).coerceAtLeast(0)))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("多池选股", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.poolsLoading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    "实时重算",
                    Modifier.clickable {
                        vm.loadPools()
                        vm.runScreen("all", true)
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "刷新",
                    Modifier.clickable { vm.loadPools() }.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        }

        item {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            pools.forEachIndexed { i, pool ->
                PoolChip(pool, tab == i) { tab = i }
            }
        }
        HorizontalDivider()
        }

        if (pools.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.poolsLoading) CircularProgressIndicator()
                    Text(
                        if (state.poolsLoading) "正在拉取多池数据…" else "暂无数据：多池源不可达。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        val pool = current ?: return@LazyColumn
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pool.subtitle ?: pool.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${pool.items.size} 条", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("状态：${statusText(pool.status, pool.items.isEmpty())}", style = MaterialTheme.typography.labelSmall,
                    color = statusColor(pool.status))
                Text("时间：${pool.ts ?: "暂无数据"}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pool.kpis.isNotEmpty()) {
                Text(pool.kpis.entries.joinToString("  ") { "${kpiLabel(it.key)} ${it.value ?: "暂无数据"}" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        }

        if (pool.status == "insufficient") {
            item {
                Text("暂无数据：${pool.reason ?: "上游不可达"}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            return@LazyColumn
        }
        if (pool.items.isEmpty()) {
            item {
                Text("暂无数据：该池没有可展示条目。", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            return@LazyColumn
        }

        val visibleItems = when (pool.id) {
            "good", "recommend", "cycle", "low" -> pool.items.filter(::isMainBoardCandidate)
            else -> pool.items
        }.distinctBy { normalizedPoolKey(it) }
        if (visibleItems.isEmpty()) {
            item {
                Text("暂无数据：该池没有符合条件的条目。", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pool.id == "sector_stocks") {
            visibleItems.groupBy { it.extra["board"]?.toString()?.takeIf { name -> name.isNotBlank() } ?: "未分类板块" }
                .toSortedMap()
                .forEach { (board, boardItems) ->
                    item(key = "sector_header_$board") {
                        Text(
                            "$board · 算法候选 ${boardItems.size} 只",
                            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(boardItems, key = { pool.id + "_" + board + "_" + normalizedPoolKey(it) }) { item ->
                        PoolRow(item) { if (isStockCode(item.code)) vm.openDetail(item.code) }
                    }
                }
        } else {
            items(visibleItems, key = { pool.id + "_" + normalizedPoolKey(it) }) { item ->
                PoolRow(item) { if (isStockCode(item.code)) vm.openDetail(item.code) }
            }
        }
    }
}

@Composable
private fun PoolChip(pool: SiteBoardClient.Pool, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(pool.name, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.width(4.dp))
            Text("${pool.items.size}", color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            if (pool.status == "insufficient") Text(" · 暂无", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PoolRow(item: SiteBoardClient.PoolItem, onClick: () -> Unit) {
    val clickable = isStockCode(item.code)
    Card(
        modifier = Modifier.fillMaxWidth().then(if (clickable) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name ?: "暂无数据", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(if (clickable) item.code else "暂无代码", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                item.score?.let { Text("分 ${formatNumber(it)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary) }
                item.pctChg?.let { Text("${if (it >= 0) "+" else ""}${formatNumber(it)}%", style = MaterialTheme.typography.labelSmall,
                    color = if (it >= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
            }
        }
        val extras = item.extra.entries.filter { (key, value) -> key !in HIDDEN_FIELDS && value != null }
            .take(3)
        if (extras.isNotEmpty()) {
            Text(extras.joinToString("  ") { (key, value) -> "${fieldLabel(key)} ${value ?: "暂无数据"}" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp))
        }
    }
}

private val HIDDEN_FIELDS = setOf("code", "name", "symbol", "s", "n", "stock_name", "board_name", "sector_code",
    "title", "score", "composite", "strength", "total_score", "price", "close", "p", "pctChg", "chg_pct", "pct", "chg", "ret_pct")

private fun normalizedPoolKey(item: SiteBoardClient.PoolItem): String {
    val code = item.code.trim().lowercase(java.util.Locale.ROOT)
        .removePrefix("sh").removePrefix("sz").removePrefix("bj")
    return if (code.matches(Regex("\\d{6}"))) "code:$code"
    else "name:${item.name.orEmpty().trim().lowercase(java.util.Locale.ROOT)}"
}

private fun isStockCode(code: String): Boolean = code.matches(Regex("(?i)^(sh|sz|bj)?\\d{6}$"))

private fun isMainBoardCandidate(item: SiteBoardClient.PoolItem): Boolean {
    val raw = item.code.trim().lowercase(java.util.Locale.ROOT)
    val code = raw.removePrefix("sh").removePrefix("sz").removePrefix("bj")
    val mainBoard = code.startsWith("600") || code.startsWith("601") || code.startsWith("603") ||
        code.startsWith("605") || code.startsWith("000") || code.startsWith("001") || code.startsWith("002")
    return mainBoard && item.pctChg != null && item.pctChg < 9.5
}

private fun statusText(status: String, empty: Boolean): String = when {
    status == "insufficient" -> "数据不足"
    empty -> "暂无数据"
    else -> "可用"
}

@Composable
private fun statusColor(status: String) = if (status == "insufficient") MaterialTheme.colorScheme.error
else MaterialTheme.colorScheme.secondary

private fun kpiLabel(key: String): String = when (key) {
    "eligible", "推荐" -> "推荐"
    "watch", "观察" -> "观察"
    "reject", "回避" -> "回避"
    "strong_buy", "强势" -> "强势"
    "total_filtered", "过滤" -> "过滤"
    "n_scanned", "扫描" -> "扫描"
    "n_zt", "涨停" -> "涨停"
    "n_analyzed", "分析" -> "分析"
    "n_auction_strong", "强竞价" -> "强竞价"
    else -> key
}

private fun fieldLabel(key: String): String = when (key) {
    "lianban" -> "连板"
    "lianban_label" -> "连板标签"
    "seal_time", "auction_time" -> "封板时间"
    "seal_label" -> "封板时段"
    "industry" -> "行业"
    "verdict" -> "判断"
    "reasons" -> "依据"
    "leader" -> "龙头"
    "leader_pct" -> "龙头涨幅"
    "avg_chg_1d" -> "日涨幅"
    "chg_5d" -> "5日涨幅"
    "chg_20d" -> "20日涨幅"
    "buy_sell_imbalance" -> "买卖失衡"
    "auction_strength" -> "竞价强度"
    "source_name" -> "来源"
    "rank" -> "排名"
    "updated" -> "更新时间"
    "market" -> "市场"
    "theme" -> "题材"
    "category" -> "类别"
    "source" -> "来源"
    "reason" -> "原因"
    "sentiment" -> "情绪"
    "pctChg" -> "涨跌幅"
    "price" -> "现价"
    "score" -> "综合评分"
    else -> key
}

private fun formatNumber(value: Double): String = String.format("%.2f", value)
