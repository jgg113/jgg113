package io.github.jgg113.stocknote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.jgg113.stocknote.data.AlertWorker
import io.github.jgg113.stocknote.ui.AppRoot
import io.github.jgg113.stocknote.ui.AppViewModel
import io.github.jgg113.stocknote.ui.theme.StockTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StockTheme { AppRoot(vm) } }
        // 알림 권한은 화면(AppRoot)에서 알림을 켤 때 묻는다. 여기서는 예약만 다시 건다.
        val alerts = vm.alertSettings
        if (alerts.enabled) AlertWorker.schedule(this, alerts)
    }
}
