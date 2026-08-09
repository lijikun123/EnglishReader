package com.example.englishreader.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.englishreader.ui.library.LibraryScreen
import com.example.englishreader.ui.reader.ReaderScreen
import com.example.englishreader.ui.settings.SettingsScreen
import com.example.englishreader.ui.vocabulary.VocabularyScreen

/**
 * 自适应导航：
 *  - 手机（Compact 宽度）：底部 NavigationBar
 *  - 平板/大屏（Medium+）：左侧 NavigationRail，Reader 使用左右双栏
 *  - Reader 详情页隐藏顶级导航，进入全屏阅读
 */
@Composable
fun AppNavHost(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val isExpandedWidth = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showTopLevelNav = topLevelDestinations.any { it.destination.route == currentRoute }

    Row(Modifier.fillMaxSize()) {
        if (isExpandedWidth && showTopLevelNav) {
            AppNavigationRail(navController, currentRoute)
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!isExpandedWidth && showTopLevelNav) {
                    AppBottomBar(navController, currentRoute)
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Destination.Library.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                composable(Destination.Library.route) {
                    LibraryScreen(
                        onOpenReader = { id ->
                            navController.navigate(Destination.Reader.createRoute(id))
                        },
                    )
                }
                composable(Destination.Vocabulary.route) {
                    VocabularyScreen()
                }
                composable(Destination.Settings.route) {
                    SettingsScreen()
                }
                composable(
                    route = Destination.Reader.route,
                    arguments = listOf(
                        navArgument(Destination.Reader.ARG) { type = NavType.LongType },
                    ),
                ) {
                    ReaderScreen(
                        useTwoPane = isExpandedWidth,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(navController: NavController, currentRoute: String?) {
    NavigationBar {
        topLevelDestinations.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.destination.route,
                onClick = { navController.navigateToTopLevel(dest.destination) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(navController: NavController, currentRoute: String?) {
    NavigationRail {
        topLevelDestinations.forEach { dest ->
            NavigationRailItem(
                selected = currentRoute == dest.destination.route,
                onClick = { navController.navigateToTopLevel(dest.destination) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}

private fun NavController.navigateToTopLevel(destination: Destination) {
    navigate(destination.route) {
        // 避免重复堆栈，并在切换 tab 时保存/恢复状态。
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
