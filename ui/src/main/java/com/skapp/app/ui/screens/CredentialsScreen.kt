package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkTabBar

/**
 * 凭据入口：AI 双源凭据 / 自检。
 *
 * 题材页只留在「消息」入口，不在这里重复挂一份——同一页两处入口会让深链与埋点无法区分来源。
 * 子页下标受控（见 MainScreen）。
 */
@Composable
fun CredentialsScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        SkTabBar(
            tabs = listOf("凭据", "自检"),
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { TokenConfigCard(vm, state) }
                else -> DiagScreen(vm, state)
            }
        }
    }
}
