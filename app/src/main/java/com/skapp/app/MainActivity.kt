package com.skapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skapp.app.ui.MainScreen
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.theme.SkColors

private val SkappLightColors = lightColorScheme(
    primary = SkColors.primary,
    onPrimary = SkColors.onPrimary,
    secondary = SkColors.secondary,
    onSecondary = SkColors.onPrimary,
    tertiary = SkColors.tertiary,
    background = SkColors.surface0,
    onBackground = SkColors.textPrimary,
    surface = SkColors.surface1,
    onSurface = SkColors.textPrimary,
    surfaceVariant = SkColors.surface3,
    onSurfaceVariant = SkColors.textSecondary,
    outline = SkColors.outlineStrong,
    error = SkColors.errorText,
    onError = Color.White,
    errorContainer = SkColors.errorContainer,
    onErrorContainer = SkColors.errorText,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽最早安装崩溃捕获：闪退原因未知时，这是唯一能拿到堆栈的途径
        com.skapp.app.ui.CrashGuard.install(this)
        // 上次若崩过，把原始堆栈导出到 Download：用户不连电脑就能把日志发出来。
        // 放后台线程——MediaStore 写入要几百毫秒，不能拖住首帧。
        Thread { com.skapp.app.ui.CrashGuard.exportPending(applicationContext) }.start()
        // vc19 守卫：CrashGuard 只抓 Java 未捕获异常，抓不到 ANR / 低内存被回收 / native 崩溃。
        // 这里补一条与异常无关的通道：主线程心跳（停顿当场落盘）+ 内存水位采样。
        // 必须在主线程调用——心跳靠 mainLooper 自循环，这正是它能反映主线程卡顿的原因。
        com.skapp.app.ui.HealthGuard.install(applicationContext)
        Thread { com.skapp.app.ui.HealthGuard.exportPending(applicationContext) }.start()
        // 本地日级行情存档的落盘目录：连板高度/晋级率/板块动量/长周期 RPS 都靠它按天积累。
        // 放在这里初始化（而不是各模块自己找 Context），未初始化时存档模块安全地退化为 no-op。
        com.skapp.app.data.MarketDayArchive.init(filesDir)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = SkappLightColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: SkappViewModel = viewModel()
                    // 恢复 token → 全量拉取（顺序不能换：token 未就位前任何请求都会失败）
                    LaunchedEffect(Unit) {
                        vm.loadSavedToken(applicationContext)
                        vm.load()
                        vm.warmUpAll()
                    }
                    MainScreen(vm)
                }
            }
        }
    }
}
