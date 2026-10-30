package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkTabBar

/**
 * 选股入口：盘前 / 早盘 / 午盘 / 实时 / 板块，顶栏切换。
 *
 * 盘前是 T-1 收盘口径；早盘与午盘是同一天按盘中时段生成的快照（口径同为当日实时）；
 * 实时跟随当前交易时段自动选早盘或午盘，非交易时段退回午盘存档。
 *
 * 不再单列「名单」子页：`PremarketPicksScreen` / `RealtimePicksScreen` 本身就是
 * `PicksListScreen` 按会话的包装（见 PicksListScreen.kt:61-71），单列等于同一份名单两个入口。
 * 子页下标由 [com.skapp.app.ui.MainScreen] 持有（受控），理由同 DashboardHubScreen。
 */
@Composable
fun PicksHubScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        SkTabBar(
            tabs = listOf("盘前", "早盘", "午盘", "实时", "板块"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> PremarketPicksScreen(vm, state)
                1 -> MorningPicksScreen(vm, state)
                2 -> MiddayPicksScreen(vm, state)
                3 -> RealtimePicksScreen(vm, state)
                else -> SectorsScreen(vm, state)
            }
        }
    }
}
