package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkTabBar
import com.skapp.app.ui.theme.SkColors

/**
 * 看板入口：概览 / 行情，顶栏切换。
 *
 * 子页下标由 [com.skapp.app.ui.MainScreen] 持有（`selected`/`onSelect` 受控），
 * 不在本容器内 `remember`：一级入口切走后容器会离开组合，子页位置会被丢掉，
 * 且默认子页的 LaunchedEffect 每次回来都会重跑。
 */
@Composable
fun DashboardHubScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        ProvenanceStrip(state)
        SkTabBar(
            tabs = listOf("概览", "行情"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> DashboardScreen(vm, state)
                else -> MarketScreen(vm, state)
            }
        }
    }
}

/**
 * 规格与链路状态带。
 *
 * 旧 `HubScreen` 曾是 `engineSpecVersion`/`chainDegraded`/`chainStatus`/`screenedAt` 的唯一渲染点，
 * 它退役后这些字段一度没有界面消费者——数据红线要求输出前能标注来源、时点与降级，
 * 所以在这里补一条紧凑状态带（只读现成字段，不新增计算）。
 */
@Composable
private fun ProvenanceStrip(state: UiState) {
    val parts = buildList {
        state.engineSpecVersion?.let { add("规格 V$it") }
        if (state.chainStatus != "ok") add("链路 ${state.chainStatus}")
        if (state.chainDegraded.isNotEmpty()) add("降级 ${state.chainDegraded.size} 项")
        state.screenedAt?.let { add("名单 $it") }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = SkColors.textSecondary,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
    )
}
