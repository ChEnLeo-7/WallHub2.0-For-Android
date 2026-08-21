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
import com.wallhub.android.feature.discover.DiscoverRoute
import com.wallhub.android.feature.discover.DiscoverResultsRoute
import com.wallhub.android.feature.discover.DiscoverFollowingRoute
import com.wallhub.android.feature.discover.DiscoverQueryEditorRoute
import com.wallhub.android.feature.downloads.DownloadsRoute
import com.wallhub.android.feature.home.HomeRoute
import com.wallhub.android.feature.library.LibraryRoute
import com.wallhub.android.feature.local.LocalWallpaperRoute
import com.wallhub.android.feature.profile.ProfileRoute
import com.wallhub.settings.SettingsRoute

@Composable
internal fun WallHubNavHost(
    modifier: Modifier,
    navController: NavHostController,
    homeScrollRequest: Int,
    discoverRefreshRequest: Int,
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
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                scrollToTopRequest = homeScrollRequest,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        // These routes intentionally have independent identities even while their feature screens are migrated.
        // This lets state restoration and deep links distinguish the new surfaces from legacy pages.
        composable<DiscoverDestination> {
            DiscoverRoute(
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onOpenRail = { spec, resolvedTitle -> navController.navigate(spec.toResultsDestination(resolvedTitle)) },
                onOpenFollowing = { navController.navigate(DiscoverFollowingDestination) },
                onOpenFriendFavorites = {
                    navController.navigate(friendResultsDestination(favorites = true))
                },
                onOpenFriendCreated = {
                    navController.navigate(friendResultsDestination(favorites = false))
                },
                refreshRequest = discoverRefreshRequest,
            )
        }
        composable<DiscoverFollowingDestination> {
            DiscoverFollowingRoute(
                onBack = { navController.popBackStack() },
                onAddQuery = { navController.navigate(DiscoverQueryEditorDestination) },
                onOpenQuery = { query ->
                    if (query.id == "official:focus-creators" || query.id == "official:focus-collections") {
                        navController.navigate(DiscoverDestination) {
                            popUpTo(navController.graph.id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else {
                        navController.navigate(query.toResultsDestination())
                    }
                },
            )
        }
        composable<DiscoverQueryEditorDestination> {
            DiscoverQueryEditorRoute(
                onBack = { navController.popBackStack() },
                onSaved = { query ->
                    navController.popBackStack()
                    navController.navigate(query.toResultsDestination())
                },
            )
        }
        composable<DiscoverResultsDestination> {
            DiscoverResultsRoute(
                onBack = { navController.popBackStack() },
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
            )
        }
        composable<ProfileDestination> {
            ProfileRoute(
                onOpenSettings = openSettings,
                onOpenSubscriptions = { navController.navigate(LibraryCollectionDestination("SUBSCRIPTIONS")) },
                onOpenFavorites = { navController.navigate(LibraryCollectionDestination("FAVORITES")) },
                onOpenVoted = { navController.navigate(LibraryCollectionDestination("VOTED")) },
                onOpenDownloads = { navController.navigate(DownloadsDestination) },
                onOpenLocal = { navController.navigate(LocalDestination) },
                onOpenLogin = { navController.navigate(SteamLoginDestination) },
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
                onBack = { navController.popBackStack() },
                onPlayVideo = { navController.navigate(LocalVideoPlayerDestination(it)) },
            )
        }
        composable<LibraryDestination> {
            LibraryRoute(
                onBack = { navController.popBackStack() },
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onPlayVideo = { navController.navigate(OnlineVideoPlayerDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<LibraryCollectionDestination> { entry ->
            val route = entry.toRoute<LibraryCollectionDestination>()
            LibraryRoute(
                initialCollection = route.collection,
                onBack = { navController.popBackStack() },
                onOpenDetail = { navController.navigate(WorkshopDetailDestination(it)) },
                onPlayVideo = { navController.navigate(OnlineVideoPlayerDestination(it)) },
                onSearchAuthor = navigateToAuthorSearch,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable<LocalDestination> { LocalWallpaperRoute(onBack = { navController.popBackStack() }) }
        composable<SettingsDestination> {
            SettingsRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SteamLoginDestination> {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                openSteamSignIn = true,
            )
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
