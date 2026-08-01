@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.WallHubGlobalToastHost
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.rememberWallHubToastState
import com.wallhub.android.core.model.AppPreferences
import kotlinx.coroutines.flow.collect

@Composable
fun FormalWallHubApp(preferences: AppPreferences) {
    WallHubTheme(
        preference = preferences.theme,
        accent = preferences.accent,
        customAccentColor = preferences.customAccentColor,
        useSystemMonet = preferences.useSystemMonet,
    ) {
        val navController = rememberNavController()
        val windowWidthSizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentTopLevelDestination =
            TopLevelDestination.entries.firstOrNull { destination ->
                currentBackStackEntry?.destination?.hasRoute(destination.target::class) == true
            }
        val isTopLevelDestination = currentTopLevelDestination != null
        var homeScrollRequest by rememberSaveable { mutableIntStateOf(0) }
        var homeContextMenuActive by remember { mutableStateOf(false) }
        val toastState = rememberWallHubToastState()
        val navigateTo: (TopLevelDestination) -> Unit = { destination ->
            when {
                destination == TopLevelDestination.HOME &&
                    currentTopLevelDestination == TopLevelDestination.HOME -> homeScrollRequest += 1

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

        CompositionLocalProvider(LocalWallHubToastState provides toastState) {
            WallHubGlobalToastHost(
                toastState = toastState,
                modifier = Modifier.fillMaxSize(),
            ) {
                WallHubAdaptiveNavigationLayout(
                    windowWidthSizeClass = windowWidthSizeClass,
                    isTopLevelDestination = isTopLevelDestination,
                    currentTopLevelDestination = currentTopLevelDestination,
                    homeContextMenuActive = homeContextMenuActive,
                    onHomeContextMenuActiveChanged = { homeContextMenuActive = it },
                    onNavigateTo = navigateTo,
                    navController = navController,
                    homeScrollRequest = homeScrollRequest,
                )
            }
        }
    }
}
