package com.wallhub.android

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline

internal enum class TopLevelDestination(
    val target: WallHubDestination,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(HomeDestination, R.string.navigation_home, Icons.Outlined.Home, Icons.Filled.Home),
    DISCOVER(DiscoverDestination, R.string.navigation_discover, Icons.Outlined.Explore, Icons.Filled.Explore),
    DOWNLOADS(DownloadsDestination, R.string.navigation_downloads, Icons.Outlined.Download, Icons.Filled.Download),
    PROFILE(ProfileDestination, R.string.navigation_profile, Icons.Outlined.PersonOutline, Icons.Filled.Person),
}

internal fun topLevelDestinations(includeDownloads: Boolean): List<TopLevelDestination> =
    if (includeDownloads) {
        TopLevelDestination.entries
    } else {
        TopLevelDestination.entries.filterNot { it == TopLevelDestination.DOWNLOADS }
    }

internal fun NavDestination.topLevelDestinationOrNull(includeDownloads: Boolean = true): TopLevelDestination? =
    topLevelDestinations(includeDownloads).firstOrNull { destination -> hasRoute(destination.target::class) }

internal fun NavDestination.isTopLevelDestinationRoute(includeDownloads: Boolean): Boolean =
    topLevelDestinationOrNull(includeDownloads) != null

/** The single back-stack policy used by every top-level entry point, including empty-state CTAs. */
internal fun NavHostController.navigateToTopLevelDestination(destination: TopLevelDestination) {
    if (currentDestination?.topLevelDestinationOrNull() == destination) return
    navigate(destination.target) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun topLevelNavigationDirection(
    initialDestination: NavDestination,
    targetDestination: NavDestination,
): Int {
    val initialIndex =
        TopLevelDestination.entries.indexOfFirst { destination ->
            initialDestination.hasRoute(destination.target::class)
        }
    val targetIndex =
        TopLevelDestination.entries.indexOfFirst { destination ->
            targetDestination.hasRoute(destination.target::class)
        }
    return targetIndex.compareTo(initialIndex)
}
