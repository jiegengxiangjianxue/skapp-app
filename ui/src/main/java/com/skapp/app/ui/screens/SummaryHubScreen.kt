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

/** 总结入口：实时 / 决策，顶栏切换。子页下标受控（见 MainScreen）。
 *
 * 原来的「AI」子页已删：它与「凭据」入口重复（两处都是 AI 配置入口），
 * 且没有独立数据，问答能力由个股详情内的 AI 复盘与决策页承担。
 */
@Composable
fun SummaryHubScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        SkTabBar(
            tabs = listOf("实时", "决策"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> RealtimeSummaryScreen(vm, state)
                else -> DecisionScreen(vm, state)
            }
        }
    }
}
