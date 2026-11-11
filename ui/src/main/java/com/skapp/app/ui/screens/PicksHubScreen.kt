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

@Composable
fun PicksHubScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        SkTabBar(
            tabs = listOf("实时", "早盘", "午盘", "历史", "多池"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> RealtimePicksScreen(vm, state)
                1 -> MorningPicksScreen(vm, state)
                2 -> MiddayPicksScreen(vm, state)
                3 -> PicksHistoryScreen(onBack = { onSelect(0) })
                else -> MultiPoolScreen(vm, state)
            }
        }
    }
}
