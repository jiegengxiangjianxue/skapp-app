package com.skapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        com.skapp.app.ui.CrashGuard.install(this)
        Thread { com.skapp.app.ui.CrashGuard.exportPending(applicationContext) }.start()
        com.skapp.app.ui.HealthGuard.install(applicationContext)
        Thread { com.skapp.app.ui.HealthGuard.exportPending(applicationContext) }.start()
        com.skapp.app.data.MarketDayArchive.init(filesDir)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            MaterialTheme(colorScheme = SkappLightColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: SkappViewModel = viewModel()
                    // 恢复 token → 全量拉取（顺序不能换：token 未就位前任何请求都会失败）
                    LaunchedEffect(Unit) {
                        vm.loadSavedToken(applicationContext)
                        vm.load()
                    }

                    MainScreen(vm)
                }
            }
        }
    }
}
