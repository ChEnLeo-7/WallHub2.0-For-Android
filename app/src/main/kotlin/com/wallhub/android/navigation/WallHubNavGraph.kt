@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.wallhub.android.feature.detail.LocalVideoPlayerRoute
import com.wallhub.android.feature.detail.OnlineVideoPlayerRoute
import com.wallhub.android.feature.detail.WorkshopDetailRoute
import com.wallhub.android.feature.downloads.DownloadsRoute
import com.wallhub.android.feature.home.HomeRoute
import com.wallhub.android.feature.library.LibraryRoute
import com.wallhub.android.feature.local.LocalWallpaperRoute
import com.wallhub.settings.SettingsRoute

@Composable
internal fun WallHubNavHost(
    modifier: Modifier,
    navController: NavHostController,
    homeScrollRequest: Int,
    onHomeContextMenuActiveChanged: (Boolean) -> Unit,
    animateTopLevelTransitions: Boolean,
) {
    val navigateToAuthorSearch: (String) -> Unit = { creator ->
        creator.authorSearchDestinationOrNull()?.let { destination ->
            val currentDestination = navController.currentDestination
            val currentCreatorId =
                if (currentDestination?.hasRoute<AuthorSearchDestination>() == true) {
                    navController.currentBackStackEntry?.toRoute<AuthorSearchDestination>()?.authorSearchCreator
                } else {
                    null
                }
            if (currentCreatorId != destination.authorSearchCreator) navController.navigate(destination)
        }
    }
    val navigateToTagSearch: (String) -> Unit = { tag ->
        tag.trim().takeIf(String::isNotEmpty)?.let { normalizedTag ->
            navController.navigate(TagSearchDestination(normalizedTag))
        }
    }
    val openSettings = {
        navController.navigate(SettingsDestination) { launchSingleTop = true }
    }
    NavHost(
        navController = navController,
        startDestination = HomeDestination,
        modifier =
            modifier
                .clipToBounds()
                .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            wallHubEnterTransition(initialState.destination, targetState.destination, animateTopLevelTransitions, popping = false)
        },
        exitTransition = {
            wallHubExitTransition(initialState.destination, targetState.destination, animateTopLevelTransitions, popping = false)
        },
        popEnterTransition = {
            wallHubEnterTransition(initialState.destination, targetState.destination, animateTopLevelTransitions, popping = true)
        },
        popExitTransition = {
            wallHubExitTransition(initialState.destination, targetState.destination, animateTopLevelTransitions, popping = true)
        },
        sizeTransform = { null },
    ) {
        composable<HomeDestination> {
            HomeRoute(
                onOpenSettings = openSettings,
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                scrollToTopRequest = homeScrollRequest,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<AuthorSearchDestination> {
            HomeRoute(
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                onBack = { navController.popBackStack() },
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<TagSearchDestination> {
            HomeRoute(
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                onBack = { navController.popBackStack() },
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<DownloadsDestination> {
            DownloadsRoute(
                onOpenSettings = openSettings,
                onPlayVideo = { navController.navigate(LocalVideoPlayerDestination(it)) },
            )
        }
        composable<LibraryDestination> {
            LibraryRoute(
                onOpenSettings = openSettings,
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onPlayVideo = { navController.navigate(OnlineVideoPlayerDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<LocalDestination> { LocalWallpaperRoute(onOpenSettings = openSettings) }
        composable<SettingsDestination> {
            SettingsRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SteamLoginDestination> {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (!navController.popBackStack()) {
                    navController.navigate(SettingsDestination) {
                        popUpTo<SteamLoginDestination> { inclusive = true }
                    }
                }
            }
        }
        composable<WorkshopDetailDestination>(
            deepLinks = listOf(navDeepLink<WorkshopDetailDestination>(basePath = "wallhub://workshop")),
        ) {
            WorkshopDetailRoute(
                onBack = { navController.popBackStack() },
                onSearchAuthor = navigateToAuthorSearch,
                onSearchTag = navigateToTagSearch,
                onOpenLocalVideo = { navController.navigate(LocalVideoPlayerDestination(it)) },
            )
        }
        composable<LocalVideoPlayerDestination> { LocalVideoPlayerRoute(onBack = { navController.popBackStack() }) }
        composable<OnlineVideoPlayerDestination> { OnlineVideoPlayerRoute(onBack = { navController.popBackStack() }) }
    }
}
