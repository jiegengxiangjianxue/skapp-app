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

/** 消息入口：新闻 / 题材，顶栏切换。子页下标受控（见 MainScreen）。 */
@Composable
fun NewsHubScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        SkTabBar(
            tabs = listOf("新闻", "题材"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> NewsScreen(vm, state)
                else -> ThemeScreen(vm, state)
            }
        }
    }
}
