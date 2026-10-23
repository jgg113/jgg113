package io.github.jgg113.gongmae

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jgg113.gongmae.ui.AppRoot
import io.github.jgg113.gongmae.ui.theme.GongmaeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GongmaeTheme {
                AppRoot()
            }
        }
    }
}
