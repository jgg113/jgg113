@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "검색", Icons.Filled.Search),
    MAP("map", "지도", Icons.Filled.Place),
    FAVORITES("favorites", "관심", Icons.Filled.Favorite),
    COMPARE("compare", "비교", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "설정", Icons.Filled.Settings),
}

private const val DETAIL_ROUTE = "detail/{id}"

private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val openDetail: (String) -> Unit = { id -> nav.navigate("detail/$id") }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (Tab.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.openTab(tab) },
                            icon = {
                                val badge = when (tab) {
                                    Tab.COMPARE -> vm.compareItems.size
                                    Tab.FAVORITES -> vm.favorites.size
                                    else -> 0
                                }
                                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.SEARCH.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.SEARCH.route) {
                SearchScreen(
                    vm = vm,
                    onOpenDetail = openDetail,
                    onOpenCompare = { nav.openTab(Tab.COMPARE) },
                    onOpenSettings = { nav.openTab(Tab.SETTINGS) },
                )
            }
            composable(Tab.MAP.route) {
                MapScreen(vm, onOpenDetail = openDetail)
            }
            composable(Tab.FAVORITES.route) { FavoritesScreen(vm, onOpenDetail = openDetail) }
            composable(Tab.COMPARE.route) {
                CompareScreen(vm, onOpenDetail = openDetail, onOpenSearch = { nav.openTab(Tab.SEARCH) })
            }
            composable(Tab.SETTINGS.route) { SettingsScreen(vm) }
            composable(DETAIL_ROUTE) { entry ->
                DetailScreen(vm, id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}
