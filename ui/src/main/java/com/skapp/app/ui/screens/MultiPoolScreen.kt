package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skapp.app.data.Code
import com.skapp.app.data.SiteBoardClient
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.state.WorkbenchSummary
import com.skapp.app.ui.theme.SkColors

@Composable
fun MultiPoolScreen(vm: SkappViewModel, state: UiState) {
    LaunchedEffect(Unit) { vm.loadPools(force = false) }
    MultiPoolContent(state.pools, state.poolsLoading, state.dataGaps["多池"],
        onRefresh = { vm.loadPools(force = true) }, onStock = { vm.openDetail(it) })
}

@Composable
internal fun MultiPoolContent(pools: List<SiteBoardClient.Pool>, loading: Boolean, error: String?,
                              onRefresh: () -> Unit, onStock: (String) -> Unit) {
    val now = System.currentTimeMillis()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("多池观察", style = MaterialTheme.typography.titleLarge)
                if (loading) CircularProgressIndicator()
                else TextButton(onClick = onRefresh) { Text("刷新池子") }
            }
            Text("来源：云端池子归档；不是本地实时推荐。补价不改变池的原始日期。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && pools.isEmpty()) Text("暂无池数据，可刷新重试。")
        }
        items(pools.distinctBy { it.id }, key = { it.id }) { pool ->
            var expanded by remember(pool.id) { mutableStateOf(false) }
            val entries = pool.items.mapNotNull { item -> Code.normalize(item.code)?.let { it to item } }.distinctBy { it.first }
            SkCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pool.name} · ${entries.size} 只", style = MaterialTheme.typography.titleMedium)
                    Text("归档 ${pool.ts ?: "时点未知"} · ${if (WorkbenchSummary.fresh(pool.ts, now, 86_400_000L)) "24小时内" else "过期或时点未核"}",
                        style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary)
                    pool.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (pool.status != "ok") Text(pool.reason ?: "该池证据不足", color = MaterialTheme.colorScheme.error)
                    if (entries.isEmpty()) Text(pool.reason ?: "该池暂无成分，未补入其他来源名单。")
                    (if (expanded) entries else entries.take(5)).forEach { (code, stock) ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onStock(code) }.padding(vertical = 8.dp)) {
                            Text("${stock.name ?: code} · $code", style = MaterialTheme.typography.bodyMedium)
                            Text(if (!Code.isMainBoard(code)) "非主板，仅观察" else if (stock.extra["is_limit_up"] == true) "涨停，仅归因观察" else "归档池成员；当前报价与证据见个股详情",
                                style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                        }
                    }
                    if (entries.size > 5) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部") }
                }
            }
        }
    }
}
