package com.skapp.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.skapp.app.ui.screens.DecisionScreen
import com.skapp.app.ui.screens.EnginesScreen
import com.skapp.app.ui.screens.PoolsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 屏渲染验收（JVM 上跑，不需要模拟器 / root / 无障碍）。
 *
 * ## 为什么要有这个文件
 *
 * 之前端上所有测试都停在**数据层**：`ScreenDataContractTest` 之类只断言「算出来的数对不对」，
 * 19 个 Screen 类**没有任何测试提及**——布局错位、文案缺失、空态没渲染，一条都测不出来，
 * 只能靠真机截图。容器与 CI 都没有 emulator / Shizuku / 无障碍，真机路线走不通，
 * 所以用 Robolectric 在 JVM 里提供 Android 运行时 + Compose 组合，把「这一屏能不能渲染出
 * 预期文案」变成可断言的事实。
 *
 * ## 断言口径
 *
 * 只断言**静态文案**（标题、空态提示）与「至少有一个节点显示出来」，不猜数据驱动的值——
 * 数据层的正确性由 `ScreenDataContractTest` / `ScreenDataContractRuntimeTest` 负责。
 * 屏在这里拿到的是默认 `UiState()`（空数据），因此同时顺带验了**空态不崩、不空白**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenRenderRobolectricTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm() = SkappViewModel()

    @Test
    fun `决策页在空数据下仍渲染标题与空态而不是白屏`() {
        compose.setContent { DecisionScreen(vm(), UiState()) }
        compose.onNodeWithText("市场决策").assertIsDisplayed()
    }

    @Test
    fun `引擎页在空数据下渲染引擎输出标题`() {
        compose.setContent { EnginesScreen(vm(), UiState()) }
        compose.onNodeWithText("引擎输出").assertIsDisplayed()
    }

    @Test
    fun `多池选股页在空数据下渲染标题`() {
        compose.setContent { PoolsScreen(vm(), UiState()) }
        compose.onNodeWithText("多池选股").assertIsDisplayed()
    }
}
