package io.github.jgg113.stocknote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

private val tabIcons = mapOf(Tab.HOME to "▤", Tab.WATCH to "★", Tab.DISCOVER to "◎", Tab.INVEST to "₩", Tab.SETTINGS to "⚙")

@Composable
fun AppRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }
    // 알림을 켜면 (설정이든 종목 화면이든) 그때 알림 권한을 묻는다. Android 13 이상에서만 필요하다.
    val context = LocalContext.current
    val askNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.message("알림 권한이 없어 알림을 보낼 수 없습니다. 휴대폰 설정 → 앱 → 주식노트 → 알림에서 켜 주세요.")
    }
    LaunchedEffect(vm.alertSettings.enabled) {
        if (vm.alertSettings.enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val top = vm.stack.lastOrNull()
    BackHandler(enabled = top != null) { vm.back() }
    BackHandler(enabled = top == null && vm.tab != Tab.HOME) { vm.tab = Tab.HOME }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (top == null) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = { Text(tabIcons[t].orEmpty(), fontSize = 18.sp) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (top) {
                    null -> Column(Modifier.fillMaxSize()) {
                        when (vm.tab) {
                            Tab.HOME -> HomeScreen(vm)
                            Tab.WATCH -> WatchScreen(vm)
                            Tab.DISCOVER -> DiscoverScreen(vm)
                            Tab.INVEST -> InvestScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                    is Screen.Detail -> DetailScreen(vm)
                    is Screen.ThemeDetail -> ThemeDetailScreen(vm, top.theme)
                    is Screen.Search -> SearchScreen(vm, null)
                    is Screen.Pick -> SearchScreen(vm, top.purpose)
                    is Screen.StrategyDetail -> StrategyDetailScreen(vm, top.asset, top.strategyId)
                }
            }
        }
    }
}
