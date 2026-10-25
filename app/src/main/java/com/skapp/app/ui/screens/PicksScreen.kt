package com.skapp.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun PicksScreen(vm: SkappViewModel, state: UiState) {
    LaunchedEffect(Unit) {
        vm.loadSummary()
        vm.loadPicks()
        vm.loadNewsCalcs()
        vm.loadIndices()
        vm.runScreen("score")
    }
    RealtimeSummaryScreen(vm, state)
}
