package com.skapp.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skapp.app.ui.components.SkTabBar
import com.skapp.app.ui.screens.CredentialsScreen
import com.skapp.app.ui.screens.DashboardHubScreen
import com.skapp.app.ui.screens.NewsHubScreen
import com.skapp.app.ui.screens.PicksHubScreen
import com.skapp.app.ui.screens.StockDetailSheet
import com.skapp.app.ui.screens.SummaryHubScreen
import com.skapp.app.ui.theme.SkColors

/**
 * 五个一级入口。
 *
 * 之前是 `module: Int` 状态机 + Hub「全部功能」列表：入口靠数字编号散在
 * [MainScreen] 与 `HubScreen` 两处映射，孤儿屏与死分支随之积累
 * （`BoardScreen`/`SectorsScreen`/`DecisionScreen`/`ThemeScreen` 都定义过却没接进导航）。
 * 现在一级入口固定为五个，二级页在容器内用 [SkTabBar] 切换。
 *
 * ## 子页下标为什么放在这里
 *
 * 各入口容器切走后会离开组合，容器内 `remember` 的子页下标会被丢掉，回到该入口时
 * 落回第一页、默认子页的 `LaunchedEffect` 重跑一次（`RealtimeSummaryScreen` 会重跑本地
 * 深算，`DiagScreen` 会重跑全量探针）。所以子页下标由这里统一持有、传回容器。
 *
 * ## 返回顺序
 *
 * 覆盖层（个股详情）→ 当前入口的子页首项 → 看板。旧实现的判据是魔法数 `module != 4`，
 * 于是「看板 / 行情」这类同属看板的子页在返回时无处可去，直接退出 App。
 */
private enum class MainTab(val label: String, val subtitle: String) {
    DASHBOARD("看板", "今天市场是什么状态"),
    SUMMARY("总结", "结论、AI 复盘与决策"),
    NEWS("消息", "新闻与题材"),
    PICKS("选股", "候选名单、板块与中枢结论"),
    CREDENTIALS("凭据", "AI 双源配置与自检"),
}

@Composable
fun MainScreen(vm: SkappViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MainTab.DASHBOARD) }
    val subTabs = remember { mutableStateMapOf<MainTab, Int>() }
    val sub = subTabs[tab] ?: 0
    val detailOpen = state.detail != null || state.detailLoading

    fun selectSub(index: Int) {
        subTabs[tab] = index
    }

    BackHandler(enabled = detailOpen || sub > 0 || tab != MainTab.DASHBOARD) {
        when {
            detailOpen -> vm.closeDetail()
            sub > 0 -> selectSub(0)
            else -> tab = MainTab.DASHBOARD
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(tab, sub, detailOpen) {
                var edgeStartX = Float.MAX_VALUE
                var edgeDragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        edgeStartX = offset.x
                        edgeDragX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (edgeStartX <= 36.dp.toPx() && dragAmount > 0f) {
                            edgeDragX += dragAmount
                        }
                    },
                    onDragEnd = {
                        if (!detailOpen && edgeStartX <= 36.dp.toPx() && edgeDragX >= 96.dp.toPx()) {
                            when {
                                sub > 0 -> selectSub(0)
                                tab != MainTab.DASHBOARD -> tab = MainTab.DASHBOARD
                            }
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
                Column {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = tab.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            SkTabBar(
                tabs = MainTab.entries.map { it.label },
                selected = tab.ordinal,
                onSelect = { tab = MainTab.entries[it] },
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
                    MainTab.DASHBOARD -> DashboardHubScreen(vm, state, sub, ::selectSub)
                    MainTab.SUMMARY -> SummaryHubScreen(vm, state, sub, ::selectSub)
                    MainTab.NEWS -> NewsHubScreen(vm, state, sub, ::selectSub)
                    MainTab.PICKS -> PicksHubScreen(vm, state, sub, ::selectSub)
                    MainTab.CREDENTIALS -> CredentialsScreen(vm, state, sub, ::selectSub)
                }
            }
        }

        if (detailOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                StockDetailSheet(vm, state)
            }
        }
    }
}
