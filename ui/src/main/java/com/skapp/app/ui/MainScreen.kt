package com.skapp.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skapp.app.ui.screens.DashboardScreen
import com.skapp.app.ui.screens.DiagScreen
import com.skapp.app.ui.screens.PremarketPicksScreen
import com.skapp.app.ui.screens.RealtimePicksScreen
import com.skapp.app.ui.screens.HubScreen
import com.skapp.app.ui.screens.MarketScreen
import com.skapp.app.ui.screens.NewsScreen
import com.skapp.app.ui.screens.RealtimeSummaryScreen
import com.skapp.app.ui.screens.StockDetailSheet
import com.skapp.app.ui.screens.ThemeScreen
import com.skapp.app.ui.screens.UnifiedAskScreen

/** 模块编号与 HubScreen 一致；保留旧编号，避免业务页面路由变化。 */
private val MODULE_NAMES = mapOf(
    0 to "看板", 1 to "实时总结", 2 to "消息与题材", 3 to "消息与题材", 4 to "全部",
    5 to "行情", 8 to "盘前推股", 9 to "实时推股", 11 to "AI 总结", 12 to "自检",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: SkappViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var module by remember { mutableStateOf(4) }

    BackHandler(enabled = state.detail != null || state.detailLoading) {
        vm.closeDetail()
    }
    BackHandler(enabled = state.detail == null && !state.detailLoading && module != 4) {
        module = 4
    }

    val navigateToHub = {
        if (module != 4) module = 4
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(module, state.detail?.code, state.detailLoading) {
                var edgeStartX = Float.MAX_VALUE
                var edgeDragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        edgeStartX = offset.x
                        edgeDragX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (edgeStartX <= 36.dp.toPx() && dragAmount > 0f) {
                            edgeDragX += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (state.detail == null && !state.detailLoading && module != 4 && edgeStartX <= 36.dp.toPx() && edgeDragX >= 96.dp.toPx()) {
                            module = 4
                        }
                    },
                    onDragCancel = {
                        edgeDragX = 0f
                    },
                )
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = MODULE_NAMES[module] ?: "全部",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                if (module != 4) {
                    Text(
                        text = "全部功能",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = navigateToHub)
                            .padding(10.dp),
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (module) {
                    0 -> DashboardScreen(vm, state)
                    1 -> RealtimeSummaryScreen(vm, state)
                    2 -> NewsScreen(vm, state)
                    3 -> NewsScreen(vm, state)
                    4 -> HubScreen(vm, state) { module = it }
                    5 -> MarketScreen(vm, state)
                    8 -> PremarketPicksScreen(vm, state)
                    9 -> RealtimePicksScreen(vm, state)
                    11 -> UnifiedAskScreen(vm, state)
                    12 -> DiagScreen(vm, state)
                }
            }
        }

        if (state.detail != null || state.detailLoading) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                StockDetailSheet(vm, state)
            }
        }
    }
}
