@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
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
    ) {
        if (!preferences.setupWizardCompleted) {
            WallHubSetupWizard(onComplete = onSetupWizardCompleted)
            return@WallHubTheme
        }
        val navController = rememberNavController()
        val context = LocalContext.current
        val windowWidthSizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentTopLevelDestination =
            TopLevelDestination.entries.firstOrNull { destination ->
                currentBackStackEntry?.destination?.hasRoute(destination.target::class) == true
            }
        val isTopLevelDestination = currentTopLevelDestination != null
        var homeScrollRequest by rememberSaveable { mutableIntStateOf(0) }
        var discoverRefreshRequest by rememberSaveable { mutableIntStateOf(0) }
        var homeContextMenuActive by remember { mutableStateOf(false) }
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
            when {
                destination == TopLevelDestination.HOME &&
                    currentTopLevelDestination == TopLevelDestination.HOME -> homeScrollRequest += 1

                destination == TopLevelDestination.DISCOVER &&
                    currentTopLevelDestination == TopLevelDestination.DISCOVER -> discoverRefreshRequest += 1

                currentTopLevelDestination == destination -> Unit

                else -> {
                    navController.navigate(destination.target) {
                        popUpTo(navController.graph.id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
        PredictiveBackHandler(
            enabled = isTopLevelDestination && currentTopLevelDestination != TopLevelDestination.HOME,
        ) {
            it.collect()
            navigateTo(TopLevelDestination.HOME)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            SteamSessionRestoreBanner(
                session = steamSession,
                onRetry = onRetrySteamRestore,
            )
            Box(modifier = Modifier.weight(1f)) {
                WallHubAdaptiveNavigationLayout(
                    windowWidthSizeClass = windowWidthSizeClass,
                    isTopLevelDestination = isTopLevelDestination,
                    currentTopLevelDestination = currentTopLevelDestination,
                    homeContextMenuActive = homeContextMenuActive,
                    onHomeContextMenuActiveChanged = { homeContextMenuActive = it },
                    onNavigateTo = navigateTo,
                    navController = navController,
                    homeScrollRequest = homeScrollRequest,
                    discoverRefreshRequest = discoverRefreshRequest,
                )
            }
        }
    }
}

@Composable
private fun SteamSessionRestoreBanner(
    session: SteamSessionState,
    onRetry: () -> Unit,
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
                    .statusBarsPadding()
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
                    if (!busy) {
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

internal fun shouldShowSteamSessionRestoreBanner(session: SteamSessionState): Boolean =
    session.hasStoredSession &&
        session.phase != SteamSessionPhase.SIGNED_IN &&
        session.phase != SteamSessionPhase.SIGNED_OUT

internal fun shouldReportExpiredPersistedSession(
    session: SteamSessionState,
    alreadyReported: Boolean,
): Boolean =
    !alreadyReported && session.phase == SteamSessionPhase.EXPIRED
