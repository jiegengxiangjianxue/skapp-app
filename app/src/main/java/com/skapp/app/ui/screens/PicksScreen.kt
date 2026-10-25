package com.skapp.app.ui.screens

import androidx.compose.runtime.Composable
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

@Composable
fun PicksScreen(vm: SkappViewModel, state: UiState) {
    RealtimeSummaryScreen(vm, state)
}
