package com.example.englishreader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String) {
    data object Library : Destination("library")
    data object Vocabulary : Destination("vocabulary")
    data object Settings : Destination("settings")

    data object Reader : Destination("reader/{readingItemId}") {
        const val ARG = "readingItemId"
        fun createRoute(id: Long): String = "reader/$id"
    }
}

/** 底部导航 / 侧边导航栏中的顶级目的地。 */
data class TopLevelDestination(
    val destination: Destination,
    val label: String,
    val icon: ImageVector,
)

val topLevelDestinations = listOf(
    TopLevelDestination(Destination.Library, "书架", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(Destination.Vocabulary, "生词本", Icons.Filled.Star),
    TopLevelDestination(Destination.Settings, "设置", Icons.Filled.Settings),
)
