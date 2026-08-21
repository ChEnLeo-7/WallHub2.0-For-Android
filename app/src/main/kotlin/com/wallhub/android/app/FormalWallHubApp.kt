@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

internal fun shouldReportExpiredPersistedSession(
    session: SteamSessionState,
    alreadyReported: Boolean,
): Boolean =
    !alreadyReported &&
        session.hasStoredSession &&
        session.phase == SteamSessionPhase.EXPIRED
