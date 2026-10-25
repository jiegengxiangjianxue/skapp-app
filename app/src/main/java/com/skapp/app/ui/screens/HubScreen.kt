package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.GlassCard
import com.skapp.app.ui.GlassPill
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/** 唯一模块入口。每个稳定模块 ID 只出现一次，业务页面仍由 MainScreen 按旧编号渲染。 */
@Composable
fun HubScreen(vm: SkappViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    val groups = listOf(
        ModuleGroup(
            title = "市场",
            subtitle = "指数、行情与板块脉搏",
            modules = listOf(
                ModuleSpec("行情看板", "指数与市场总览", "看板", 0, Icons.Filled.Dashboard),
                ModuleSpec("全球与自选", "A 股指数与自选行情", "行情", 5, Icons.Filled.ShowChart),
                ModuleSpec("板块地图", "行业、概念与成分股", "板块", 8, Icons.Filled.Category),
            ),
        ),
        ModuleGroup(
            title = "决策",
            subtitle = "候选、策略与复盘",
            modules = listOf(
                ModuleSpec("实时总结", "早盘/午盘/收盘 + 推股策略与引擎", "实时总结", 1, Icons.Filled.AutoGraph),
                ModuleSpec("多池选股", "好股票与策略池", "多池", 7, Icons.Filled.Layers),
            ),
        ),
        ModuleGroup(
            title = "资讯",
            subtitle = "新闻与题材变化",
            modules = listOf(
                ModuleSpec("消息与题材", "按题材展开消息，未匹配内容归入未知", "消息与题材", 3, Icons.Filled.Newspaper),
            ),
        ),
        ModuleGroup(
            title = "AI",
            subtitle = "双源模型总结",
            modules = listOf(
                ModuleSpec("AI 总结", "GitCode/GitHub 双源、个股复盘与市场总结", "AI", 11, Icons.Filled.Psychology),
            ),
        ),
        ModuleGroup(
            title = "系统",
            subtitle = "数据链路与健康状态",
            modules = listOf(
                ModuleSpec("数据源自检", "HTTP 状态与解析数量", "系统", 12, Icons.Filled.MonitorHeart),
            ),
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            GlassPill(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .semantics { contentDescription = "全部功能入口" },
            ) {
                Text(
                    text = "全部功能",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        items(groups) { group ->
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = group.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                group.modules.forEach { module ->
                    ModuleCard(module, state, onNavigate)
                }
            }
        }
    }
}

private data class ModuleGroup(
    val title: String,
    val subtitle: String,
    val modules: List<ModuleSpec>,
)

private data class ModuleSpec(
    val title: String,
    val description: String,
    val category: String,
    val index: Int,
    val icon: ImageVector,
)

@Composable
private fun ModuleCard(
    module: ModuleSpec,
    state: UiState,
    onNavigate: (Int) -> Unit,
) {
    val meta = when (module.index) {
        0 -> "${state.aShareIndices.size} 个指数"
        1 -> "${state.picks.signals.size + state.picks.values.size} 条候选"
        5 -> "${state.aShareIndices.size + state.globalIndices.size} 个指数"
        7 -> "${state.pools.size} 个选股池"
        8 -> "${state.sectors.industries.size + state.sectors.concepts.size} 个板块"
        else -> module.category
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(module.index) }
            .semantics { contentDescription = "打开${module.title}" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassPill(
                modifier = Modifier.size(46.dp),
            ) {
                Icon(
                    imageVector = module.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(11.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = module.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "进入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}
