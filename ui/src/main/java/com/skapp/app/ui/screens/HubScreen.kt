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
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlaylistAddCheck
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
import com.skapp.app.ui.theme.SkColors

/**
 * 唯一模块入口，按**决策链三段**组织：看盘 → 选股 → 复盘。
 *
 * 之前是「市场/决策/资讯/AI/系统」五组平铺，用户要先知道「看板块该点哪个」才能找到入口；
 * 分段后每一段对应决策链的一步，段标题就是这一步要回答的问题。
 * 业务页面仍由 MainScreen 按旧编号渲染，编号不动——改名会牵动 12 处跳转。
 */
@Composable
fun HubScreen(vm: SkappViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    val groups = listOf(
        ModuleGroup(
            title = "① 看盘",
            subtitle = "今天市场是什么状态：指数、板块、题材",
            modules = listOf(
                ModuleSpec("行情看板", "指数与市场总览", "看板", 0, Icons.Filled.Dashboard),
                ModuleSpec("全球与自选", "A 股指数与自选行情", "行情", 5, Icons.Filled.ShowChart),
                ModuleSpec("消息与题材", "按题材展开消息，未匹配内容归入未知", "消息与题材", 3, Icons.Filled.Newspaper),
            ),
        ),
        ModuleGroup(
            title = "② 选股",
            subtitle = "从哪几只里选：候选池、中枢刷新与结论",
            modules = listOf(
                ModuleSpec("盘前推股名单", "上一交易日收盘口径，当日冻结", "盘前推股", 8, Icons.Filled.PlaylistAddCheck),
                ModuleSpec("实时推股名单", "当日实时行情口径，随行情刷新", "实时推股", 9, Icons.Filled.Bolt),
                ModuleSpec("多池选股", "推荐、风险、周期及策略池", "多池", 7, Icons.Filled.Layers),
                ModuleSpec("实时总结", "刷新反馈、中枢引擎说明与 AI 二次总结", "实时总结", 1, Icons.Filled.AutoGraph),
                ModuleSpec("决策台", "中枢结论与执行纪律", "决策", 6, Icons.Filled.QueryStats),
            ),
        ),
        ModuleGroup(
            title = "③ 复盘",
            subtitle = "选出来之后怎么样：AI 复盘与数据健康",
            modules = listOf(
                ModuleSpec("AI 总结", "GitCode/GitHub 双源、个股复盘与市场总结", "AI", 11, Icons.Filled.Psychology),
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
        // 全局状态带放在最上面：规格版本、数据时点、降级项数三个数决定「下面这些分数能不能信」。
        item(key = "status_band") { StatusBand(state) }
        item(key = "hub_header") {
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
        // 分组必须带 key：无 key 的 items 在重排时会按位置复用节点，展开态与滚动位置会串到别的组。
        items(groups, key = { it.title }) { group ->
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

/**
 * 全局状态带：规格版本 / 数据时点 / 降级项数。
 *
 * 三个数各管一件事——版本不一致说明算法可能不是你以为的那套，时点不是今天说明数据是旧的，
 * 降级项 >0 说明有引擎没跑起来（分数会系统性偏低但不是 0）。三者任一异常都必须一眼可见，
 * 否则用户看到的是一个「看起来正常」的分数。
 */
@Composable
fun StatusBand(state: UiState, modifier: Modifier = Modifier) {
    val degraded = state.chainDegraded.size
    val bad = state.engineSpecDrift || degraded > 0
    GlassPill(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "规格 V${state.engineSpecVersion ?: "?"} · ${state.chainStatus}" +
                        if (state.engineSpecDrift) " · 漂移" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "数据时点 ${state.screenedAt?.replace("T", " ")?.take(19) ?: "尚未计算"} · 降级 $degraded 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkColors.textSecondary,
                )
            }
            if (bad) {
                Text(
                    text = "降级",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
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
        13 -> state.canonicalScore?.let { "规格 " + String.format("%.1f", it) } ?: "未计算"
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
