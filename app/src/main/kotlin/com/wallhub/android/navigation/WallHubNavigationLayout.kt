@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.window.core.layout.WindowWidthSizeClass
import com.wallhub.android.core.designsystem.WallHubSizeTokens

@Composable
internal fun WallHubAdaptiveNavigationLayout(
    windowWidthSizeClass: WindowWidthSizeClass,
    isTopLevelDestination: Boolean,
    currentTopLevelDestination: TopLevelDestination?,
    homeContextMenuActive: Boolean,
    onHomeContextMenuActiveChanged: (Boolean) -> Unit,
    onNavigateTo: (TopLevelDestination) -> Unit,
    navController: NavHostController,
    homeScrollRequest: Int,
) {
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.MEDIUM -> {
            Row(modifier = Modifier.fillMaxSize()) {
                if (isTopLevelDestination) {
                    NavigationRail(
                        modifier = Modifier.appContextMenuBackdrop(homeContextMenuActive),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentTopLevelDestination == destination
                            val label = stringResource(destination.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                enabled = !homeContextMenuActive,
                                onClick = { onNavigateTo(destination) },
                                icon = { WallHubDestinationIcon(destination, selected, label) },
                                label = { Text(label) },
                                colors =
                                    NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                            )
                        }
                    }
                }
                WallHubNavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    homeScrollRequest = homeScrollRequest,
                    onHomeContextMenuActiveChanged = onHomeContextMenuActiveChanged,
                    animateTopLevelTransitions = false,
                )
            }
        }

        WindowWidthSizeClass.EXPANDED -> {
            if (isTopLevelDestination) {
                PermanentNavigationDrawer(
                    drawerContent = {
                        PermanentDrawerSheet(
                            modifier =
                                Modifier
                                    .width(WallHubSizeTokens.expandedNavigationDrawerWidth)
                                    .appContextMenuBackdrop(homeContextMenuActive),
                            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            TopLevelDestination.entries.forEach { destination ->
                                val selected = currentTopLevelDestination == destination
                                val label = stringResource(destination.labelRes)
                                NavigationDrawerItem(
                                    selected = selected,
                                    onClick = { if (!homeContextMenuActive) onNavigateTo(destination) },
                                    icon = { WallHubDestinationIcon(destination, selected, label) },
                                    label = { Text(label) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                    colors =
                                        NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                )
                            }
                        }
                    },
                ) {
                    WallHubNavHost(
                        modifier = Modifier.fillMaxSize(),
                        navController = navController,
                        homeScrollRequest = homeScrollRequest,
                        onHomeContextMenuActiveChanged = onHomeContextMenuActiveChanged,
                        animateTopLevelTransitions = false,
                    )
                }
            } else {
                WallHubNavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    homeScrollRequest = homeScrollRequest,
                    onHomeContextMenuActiveChanged = onHomeContextMenuActiveChanged,
                    animateTopLevelTransitions = false,
                )
            }
        }

        else -> {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    AnimatedVisibility(
                        visible = isTopLevelDestination,
                        enter =
                            expandVertically(
                                expandFrom = Alignment.Bottom,
                                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                            ) +
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                                ) + fadeIn(tween(BOTTOM_NAVIGATION_FADE_DURATION_MS)),
                        exit =
                            shrinkVertically(
                                shrinkTowards = Alignment.Bottom,
                                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                            ) +
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                                ) + fadeOut(tween(BOTTOM_NAVIGATION_FADE_DURATION_MS)),
                    ) {
                        NavigationBar(
                            modifier = Modifier.appContextMenuBackdrop(homeContextMenuActive),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            TopLevelDestination.entries.forEach { destination ->
                                val selected = currentTopLevelDestination == destination
                                val label = stringResource(destination.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    enabled = !homeContextMenuActive,
                                    onClick = { onNavigateTo(destination) },
                                    icon = { WallHubDestinationIcon(destination, selected, label) },
                                    label = { Text(label) },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                WallHubNavHost(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    navController = navController,
                    homeScrollRequest = homeScrollRequest,
                    onHomeContextMenuActiveChanged = onHomeContextMenuActiveChanged,
                    animateTopLevelTransitions = true,
                )
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
        label = "${destination.name}NavigationIcon",
    ) { isSelected ->
        Icon(
            imageVector = if (isSelected) destination.selectedIcon else destination.icon,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun Modifier.appContextMenuBackdrop(active: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (active) CONTEXT_MENU_BACKDROP_ENTER_MS else CONTEXT_MENU_BACKDROP_EXIT_MS,
                easing = NAVIGATION_EASING,
            ),
    )
    if (!active && progress <= 0f) return this
    val scrimAlpha =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            CONTEXT_MENU_DARK_SCRIM_ALPHA
        } else {
            CONTEXT_MENU_LIGHT_SCRIM_ALPHA
        }
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha * progress)
    val materialModifier =
        this
            .drawWithContent {
                drawContent()
                drawRect(scrimColor)
            }.then(if (active) Modifier.semantics { invisibleToUser() } else Modifier)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress > 0f) {
        materialModifier.blur(CONTEXT_MENU_BLUR_RADIUS * progress)
    } else {
        materialModifier
    }
}

private const val CONTEXT_MENU_BACKDROP_ENTER_MS = 160
private const val CONTEXT_MENU_BACKDROP_EXIT_MS = 130
private const val CONTEXT_MENU_LIGHT_SCRIM_ALPHA = 0.14f
private const val CONTEXT_MENU_DARK_SCRIM_ALPHA = 0.20f
private val CONTEXT_MENU_BLUR_RADIUS = 12.dp
