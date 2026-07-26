package com.wallhub.android

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.WallHubGlobalToastHost
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.rememberWallHubToastState
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.feature.detail.LocalVideoPlayerRoute
import com.wallhub.android.feature.detail.OnlineVideoPlayerRoute
import com.wallhub.android.feature.detail.WorkshopDetailRoute
import com.wallhub.android.feature.home.HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT
import com.wallhub.android.feature.home.HomeRoute
import com.wallhub.android.feature.settings.SettingsRoute
import com.wallhub.android.feature.settings.SteamLoginRoute

private const val STEAM_LOGIN_ROUTE = "steam-login"
private const val AUTHOR_SEARCH_ROUTE = "author-search"
private const val WORKSHOP_DETAIL_ROUTE = "workshop-detail"
private const val WORKSHOP_DETAIL_ID_ARGUMENT = "workshopId"
private const val LOCAL_VIDEO_PLAYER_ROUTE = "local-video-player"
private const val LOCAL_VIDEO_TASK_ARGUMENT = "taskId"
private const val ONLINE_VIDEO_PLAYER_ROUTE = "online-video-player"
private const val ONLINE_VIDEO_WORKSHOP_ARGUMENT = "workshopId"

private enum class TopLevelDestination(
    val route: String,
    val labelZh: String,
    val labelEn: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("home", "发现", "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
    MANAGEMENT("management", "管理", "Manage", Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
    SETTINGS("settings", "设置", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),

    ;

    fun label(language: AppLanguage): String = if (language == AppLanguage.EN) labelEn else labelZh
}

@Composable
fun FormalWallHubApp(
    preferences: AppPreferences,
) {
    WallHubTheme(
        preference = preferences.theme,
        language = preferences.language,
        accent = preferences.accent,
        customAccentColor = preferences.customAccentColor,
        useSystemMonet = preferences.useSystemMonet,
    ) {
        val navController = rememberNavController()
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = currentBackStackEntry?.destination?.route
        val destinations = TopLevelDestination.entries
        val isTopLevelDestination = destinations.any { it.route == currentRoute }
        var homeScrollRequest by rememberSaveable { mutableIntStateOf(0) }
        var managementScrollToTopRequest by rememberSaveable { mutableIntStateOf(0) }
        var homeContextMenuActive by remember { mutableStateOf(false) }
        val toastState = rememberWallHubToastState()
        val navigateTo: (TopLevelDestination) -> Unit = { destination ->
            val activeRoute = navController.currentDestination?.route
            when {
                destination == TopLevelDestination.HOME &&
                    activeRoute == TopLevelDestination.HOME.route -> homeScrollRequest += 1

                destination == TopLevelDestination.MANAGEMENT &&
                    activeRoute == TopLevelDestination.MANAGEMENT.route -> managementScrollToTopRequest += 1

                activeRoute == destination.route -> Unit

                else -> {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
        val navigateAcrossManagementBoundary: (Int) -> Unit = { direction ->
            val managementIndex = destinations.indexOf(TopLevelDestination.MANAGEMENT)
            destinations.getOrNull(managementIndex + direction)?.let(navigateTo)
        }
        BackHandler(
            enabled = isTopLevelDestination && currentRoute != TopLevelDestination.HOME.route,
        ) {
            navigateTo(TopLevelDestination.HOME)
        }

        CompositionLocalProvider(LocalWallHubToastState provides toastState) {
            WallHubGlobalToastHost(
                toastState = toastState,
                modifier = Modifier.fillMaxSize(),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 720.dp
            if (useNavigationRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (isTopLevelDestination) {
                        NavigationRail(
                            modifier = Modifier.appContextMenuBackdrop(homeContextMenuActive),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            destinations.forEach { destination ->
                                val selected = currentRoute == destination.route
                                NavigationRailItem(
                                    selected = selected,
                                    enabled = !homeContextMenuActive,
                                    onClick = { navigateTo(destination) },
                                    icon = {
                                        WallHubDestinationIcon(
                                            destination = destination,
                                            selected = selected,
                                            contentDescription = destination.label(preferences.language),
                                        )
                                    },
                                    label = { Text(destination.label(preferences.language)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                    WallHubNavHost(
                        modifier = Modifier.fillMaxSize(),
                        navController = navController,
                        homeScrollRequest = homeScrollRequest,
                        managementScrollToTopRequest = managementScrollToTopRequest,
                        onHomeContextMenuActiveChanged = { homeContextMenuActive = it },
                        onManagementBoundaryNavigation = navigateAcrossManagementBoundary,
                        animateTopLevelTransitions = false,
                    )
                }
            } else {
                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = isTopLevelDestination,
                            enter = expandVertically(
                                expandFrom = Alignment.Bottom,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) + slideInVertically(
                                initialOffsetY = { height -> height },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) + fadeIn(tween(BOTTOM_NAVIGATION_FADE_DURATION_MS)),
                            exit = shrinkVertically(
                                shrinkTowards = Alignment.Bottom,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) + slideOutVertically(
                                targetOffsetY = { height -> height },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) + fadeOut(tween(BOTTOM_NAVIGATION_FADE_DURATION_MS)),
                            label = "BottomNavigationVisibility",
                        ) {
                            NavigationBar(
                                modifier = Modifier
                                    .appContextMenuBackdrop(homeContextMenuActive),
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                destinations.forEach { destination ->
                                    val selected = currentRoute == destination.route
                                    NavigationBarItem(
                                        selected = selected,
                                        enabled = !homeContextMenuActive,
                                        onClick = { navigateTo(destination) },
                                        icon = {
                                            WallHubDestinationIcon(
                                                destination = destination,
                                                selected = selected,
                                                contentDescription = destination.label(preferences.language),
                                            )
                                        },
                                        label = { Text(destination.label(preferences.language)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    WallHubNavHost(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                        navController = navController,
                        homeScrollRequest = homeScrollRequest,
                        managementScrollToTopRequest = managementScrollToTopRequest,
                        onHomeContextMenuActiveChanged = { homeContextMenuActive = it },
                        onManagementBoundaryNavigation = navigateAcrossManagementBoundary,
                        animateTopLevelTransitions = true,
                    )
                }
            }
                }
            }
        }
    }
}

@Composable
private fun WallHubDestinationIcon(
    destination: TopLevelDestination,
    selected: Boolean,
    contentDescription: String,
) {
    Crossfade(
        targetState = selected,
        animationSpec = tween(BOTTOM_NAVIGATION_ICON_FADE_DURATION_MS),
        label = "${destination.route}NavigationIcon",
    ) { isSelected ->
        Icon(
            imageVector = if (isSelected) destination.selectedIcon else destination.icon,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun WallHubNavHost(
    modifier: Modifier,
    navController: NavHostController,
    homeScrollRequest: Int,
    managementScrollToTopRequest: Int,
    onHomeContextMenuActiveChanged: (Boolean) -> Unit,
    onManagementBoundaryNavigation: (Int) -> Unit,
    animateTopLevelTransitions: Boolean,
) {
    val navigateToAuthorSearch: (String) -> Unit = { creator ->
        creator.authorSearchRouteOrNull()?.let { route ->
            val normalizedCreatorId = route.substringAfterLast('/')
            val currentCreatorId = navController.currentBackStackEntry
                ?.arguments
                ?.getString(HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT)
            val alreadyShowingCreator = navController.currentDestination?.route ==
                "$AUTHOR_SEARCH_ROUTE/{$HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT}" &&
                currentCreatorId == normalizedCreatorId
            if (!alreadyShowingCreator) navController.navigate(route)
        }
    }
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.HOME.route,
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            if (
                initialState.destination.route.isTopLevelDestinationRoute() &&
                targetState.destination.route.isTopLevelDestinationRoute()
            ) {
                if (animateTopLevelTransitions) {
                    wallHubTopLevelEnterTransition(
                        direction = topLevelNavigationDirection(
                            initialRoute = initialState.destination.route,
                            targetRoute = targetState.destination.route,
                        ),
                    )
                } else {
                    EnterTransition.None
                }
            } else {
                wallHubForwardEnterTransition()
            }
        },
        exitTransition = {
            if (
                initialState.destination.route.isTopLevelDestinationRoute() &&
                targetState.destination.route.isTopLevelDestinationRoute()
            ) {
                if (animateTopLevelTransitions) {
                    wallHubTopLevelExitTransition(
                        direction = topLevelNavigationDirection(
                            initialRoute = initialState.destination.route,
                            targetRoute = targetState.destination.route,
                        ),
                    )
                } else {
                    ExitTransition.None
                }
            } else {
                wallHubForwardExitTransition()
            }
        },
        popEnterTransition = {
            if (
                initialState.destination.route.isTopLevelDestinationRoute() &&
                targetState.destination.route.isTopLevelDestinationRoute()
            ) {
                if (animateTopLevelTransitions) {
                    wallHubTopLevelEnterTransition(
                        direction = topLevelNavigationDirection(
                            initialRoute = initialState.destination.route,
                            targetRoute = targetState.destination.route,
                        ),
                    )
                } else {
                    EnterTransition.None
                }
            } else {
                wallHubBackEnterTransition()
            }
        },
        popExitTransition = {
            if (
                initialState.destination.route.isTopLevelDestinationRoute() &&
                targetState.destination.route.isTopLevelDestinationRoute()
            ) {
                if (animateTopLevelTransitions) {
                    wallHubTopLevelExitTransition(
                        direction = topLevelNavigationDirection(
                            initialRoute = initialState.destination.route,
                            targetRoute = targetState.destination.route,
                        ),
                    )
                } else {
                    ExitTransition.None
                }
            } else {
                wallHubBackExitTransition()
            }
        },
        sizeTransform = { null },
    ) {
        composable(TopLevelDestination.HOME.route) {
            HomeRoute(
                onOpenDetail = { workshopId ->
                    navController.navigate("$WORKSHOP_DETAIL_ROUTE/$workshopId")
                },
                onSearchAuthor = navigateToAuthorSearch,
                scrollToTopRequest = homeScrollRequest,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable(
            route = "$AUTHOR_SEARCH_ROUTE/{$HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT}",
            arguments = listOf(
                navArgument(HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT) { type = NavType.StringType },
            ),
        ) {
            HomeRoute(
                onOpenDetail = { workshopId ->
                    navController.navigate("$WORKSHOP_DETAIL_ROUTE/$workshopId")
                },
                onSearchAuthor = navigateToAuthorSearch,
                onBack = { navController.popBackStack() },
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
            )
        }
        composable(TopLevelDestination.MANAGEMENT.route) {
            ManagementRoute(
                onOpenDetail = { workshopId ->
                    navController.navigate("$WORKSHOP_DETAIL_ROUTE/$workshopId")
                },
                onOpenLocalVideo = { taskId ->
                    navController.navigate("$LOCAL_VIDEO_PLAYER_ROUTE/$taskId")
                },
                onOpenOnlineVideo = { workshopId ->
                    navController.navigate("$ONLINE_VIDEO_PLAYER_ROUTE/$workshopId")
                },
                onSearchAuthor = navigateToAuthorSearch,
                libraryScrollToTopRequest = managementScrollToTopRequest,
                onContextMenuActiveChanged = onHomeContextMenuActiveChanged,
                onNavigatePreviousTopLevel = { onManagementBoundaryNavigation(-1) },
                onNavigateNextTopLevel = { onManagementBoundaryNavigation(1) },
            )
        }
        composable(TopLevelDestination.SETTINGS.route) {
            SettingsRoute(onOpenSteamLogin = { navController.navigate(STEAM_LOGIN_ROUTE) })
        }
        composable(STEAM_LOGIN_ROUTE) {
            SteamLoginRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = "$WORKSHOP_DETAIL_ROUTE/{$WORKSHOP_DETAIL_ID_ARGUMENT}",
            arguments = listOf(
                navArgument(WORKSHOP_DETAIL_ID_ARGUMENT) { type = NavType.LongType },
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "wallhub://workshop/{$WORKSHOP_DETAIL_ID_ARGUMENT}" },
            ),
        ) {
            WorkshopDetailRoute(
                onBack = { navController.popBackStack() },
                onSearchAuthor = navigateToAuthorSearch,
                onOpenLocalVideo = { taskId ->
                    navController.navigate("$LOCAL_VIDEO_PLAYER_ROUTE/$taskId")
                },
                onOpenOnlineVideo = { workshopId ->
                    navController.navigate("$ONLINE_VIDEO_PLAYER_ROUTE/$workshopId")
                },
            )
        }
        composable(
            route = "$LOCAL_VIDEO_PLAYER_ROUTE/{$LOCAL_VIDEO_TASK_ARGUMENT}",
            arguments = listOf(
                navArgument(LOCAL_VIDEO_TASK_ARGUMENT) { type = NavType.StringType },
            ),
        ) {
            LocalVideoPlayerRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = "$ONLINE_VIDEO_PLAYER_ROUTE/{$ONLINE_VIDEO_WORKSHOP_ARGUMENT}",
            arguments = listOf(
                navArgument(ONLINE_VIDEO_WORKSHOP_ARGUMENT) { type = NavType.LongType },
            ),
        ) {
            OnlineVideoPlayerRoute(onBack = { navController.popBackStack() })
        }
    }
}

private fun String.authorSearchRouteOrNull(): String? =
    filter(Char::isDigit)
        .takeIf(String::isNotBlank)
        ?.let { creatorId -> "$AUTHOR_SEARCH_ROUTE/$creatorId" }

private fun String?.isTopLevelDestinationRoute(): Boolean =
    TopLevelDestination.entries.any { destination -> destination.route == this }

private fun topLevelNavigationDirection(
    initialRoute: String?,
    targetRoute: String?,
): Int {
    val initialIndex = TopLevelDestination.entries.indexOfFirst { it.route == initialRoute }
    val targetIndex = TopLevelDestination.entries.indexOfFirst { it.route == targetRoute }
    return targetIndex.compareTo(initialIndex)
}

private fun wallHubTopLevelEnterTransition(
    direction: Int,
): EnterTransition = slideInHorizontally(
    initialOffsetX = { width -> direction * width },
    animationSpec = tween(
        durationMillis = TOP_LEVEL_TRANSITION_DURATION_MS,
        easing = TOP_LEVEL_NAVIGATION_EASING,
    ),
)

private const val BOTTOM_NAVIGATION_FADE_DURATION_MS = 180
private const val BOTTOM_NAVIGATION_ICON_FADE_DURATION_MS = 160

private fun wallHubTopLevelExitTransition(direction: Int): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { width -> -direction * width },
        animationSpec = tween(
            durationMillis = TOP_LEVEL_TRANSITION_DURATION_MS,
            easing = TOP_LEVEL_NAVIGATION_EASING,
        ),
    )

private fun wallHubForwardEnterTransition() =
    fadeIn(
        animationSpec = tween(
            durationMillis = SECONDARY_ENTER_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    ) + slideInHorizontally(
        initialOffsetX = { width -> width / SECONDARY_ENTER_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = SECONDARY_ENTER_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    )

private fun wallHubForwardExitTransition() =
    fadeOut(
        animationSpec = tween(
            durationMillis = SECONDARY_EXIT_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    ) + slideOutHorizontally(
        targetOffsetX = { width -> -width / SECONDARY_EXIT_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = SECONDARY_EXIT_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    )

private fun wallHubBackEnterTransition() =
    fadeIn(
        animationSpec = tween(
            durationMillis = SECONDARY_ENTER_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    ) + slideInHorizontally(
        initialOffsetX = { width -> -width / SECONDARY_EXIT_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = SECONDARY_ENTER_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    )

private fun wallHubBackExitTransition() =
    fadeOut(
        animationSpec = tween(
            durationMillis = SECONDARY_EXIT_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    ) + slideOutHorizontally(
        targetOffsetX = { width -> width / SECONDARY_ENTER_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = SECONDARY_EXIT_DURATION_MS,
            easing = WALLHUB_NAVIGATION_EASING,
        ),
    )

@Composable
private fun Modifier.appContextMenuBackdrop(active: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) {
                APP_CONTEXT_MENU_BACKDROP_ENTER_DURATION_MS
            } else {
                APP_CONTEXT_MENU_BACKDROP_EXIT_DURATION_MS
            },
            easing = APP_CONTEXT_MENU_EASING,
        ),
        label = "AppContextMenuBackdrop",
    )
    if (!active && progress <= 0f) return this
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrimAlpha = if (isDarkBackground) {
        APP_CONTEXT_MENU_DARK_SCRIM_ALPHA
    } else {
        APP_CONTEXT_MENU_LIGHT_SCRIM_ALPHA
    }
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha * progress)
    val materialModifier = this
        .drawWithContent {
            drawContent()
            drawRect(scrimColor)
        }
        .then(if (active) Modifier.semantics { invisibleToUser() } else Modifier)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress > 0f) {
        materialModifier.blur(APP_CONTEXT_MENU_BLUR_RADIUS * progress)
    } else {
        materialModifier
    }
}

private const val TOP_LEVEL_TRANSITION_DURATION_MS = 340
private const val SECONDARY_ENTER_DURATION_MS = 320
private const val SECONDARY_EXIT_DURATION_MS = 220
private const val SECONDARY_ENTER_OFFSET_DIVISOR = 9
private const val SECONDARY_EXIT_OFFSET_DIVISOR = 18
private const val APP_CONTEXT_MENU_BACKDROP_ENTER_DURATION_MS = 160
private const val APP_CONTEXT_MENU_BACKDROP_EXIT_DURATION_MS = 130
private const val APP_CONTEXT_MENU_LIGHT_SCRIM_ALPHA = 0.14f
private const val APP_CONTEXT_MENU_DARK_SCRIM_ALPHA = 0.20f
private val APP_CONTEXT_MENU_BLUR_RADIUS = 12.dp
private val APP_CONTEXT_MENU_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val TOP_LEVEL_NAVIGATION_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val WALLHUB_NAVIGATION_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
