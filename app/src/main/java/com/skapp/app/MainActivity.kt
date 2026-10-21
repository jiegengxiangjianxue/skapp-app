package com.skapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skapp.app.ui.MainScreen
import com.skapp.app.ui.SkappViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF58A6FF),
                    background = androidx.compose.ui.graphics.Color(0xFF0D1117),
                    surface = androidx.compose.ui.graphics.Color(0xFF161B22),
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: SkappViewModel = viewModel()
                    MainScreen(vm)
                }
            }
        }
    }
}
