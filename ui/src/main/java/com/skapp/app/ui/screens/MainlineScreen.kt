package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkTabBar

/** 题材催化、板块结构与量价动量共用一个工作入口，观察不等于买入。 */
@Composable
fun MainlineScreen(
    vm: SkappViewModel,
    state: UiState,
    selected: Int = 0,
    onSelect: (Int) -> Unit = {},
) {
    LaunchedEffect(selected) {
        when (selected) {
            0 -> vm.ensureTheme()
            1 -> vm.ensureSectors()
            else -> if (state.momentum == null) vm.loadMomentum()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("题材与主线 · 观察工作台", Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleMedium)
        Text("催化 → 行业概念 → 强弱验证；题材归档不替代实时买点。", Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall)
        SkTabBar(listOf("催化题材", "行业概念", "强弱动量"), selected, onSelect,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        val label = when (selected) { 0 -> "题材"; 1 -> "板块"; else -> "板块动量" }
        state.dataGaps[label]?.let { error ->
            Text(error, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (label !in state.loadingSections) TextButton(onClick = {
                when (selected) {
                    0 -> vm.ensureTheme(force = true)
                    1 -> vm.ensureSectors(force = true)
                    else -> vm.loadMomentum(force = true)
                }
            }) { Text(when (selected) { 0 -> "重试催化题材"; 1 -> "重试行业概念"; else -> "重试强弱动量" }) }
        }
        Box(Modifier.weight(1f).testTag("mainline-$selected")) {
            when (selected) {
                0 -> ThemeScreen(vm, state)
                1 -> SectorsScreen(vm, state)
                else -> MomentumScreen(vm, state)
            }
        }
    }
}
