package com.skapp.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skapp.app.ui.screens.AiScreen
import com.skapp.app.ui.screens.DashboardScreen
import com.skapp.app.ui.screens.DecisionScreen
import com.skapp.app.ui.screens.DiagScreen
import com.skapp.app.ui.screens.EnginesScreen
import com.skapp.app.ui.screens.HubScreen
import com.skapp.app.ui.screens.MarketScreen
import com.skapp.app.ui.screens.NewsScreen
import com.skapp.app.ui.screens.PicksScreen
import com.skapp.app.ui.screens.PoolsScreen
import com.skapp.app.ui.screens.SectorsScreen
import com.skapp.app.ui.screens.StockDetailSheet
import com.skapp.app.ui.screens.SummaryScreen
import com.skapp.app.ui.screens.ThemeScreen
import com.skapp.app.ui.screens.UnifiedAskScreen

private data class NavItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** 模块编号与 HubScreen 一致；顺序保留兼容旧调用 */
private val ALL = listOf(
    "看板", "推股", "题材", "消息", "全部", "行情",
    "决策", "多池", "板块", "引擎", "复盘", "AI复盘", "自检", "问答",
)

/**
 * 应用主导航 —— 网页版信息架构的移动版：
 * 高频模块在底栏，所有网页模块在「全部」总览中展开；
 * 所有模块都通过同一个 NavHost 切换，避免页面被底栏数量限制而消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: SkappViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var module by remember { mutableStateOf(0) }

    val tabs = listOf(
        NavItem("看板", Icons.Filled.Dashboard),
        NavItem("推股", Icons.Filled.TrendingUp),
        NavItem("题材", Icons.Filled.Category),
        NavItem("消息", Icons.Filled.Article),
        NavItem("全部", Icons.Filled.Apps),
    )
    val selectedTab = when (module) {
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> 3
        else -> 4
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { module = index },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // 所有模块统一页头：模块名 + 数据状态 + 返回总览入口
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        ALL.getOrElse(module) { "看板" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (module != 4) {
                        Text(
                            "全部功能",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .clickable { module = 4 },
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when (module) {
                        0 -> DashboardScreen(vm, state)
                        1 -> PicksScreen(vm, state)
                        2 -> ThemeScreen(vm, state)
                        3 -> NewsScreen(vm, state)
                        4 -> HubScreen(vm, state) { module = it }
                        5 -> MarketScreen(vm, state)
                        6 -> DecisionScreen(vm, state)
                        7 -> PoolsScreen(vm, state)
                        8 -> SectorsScreen(vm, state)
                        9 -> EnginesScreen(vm, state)
                        10 -> SummaryScreen(vm, state)
                        11 -> AiScreen(vm, state)
                        12 -> DiagScreen(vm, state)
                        13 -> UnifiedAskScreen(vm, state)
                    }
                }
            }
        }

        if (state.detail != null || state.detailLoading) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = vm::closeDetail,
                sheetState = sheetState,
            ) {
                StockDetailSheet(vm, state)
            }
        }
    }
}
