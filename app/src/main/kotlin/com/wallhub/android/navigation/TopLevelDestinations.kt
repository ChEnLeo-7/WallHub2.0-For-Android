package com.wallhub.android

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

internal enum class TopLevelDestination(
    val target: WallHubDestination,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(HomeDestination, R.string.navigation_discover, Icons.Outlined.Explore, Icons.Filled.Explore),
    DOWNLOADS(DownloadsDestination, R.string.navigation_downloads, Icons.Outlined.Download, Icons.Filled.Download),
    LIBRARY(LibraryDestination, R.string.navigation_library, Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    LOCAL(LocalDestination, R.string.navigation_local, Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
}

internal fun NavDestination.isTopLevelDestinationRoute(): Boolean =
    TopLevelDestination.entries.any { destination -> hasRoute(destination.target::class) }

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
