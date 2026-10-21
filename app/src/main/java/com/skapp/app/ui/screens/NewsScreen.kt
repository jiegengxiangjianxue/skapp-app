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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 消息 Tab：顶栏切换「快讯 / 情绪 / 主线 / 影响 / 传导」。
 */
@Composable
fun NewsScreen(vm: SkappViewModel, state: UiState) {
    val n = state.news
    var tab by remember { mutableStateOf(0) }

    val tabs = listOf(
        "快讯" to n.items,
        "情绪" to emptyList<Map<String, Any?>>(),
        "主线" to n.themes,
        "影响" to n.impacts,
        "传导" to n.transmission,
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "消息面",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            tabs.forEachIndexed { i, (name, list) ->
                NewsTab(name, if (i == 1) (n.digest["total"] as? Int ?: 0) else list.size, tab == i) { tab = i }
            }
        }
        HorizontalDivider()

        Text(
            n.ts ?: "时点暂无",
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        when (tab) {
            1 -> SentimentPanel(n)
            else -> {
                val current = tabs[tab].second
                if (current.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("暂无数据：该分类上游未产出。", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(current, key = { "n" + it.hashCode() }) { m ->
                            when (tab) {
                                3 -> ImpactCard(m, vm)
                                else -> GenericCard(m)
                            }
                        }
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

/** 情绪面板：市场情绪摘要 + 分类计数 */
@Composable
private fun SentimentPanel(n: com.skapp.app.data.NewsSnapshot) {
    val dg = n.digest
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dg.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("市场情绪", fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append("情绪：${dg["global_mood"] ?: "暂无数据"}")
                                dg["global_score"]?.let { append("（评分 $it）") }
                                dg["total"]?.let { append("　共 $it 条") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (dg["stale"] == true) {
                            Text(
                                "注意：上游标记 stale，数据可能过期",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD29922),
                            )
                        }
                        val cc = dg["cat_counts"]
                        if (cc is Map<*, *>) {
                            Spacer(Modifier.height(6.dp))
                            cc.entries.forEach { (k, v) ->
                                Text(
                                    "$k：$v",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                )
                            }
                        }
                    }
                }
            }
        }
        // 分类下的新闻（categories 是 dict: 分类 -> 列表）
        val cats = (dg["categories"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        cats.forEach { (cat, items) ->
            item {
                Text(
                    cat.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (items is List<*>) {
                items(items.take(10), key = { "c" + it.hashCode() }) { m ->
                    val mm = m as? Map<*, *> ?: return@items
                    Card(colors = CardDefaults.cardColors()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                (mm["title"] ?: "暂无标题").toString(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val senti = mm["sentiment"]
                            val src = mm["src"]
                            if (senti != null || src != null) {
                                Text(
                                    listOfNotNull(senti?.toString(), src?.toString()).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = sentimentColor(senti?.toString()),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (cats.isEmpty() && dg.isEmpty()) {
            item { Text("暂无数据：消息面引擎产出不可达。", color = Color.Gray) }
        }
    }
}

@Composable
private fun GenericCard(m: Map<String, Any?>) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (m["title"] ?: m["name"] ?: m["theme"] ?: "暂无标题").toString(),
                fontWeight = FontWeight.Medium,
            )
            val sub = listOfNotNull(
                m["source_name"]?.toString() ?: m["source"]?.toString(),
                m["updated"]?.toString() ?: m["ts"]?.toString(),
            ).filter { it.isNotEmpty() && it != "null" }
            if (sub.isNotEmpty()) {
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            val stocks = m["stocks"]
            if (stocks is List<*>) {
                Text(
                    stocks.take(6).joinToString("、") { s ->
                        (s as? Map<*, *>)?.get("name")?.toString() ?: s.toString()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                )
            }
        }
    }
}

@Composable
private fun ImpactCard(m: Map<String, Any?>, vm: SkappViewModel) {
    val code = (m["code"] ?: "").toString()
    val name = (m["name"] ?: "").toString().ifEmpty { code }
    val dir = m["direction"]
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (code.isNotEmpty()) Modifier.clickable { vm.openDetail(code) } else Modifier),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium)
                if (code.isNotEmpty()) {
                    Text(code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            if (dir != null) {
                Text(
                    dir.toString(),
                    color = sentimentColor(dir.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun sentimentColor(v: String?): Color = when (v) {
    "positive", "利好", "bull" -> Color(0xFFF85149)
    "negative", "利空", "bear" -> Color(0xFF3FB950)
    else -> Color.Gray
}
