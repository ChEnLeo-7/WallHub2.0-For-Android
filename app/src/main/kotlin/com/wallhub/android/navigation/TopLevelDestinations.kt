package com.wallhub.android

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
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
    PROFILE(ProfileDestination, R.string.navigation_profile, Icons.Outlined.PersonOutline, Icons.Filled.Person),
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
