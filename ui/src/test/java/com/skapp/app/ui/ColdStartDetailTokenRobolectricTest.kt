package com.skapp.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.skapp.app.data.AiRouter
import com.skapp.app.data.KPeriod
import com.skapp.app.ui.state.SKAPP_PREFS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「真机四项」里可由 JVM 断言的三个状态契约（冷启动 / 详情页 / 设置页双源 token）。
 *
 * ## 覆盖口径与不算覆盖的部分
 *
 * 1. **冷启动**：`loadSavedToken` 从 `SharedPreferences` 恢复双源凭据 → `aiConfigured`/`githubConfigured`/
 *    `aiProviderStatus` 落地、`_context` 注入、不带 `error`。**首屏「有数」（行情/异动/推股行数非空）不在覆盖内**，
 *    那需要真实网络与真实行情，本用例只能证明「装配链路不崩、状态字段可被首屏读取」。
 * 2. **详情页**：`openDetail` 首帧（`detail.code` / `detailLoading` / `detailAdjust`）与
 *    `switchPeriod`（日 → 周 → 月 → 日，本地聚合不请求网络）、`switchAdjust`（qfq → hfq 重开链路）、
 *    `closeDetail` 清空。**分时曲线真的画出折线不在覆盖内**（`minuteTrend` 需要网络数据）。
 * 3. **设置页双源 token**：`setAiToken`/`setGithubToken` → `saveTokenNow`/`saveGithubTokenNow` →
 *    `SharedPreferences` 真写入 → `aiProviderStatus == AiRouter.statusLine()`（刷新发生）→
 *    下一次冷启动能读回同一份凭据。**双源状态行的具体文案（可用 provider 数、冷却）不在覆盖内**，
 *    它取决于凭据是否有效与远端探活。
 * 4. **盘中 SSE**：`@Ignore`，见 [盘中 SSE 需设备证据]。
 *
 * ## 运行环境
 *
 * 与 `ScreenRenderRobolectricTest` 同源限制：Robolectric 起 Android 环境时 `AndroidTestEnvironment`
 * 会 `NativeCryptoJni.init()` 加载 conscrypt 原生库，而 `conscrypt-openjdk-uber` 只发 linux-x86_64，
 * aarch64 容器必 `UnsatisfiedLinkError`（失败点在 Robolectric 自身）。权威执行环境是 x86_64 的
 * CI job（`android-build.yml` 里校验 x86_64 runner 后跑 `:ui:testDebugUnitTest`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ColdStartDetailTokenRobolectricTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** 假凭据：只为走通「非空即已配置」分支，不代表任何真实账号。 */
    private val fakeAiToken = "TEST-gitcode-token-not-real"
    private val fakeGhToken = "TEST-github-token-not-real"

    private fun prefs() = ctx.getSharedPreferences(SKAPP_PREFS, Context.MODE_PRIVATE)

    private fun freshPrefs() {
        prefs().edit().clear().commit()
    }

    /** `openDetail` 的首帧赋值在 `viewModelScope.launch` 体内，需把主线程队列跑空才可见。 */
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test(timeout = 120_000)
    fun `冷启动 空偏好下双源未配置且首屏状态可读`() {
        freshPrefs()
        val vm = SkappViewModel()
        vm.loadSavedToken(ctx)

        val s = vm.state.value
        assertNotNull("loadSavedToken 必须注入应用 Context，否则设置页存不了 token", vm._context)
        assertFalse("空偏好下不应标记 AI 已配置", s.aiConfigured)
        assertFalse("空偏好下不应标记 GitHub 已配置", s.githubConfigured)
        assertEquals("空偏好下 aiToken 应为空串", "", s.aiToken)
        assertTrue("双源状态行不能为空——设置页直接渲染这一行", s.aiProviderStatus.isNotBlank())
        assertNull("冷启动装配不应带 error", s.error)
        assertTrue("首屏行情相位必须有文案（加载中/交易中/休市）", s.marketPhase.isNotBlank())
    }

    @Test(timeout = 120_000)
    fun `冷启动 已保存的凭据能被读回并标记已配置`() {
        freshPrefs().also {
            prefs().edit()
                .putString("gitcode_token", fakeAiToken)
                .putString("github_token", fakeGhToken)
                .commit()
        }
        val vm = SkappViewModel()
        vm.loadSavedToken(ctx)

        val s = vm.state.value
        assertEquals("gitcode_token 未从 SharedPreferences 恢复", fakeAiToken, s.aiToken)
        assertEquals("github_token 未从 SharedPreferences 恢复", fakeGhToken, s.githubToken)
        assertTrue(s.aiConfigured)
        assertTrue(s.githubConfigured)
        assertTrue("配置了凭据后双源状态行仍为空", s.aiProviderStatus.isNotBlank())
    }

    @Test(timeout = 120_000)
    fun `详情页 首帧状态与复权周期切换`() {
        freshPrefs()
        val vm = SkappViewModel()
        vm.loadSavedToken(ctx)

        vm.openDetail("600519", "qfq")
        idleMain()

        val opened = vm.state.value
        assertEquals("openDetail 后 detail.code 不符", "600519", opened.detail?.code)
        assertEquals("openDetail 传入的复权口径没有落到状态", "qfq", opened.detailAdjust)
        assertTrue("首帧应处于 detailLoading=true，详情页据此显示骨架", opened.detailLoading)
        assertEquals("新开详情应重置为日 K", KPeriod.DAY, opened.kPeriod)

        // 复权：qfq → hfq 必须重开链路（同口径早退，不重复请求）
        vm.switchAdjust("hfq")
        idleMain()
        assertEquals("切换复权后 detailAdjust 未更新", "hfq", vm.state.value.detailAdjust)

        // 周期：本地聚合，不请求网络
        vm.switchPeriod(KPeriod.WEEK)
        assertEquals(KPeriod.WEEK, vm.state.value.kPeriod)
        vm.switchPeriod(KPeriod.MONTH)
        assertEquals(KPeriod.MONTH, vm.state.value.kPeriod)
        vm.switchPeriod(KPeriod.DAY)
        assertEquals(KPeriod.DAY, vm.state.value.kPeriod)
        vm.switchPeriod(KPeriod.DAY)
        assertEquals("重复点同一周期应为幂等", KPeriod.DAY, vm.state.value.kPeriod)

        vm.closeDetail()
        idleMain()
        assertNull("closeDetail 必须清空 detail", vm.state.value.detail)
        assertFalse("closeDetail 必须退出 loading", vm.state.value.detailLoading)
    }

    @Test(timeout = 120_000)
    fun `设置页 保存双源 token 后状态行刷新且下次冷启动可读回`() {
        freshPrefs()
        val vm = SkappViewModel()
        vm.loadSavedToken(ctx)
        assertEquals("未配置时状态行也应是 AiRouter 的当前快照", AiRouter.statusLine(), vm.state.value.aiProviderStatus)

        vm.setAiToken(fakeAiToken)
        vm.setGithubToken(fakeGhToken)
        vm.saveTokenNow()
        vm.saveGithubTokenNow()

        assertEquals("gitcode_token 未写入 SharedPreferences", fakeAiToken, prefs().getString("gitcode_token", null))
        assertEquals("github_token 未写入 SharedPreferences", fakeGhToken, prefs().getString("github_token", null))
        assertTrue("保存后 aiConfigured 应为 true", vm.state.value.aiConfigured)
        assertTrue("保存后 githubConfigured 应为 true", vm.state.value.githubConfigured)
        assertEquals(
            "保存后双源状态行必须重新取 AiRouter 快照（设置页靠这一行判断保存是否生效）",
            AiRouter.statusLine(),
            vm.state.value.aiProviderStatus,
        )

        // 下一次冷启动必须读回同一份凭据，否则「保存成功」只是内存假象
        val reopened = SkappViewModel()
        reopened.loadSavedToken(ctx)
        assertEquals(fakeAiToken, reopened.state.value.aiToken)
        assertEquals(fakeGhToken, reopened.state.value.githubToken)
        assertTrue(reopened.state.value.aiConfigured)
        assertTrue(reopened.state.value.githubConfigured)
    }

    @Test(timeout = 120_000)
    @Ignore(
        "盘中 SSE 秒级推送需要真实行情源与设备网络：JVM 下 OpenDetail 的请求全部落空，" +
            "无法区分「SSE 在推」与「15 秒轮询兜底」，也看不到报价两次取样变化。" +
            "该步只能由设备证据（行进中两次 screencap 报价不同 + logcat）判定。"
    )
    fun `盘中 SSE 需设备证据`() {
    }
}
