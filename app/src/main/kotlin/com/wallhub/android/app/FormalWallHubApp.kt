@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubWindowHeight
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.app.AppNavigationCommand
import com.wallhub.android.app.AppNavigationCommandBus
import com.wallhub.android.app.DebugVideoCommandBus
import com.wallhub.android.feature.setup.WallHubSetupWizard
import kotlinx.coroutines.flow.collect

@Composable
fun FormalWallHubApp(
    preferences: AppPreferences,
    steamSession: SteamSessionState,
    onSetupWizardCompleted: () -> Unit,
    onRetrySteamRestore: () -> Unit,
) {
    WallHubTheme(
        preference = preferences.theme,
        accent = preferences.accent,
        customAccentColor = preferences.customAccentColor,
        useSystemMonet = preferences.useSystemMonet,
        visualStyle = preferences.visualStyle,
        schemeStyle = preferences.schemeStyle,
        contrastLevel = preferences.contrastLevel,
        pureBlackInDark = preferences.pureBlackInDark,
    ) {
        WallHubAppSurface {
            AnimatedContent(
                targetState = preferences.setupWizardCompleted,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(
                        tween(BOTTOM_NAVIGATION_FADE_DURATION_MS, easing = NAVIGATION_EASING),
                    ) togetherWith fadeOut(
                        tween(BOTTOM_NAVIGATION_FADE_DURATION_MS, easing = NAVIGATION_EASING),
                    )
                },
                label = "SetupMainShell",
            ) { setupCompleted ->
                if (!setupCompleted) {
                    WallHubSetupWizard(onComplete = onSetupWizardCompleted)
                    return@AnimatedContent
                }
            val navController = rememberNavController()
        // Downloads is a first-class destination in both appearance modes so the navigation
        // entry count and back-stack policy never depend on the selected visual style.
        val downloadsAreTopLevel = true
        val debugWorkshopId by DebugVideoCommandBus.openWorkshopId.collectAsStateWithLifecycle()
        val navigationCommand by AppNavigationCommandBus.command.collectAsStateWithLifecycle()
        LaunchedEffect(navigationCommand) {
            val command = navigationCommand ?: return@LaunchedEffect
            when (val target = command.target) {
                AppNavigationCommand.Target.Downloads ->
                    if (downloadsAreTopLevel) {
                        navController.navigateToTopLevelDestination(TopLevelDestination.DOWNLOADS)
                    } else {
                        navController.navigate(DownloadsDestination) { launchSingleTop = true }
                    }
                is AppNavigationCommand.Target.Workshop ->
                    navController.navigate(WorkshopDetailDestination(target.workshopId)) { launchSingleTop = true }
            }
            AppNavigationCommandBus.consume(command.id)
        }
        LaunchedEffect(debugWorkshopId) {
            val workshopId = debugWorkshopId ?: return@LaunchedEffect
            navController.navigate(OnlineVideoPlayerDestination(workshopId)) { launchSingleTop = true }
            DebugVideoCommandBus.consumeOpen(workshopId)
        }
        val context = LocalContext.current
        val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
        val windowWidthSizeClass = windowSizeClass.windowWidthSizeClass
        val adaptiveLayout =
            WallHubAdaptiveLayout(
                width =
                    when (windowWidthSizeClass) {
                        WindowWidthSizeClass.MEDIUM -> WallHubWindowLayout.MEDIUM
                        WindowWidthSizeClass.EXPANDED -> WallHubWindowLayout.EXPANDED
                        else -> WallHubWindowLayout.COMPACT
                    },
                height =
                    when (windowSizeClass.windowHeightSizeClass) {
                        WindowHeightSizeClass.COMPACT -> WallHubWindowHeight.COMPACT
                        WindowHeightSizeClass.EXPANDED -> WallHubWindowHeight.EXPANDED
                        else -> WallHubWindowHeight.MEDIUM
                    },
            )
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentTopLevelDestination =
            currentBackStackEntry?.destination?.topLevelDestinationOrNull(includeDownloads = downloadsAreTopLevel)
        val isTopLevelDestination = currentTopLevelDestination != null
        var homeScrollRequest by rememberSaveable { mutableIntStateOf(0) }
        var homeResetRequest by rememberSaveable { mutableIntStateOf(0) }
        var discoverRefreshRequest by rememberSaveable { mutableIntStateOf(0) }
        var homeContextMenuActive by remember { mutableStateOf(false) }
        var fullscreenPlayback by remember { mutableStateOf(false) }
        var homeBottomNavigationCollapsed by remember { mutableStateOf(false) }
        LaunchedEffect(currentTopLevelDestination, preferences.homeBottomBarAutoHide) {
            if (
                currentTopLevelDestination != TopLevelDestination.HOME ||
                !preferences.homeBottomBarAutoHide
            ) {
                homeBottomNavigationCollapsed = false
            }
        }
        val expiredSessionMessage = stringResource(R.string.app_saved_steam_session_expired)
        var expiredSessionReported by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(steamSession.phase, steamSession.hasStoredSession, expiredSessionMessage) {
            if (shouldReportExpiredPersistedSession(steamSession, expiredSessionReported)) {
                expiredSessionReported = true
                Toast.makeText(context.applicationContext, expiredSessionMessage, Toast.LENGTH_LONG).show()
                navController.navigate(SteamLoginDestination) {
                    launchSingleTop = true
                }
            } else if (
                steamSession.phase == SteamSessionPhase.SIGNED_IN ||
                !steamSession.hasStoredSession
            ) {
                expiredSessionReported = false
            }
        }
        val navigateTo: (TopLevelDestination) -> Unit = { destination ->
            val actualCurrentDestination =
                navController.currentDestination?.topLevelDestinationOrNull(includeDownloads = downloadsAreTopLevel)
            discoverRefreshRequest =
                nextDiscoverRefreshRequest(
                    currentRequest = discoverRefreshRequest,
                    currentDestination = actualCurrentDestination,
                    selectedDestination = destination,
                )
            if (destination == TopLevelDestination.HOME && actualCurrentDestination == TopLevelDestination.HOME) {
                homeScrollRequest += 1
            } else {
                navController.navigateToTopLevelDestination(destination)
            }
        }
        PredictiveBackHandler(
            enabled = isTopLevelDestination && currentTopLevelDestination != TopLevelDestination.HOME,
        ) {
            it.collect()
            navigateTo(TopLevelDestination.HOME)
        }

        CompositionLocalProvider(LocalWallHubAdaptiveLayout provides adaptiveLayout) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .consumeWindowInsets(WindowInsets.statusBars),
            ) {
                SteamSessionRestoreBanner(
                    session = steamSession,
                    onRetry = onRetrySteamRestore,
                    onOpenSignIn = {
                        navController.navigate(SteamLoginDestination) { launchSingleTop = true }
                    },
                )
                Box(modifier = Modifier.weight(1f)) {
                    WallHubAdaptiveNavigationLayout(
                        windowLayout = adaptiveLayout.width,
                        isTopLevelDestination = isTopLevelDestination,
                        currentTopLevelDestination = currentTopLevelDestination,
                        homeContextMenuActive = homeContextMenuActive,
                        onHomeContextMenuActiveChanged = { homeContextMenuActive = it },
                        homeBottomNavigationCollapsed = homeBottomNavigationCollapsed,
                        homeBottomBarAutoHideEnabled = preferences.homeBottomBarAutoHide,
                        onHomeBottomNavigationCollapsedChanged = { homeBottomNavigationCollapsed = it },
                        onNavigateTo = navigateTo,
                        navController = navController,
                        homeScrollRequest = homeScrollRequest,
                        homeResetRequest = homeResetRequest,
                        onHomeResetRequest = { homeResetRequest += 1 },
                        discoverRefreshRequest = discoverRefreshRequest,
                        fullscreenPlayback = fullscreenPlayback,
                        onFullscreenPlaybackChanged = { fullscreenPlayback = it },
                    )
                }
            }
        }
        }
    }
}
}

internal fun nextDiscoverRefreshRequest(
    currentRequest: Int,
    currentDestination: TopLevelDestination?,
    selectedDestination: TopLevelDestination?,
): Int =
    if (
        selectedDestination == TopLevelDestination.DISCOVER &&
        currentDestination == TopLevelDestination.DISCOVER
    ) {
        currentRequest + 1
    } else {
        currentRequest
    }

@Composable
internal fun SteamSessionRestoreBanner(
    session: SteamSessionState,
    onRetry: () -> Unit,
    onOpenSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy =
        session.phase == SteamSessionPhase.SIGNING_IN ||
            session.phase == SteamSessionPhase.WAITING_FOR_CODE ||
            session.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION
    AnimatedVisibility(
        visible = shouldShowSteamSessionRestoreBanner(session),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.message ?: stringResource(R.string.backend_steam_restoring_session),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (shouldOpenSteamSignInFromRestoreBanner(session)) {
                        TextButton(onClick = onOpenSignIn) {
                            Text(
                                stringResource(
                                    if (busy) R.string.settings_action_view_sign_in_progress
                                    else R.string.settings_action_sign_in_again_steam,
                                ),
                            )
                        }
                    } else if (!busy) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription =
                                    stringResource(R.string.settings_action_retry_restore_steam_session),
                            )
                        }
                    }
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// Saved identity without a live transport (RESTORABLE), and recovery in progress, are not failures.
// Keep attention states explicit so normal foreground recovery cannot become a global alarm.
private fun shouldShowSteamSessionRestoreBanner(session: SteamSessionState): Boolean =
    shouldOpenSteamSignInFromRestoreBanner(session) ||
        (session.hasStoredSession && session.phase == SteamSessionPhase.FAILED)

private fun shouldOpenSteamSignInFromRestoreBanner(session: SteamSessionState): Boolean =
    session.phase == SteamSessionPhase.EXPIRED ||
        session.phase == SteamSessionPhase.WAITING_FOR_CODE ||
        session.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION

private fun shouldReportExpiredPersistedSession(
    session: SteamSessionState,
    alreadyReported: Boolean,
): Boolean =
    !alreadyReported && session.phase == SteamSessionPhase.EXPIRED
