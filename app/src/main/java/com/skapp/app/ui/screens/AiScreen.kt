package com.skapp.app.ui.screens

import androidx.compose.runtime.Composable
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/** 兼容旧路由；AI 内容统一由 AI 总结唯一入口提供。 */
@Composable
fun AiScreen(vm: SkappViewModel, state: UiState) {
    UnifiedAskScreen(vm, state)
}
