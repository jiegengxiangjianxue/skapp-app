package com.skapp.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skapp.app.ui.screens.UnifiedAskScreen
import com.skapp.app.ui.screens.BoardScreen
import com.skapp.app.ui.screens.DecisionScreen
import com.skapp.app.ui.screens.MarketScreen
import com.skapp.app.ui.screens.NewsScreen
import com.skapp.app.ui.screens.EnginesScreen
import com.skapp.app.ui.screens.PicksScreen
import com.skapp.app.ui.screens.PoolsScreen
import com.skapp.app.ui.screens.SectorsScreen
import com.skapp.app.ui.screens.AiScreen
import com.skapp.app.ui.screens.StockDetailSheet
import com.skapp.app.ui.screens.SummaryScreen

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: SkappViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    // 与网页版 tab 对齐：实时总结 / 推股 / 决策 / 看板 / 多池 / 消息 / 板块 / 提问
    // 合并后 5 个：看板(自选行情+全球+指数+涨跌榜+实时总结+决策卡)
    //           推股(多池+本地实时选股+云端推股)
    //           消息(快讯/主线, 标题下实时算法)
    //           提问(本地规则引擎) / AI(GitCode 复盘+问答)
    val tabs = listOf(
        Tab("看板", Icons.Filled.Dashboard),
        Tab("推股", Icons.Filled.TrendingUp),
        Tab("消息", Icons.Filled.Article),
        Tab("引擎", Icons.Filled.Science),
        Tab("问答", Icons.Filled.QuestionAnswer),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> BoardScreen(vm, state)
                1 -> PicksScreen(vm, state)
                2 -> NewsScreen(vm, state)
                3 -> EnginesScreen(vm, state)
                4 -> UnifiedAskScreen(vm, state)
            }
        }

        // 个股详情：底部抽屉（原 app 缺失的能力）
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
