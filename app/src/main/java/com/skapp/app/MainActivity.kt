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

private val SkappLightColors = lightColorScheme(
    primary = Color(0xFF175D9C),
    onPrimary = Color.White,
    secondary = Color(0xFF50708D),
    onSecondary = Color.White,
    tertiary = Color(0xFF657B42),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF18232D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18232D),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFF4C5D6B),
    outline = Color(0xFFB8C5CF),
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
