package com.skapp.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
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
import com.skapp.app.ui.screens.AskScreen
import com.skapp.app.ui.screens.DecisionScreen
import com.skapp.app.ui.screens.MarketScreen
import com.skapp.app.ui.screens.PicksScreen
import com.skapp.app.ui.screens.StockDetailSheet

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MainScreen(vm: SkappViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    val tabs = listOf(
        Tab("行情", Icons.Filled.ShowChart),
        Tab("推股", Icons.Filled.TrendingUp),
        Tab("决策", Icons.Filled.Star),
        Tab("提问", Icons.Filled.QuestionAnswer),
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
                0 -> MarketScreen(vm, state)
                1 -> PicksScreen(vm, state)
                2 -> DecisionScreen(vm, state)
                3 -> AskScreen(vm, state)
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
