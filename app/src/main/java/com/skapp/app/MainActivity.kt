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
