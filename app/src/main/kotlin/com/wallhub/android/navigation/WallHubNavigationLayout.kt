@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.floatingNavigationSystemInset
import com.wallhub.android.core.designsystem.wallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.wallHubStyle

private val FLOATING_NAVIGATION_SIDE_PADDING = 17.dp
private val FLOATING_NAVIGATION_VERTICAL_PADDING = WallHubSizeTokens.floatingNavigationOuterPadding
private val FLOATING_NAVIGATION_CONTAINER_PADDING = WallHubSizeTokens.floatingNavigationContainerPadding
private val FLOATING_NAVIGATION_ITEM_HORIZONTAL_INSET = 2.dp
private val FLOATING_NAVIGATION_ITEM_HEIGHT = WallHubSizeTokens.floatingNavigationItemHeight
private val FLOATING_NAVIGATION_ICON_SIZE = 22.dp
private val FLOATING_NAVIGATION_ICON_LABEL_SPACE = 2.dp
private val FLOATING_NAVIGATION_SHADOW = 6.dp

private data class MovableNavHostParams(
    val modifier: Modifier,
    val navController: NavHostController,
    val homeScrollRequest: Int,
    val homeResetRequest: Int,
    val discoverRefreshRequest: Int,
    val onHomeContextMenuActiveChanged: (Boolean) -> Unit,
    val onHomeBottomNavigationCollapsedChanged: (Boolean) -> Unit,
    val homeFloatingBottomBarAutoHideActive: Boolean,
    val animateTopLevelTransitions: Boolean,
)

@Composable
internal fun WallHubAdaptiveNavigationLayout(
    windowLayout: WallHubWindowLayout,
    isTopLevelDestination: Boolean,
    currentTopLevelDestination: TopLevelDestination?,
    homeContextMenuActive: Boolean,
    onHomeContextMenuActiveChanged: (Boolean) -> Unit,
    homeBottomNavigationCollapsed: Boolean,
    homeBottomBarAutoHideEnabled: Boolean,
    onHomeBottomNavigationCollapsedChanged: (Boolean) -> Unit,
    onNavigateTo: (TopLevelDestination) -> Unit,
    navController: NavHostController,
    homeScrollRequest: Int,
    homeResetRequest: Int,
    onHomeResetRequest: () -> Unit,
    discoverRefreshRequest: Int,
    fullscreenPlayback: Boolean,
    onFullscreenPlaybackChanged: (Boolean) -> Unit,
) {
    val navigationStyle = wallHubStyle()
    val showAdaptiveNavigation = shouldShowAdaptiveNavigation(isTopLevelDestination, fullscreenPlayback)
    val destinations = topLevelDestinations(includeDownloads = true)
    val movableHost = remember {
        movableContentOf<MovableNavHostParams> { params ->
            WallHubNavHost(
                modifier = params.modifier,
                navController = params.navController,
                homeScrollRequest = params.homeScrollRequest,
                homeResetRequest = params.homeResetRequest,
                discoverRefreshRequest = params.discoverRefreshRequest,
                onHomeContextMenuActiveChanged = params.onHomeContextMenuActiveChanged,
                onHomeBottomNavigationCollapsedChanged = params.onHomeBottomNavigationCollapsedChanged,
                homeFloatingBottomBarAutoHideActive = params.homeFloatingBottomBarAutoHideActive,
                homeFloatingNavigation = {},
                animateTopLevelTransitions = params.animateTopLevelTransitions,
                onFullscreenPlaybackChanged = onFullscreenPlaybackChanged,
            )
        }
    }
    val host: @Composable (Modifier, Boolean, Boolean) -> Unit = { modifier, floating, animate ->
        movableHost(
            MovableNavHostParams(
                modifier,
                navController,
                homeScrollRequest,
                homeResetRequest,
                discoverRefreshRequest,
                onHomeContextMenuActiveChanged,
                onHomeBottomNavigationCollapsedChanged,
                floating,
                animate,
            ),
        )
    }
    when (windowLayout) {
        WallHubWindowLayout.MEDIUM,
        WallHubWindowLayout.EXPANDED,
        -> {
            if (fullscreenPlayback) {
                host(Modifier.fillMaxSize(), false, false)
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    WallHubPadNavigationSidebar(
                    destinations = destinations,
                    currentDestination = currentTopLevelDestination,
                    expanded = true,
                    enabled = !homeContextMenuActive,
                    onExpandedChange = {},
                    onNavigateTo = onNavigateTo,
                    onHomeReset = {
                        if (currentTopLevelDestination == TopLevelDestination.HOME) {
                            onHomeResetRequest()
                        } else {
                            onNavigateTo(TopLevelDestination.HOME)
                        }
                    },
                )
                    host(Modifier.weight(1f).fillMaxHeight(), false, true)
                }
            }
        }

        WallHubWindowLayout.COMPACT -> {
            val showNavigation =
                showAdaptiveNavigation && shouldShowFloatingBottomNavigation(
                    isTopLevelDestination = isTopLevelDestination,
                    currentTopLevelDestination = currentTopLevelDestination,
                    autoHideEnabled = homeBottomBarAutoHideEnabled,
                    homeBottomNavigationCollapsed = homeBottomNavigationCollapsed,
                ) && !homeContextMenuActive
            if (navigationStyle.navigation.floating) {
                // The floating surface is an overlay: content keeps the full height so cards scroll
                // behind the pill, and `LocalWallHubOverlayBottomClearance` carries the space the
                // screens must reserve so the last row can clear it.
                val navigationInset = floatingNavigationSystemInset()
                val overlayClearance = wallHubOverlayBottomClearance(navigationInset, showNavigation)
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalWallHubOverlayBottomClearance provides overlayClearance,
                    ) {
                        host(
                            Modifier
                                .fillMaxSize()
                                .consumeWindowInsets(WindowInsets.navigationBars),
                            false,
                            true,
                        )
                    }
                    AnimatedVisibility(
                        visible = showNavigation,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter =
                            slideInVertically(initialOffsetY = { it }, animationSpec = tween(240)) +
                                fadeIn(animationSpec = tween(160)),
                        exit =
                            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) +
                                fadeOut(animationSpec = tween(120)),
                    ) {
                        WallHubFloatingNavigationBar(
                            destinations = destinations,
                            currentTopLevelDestination = currentTopLevelDestination,
                            onNavigateTo = onNavigateTo,
                        )
                    }
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showNavigation,
                            enter =
                                slideInVertically(initialOffsetY = { it }, animationSpec = tween(240)) +
                                    fadeIn(animationSpec = tween(160)),
                            exit =
                                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) +
                                    fadeOut(animationSpec = tween(120)),
                        ) {
                            StandardBottomNavigation(
                                destinations = destinations,
                                currentTopLevelDestination = currentTopLevelDestination,
                                onNavigateTo = onNavigateTo,
                            )
                        }
                    },
                ) { padding ->
                    host(
                        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                        false,
                        true,
                    )
                }
            }
        }
    }
}

/**
 * The floating bottom navigation: an M3 `Surface` pill that holds one selectable slot per
 * destination. The selected destination is a full-height capsule behind its icon and label, so the
 * indicator hugs both instead of the 64x32 icon-only box that `NavigationBarItem` draws. The
 * highlight is one shared capsule that slides between slots with the M3 Expressive spatial motion,
 * and the slots deliberately draw no ripple: a nav destination is a state switch, not an action.
 * The pill carries no border; the official elevation shadow separates it from the page.
 */
@Composable
internal fun WallHubFloatingNavigationBar(
    destinations: List<TopLevelDestination>,
    currentTopLevelDestination: TopLevelDestination?,
    onNavigateTo: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = floatingNavigationSystemInset())
                .padding(
                    horizontal = FLOATING_NAVIGATION_SIDE_PADDING,
                    vertical = FLOATING_NAVIGATION_VERTICAL_PADDING,
                ),
        shape = style.shapes.navigation,
        color = style.navigation.container,
        shadowElevation = FLOATING_NAVIGATION_SHADOW,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(FLOATING_NAVIGATION_CONTAINER_PADDING)
                    .height(FLOATING_NAVIGATION_ITEM_HEIGHT)
                    .selectableGroup(),
        ) {
            val slotWidth = maxWidth / destinations.size
            val selectedIndex = destinations.indexOf(currentTopLevelDestination)
            if (selectedIndex >= 0) {
                val indicatorOffset by
                    animateDpAsState(
                        targetValue = slotWidth * selectedIndex,
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
                        label = "floating-navigation-indicator",
                    )
                Box(
                    modifier =
                        Modifier
                            .offset(x = indicatorOffset)
                            .width(slotWidth)
                            .fillMaxHeight()
                            .padding(horizontal = FLOATING_NAVIGATION_ITEM_HORIZONTAL_INSET)
                            .clip(CircleShape)
                            .background(style.selection.container),
                )
            }
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    FloatingNavigationItem(
                        destination = destination,
                        selected = currentTopLevelDestination == destination,
                        onClick = { onNavigateTo(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FloatingNavigationItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val style = wallHubStyle()
    // M3 Expressive effects motion: the content tint cross-fades instead of cutting, and the role
    // comes from the style seam so accents and dark mode stay in step.
    val contentColor by
        animateColorAsState(
            targetValue = if (selected) style.selection.content else style.navigation.content,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
            label = "floating-navigation-content",
        )
    Column(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(FLOATING_NAVIGATION_ICON_SIZE),
        )
        Spacer(Modifier.height(FLOATING_NAVIGATION_ICON_LABEL_SPACE))
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StandardBottomNavigation(
    destinations: List<TopLevelDestination>,
    currentTopLevelDestination: TopLevelDestination?,
    onNavigateTo: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    NavigationBar(
        modifier = modifier,
        containerColor = style.navigation.container,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        destinations.forEach { destination ->
            val selected = currentTopLevelDestination == destination
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigateTo(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = label,
                    )
                },
                label = { Text(label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = style.navigation.selectedContent,
                        selectedTextColor = style.navigation.selectedContent,
                        indicatorColor = style.navigation.selectedContainer,
                    ),
            )
        }
    }
}

@Composable
internal fun WallHubPadNavigationSidebar(
    destinations: List<TopLevelDestination>,
    currentDestination: TopLevelDestination?,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNavigateTo: (TopLevelDestination) -> Unit,
    onHomeReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationStyle = wallHubStyle()
    NavigationRail(
        modifier = modifier.width(80.dp).fillMaxHeight(),
        containerColor = navigationStyle.navigation.railContainer,
        header = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = onHomeReset,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.wallhub_logo),
                        contentDescription = stringResource(R.string.home_reset),
                        modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.medium),
                    )
                }
            }
        },
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        destinations.forEach { destination ->
            val selected = currentDestination == destination
            val label = stringResource(destination.labelRes)
            NavigationRailItem(
                selected = selected,
                enabled = enabled,
                onClick = {
                    if (destination == TopLevelDestination.HOME && currentDestination == TopLevelDestination.HOME) {
                        onHomeReset()
                    } else {
                        onNavigateTo(destination)
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = label,
                    )
                },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors =
                    NavigationRailItemDefaults.colors(
                        selectedIconColor = navigationStyle.navigation.drawerSelectedContent,
                        selectedTextColor = navigationStyle.navigation.drawerSelectedContent,
                        indicatorColor = navigationStyle.navigation.selectedContainer,
                    ),
            )
        }
    }
}

internal fun shouldShowAdaptiveNavigation(
    isTopLevelDestination: Boolean,
    fullscreenPlayback: Boolean,
): Boolean = isTopLevelDestination && !fullscreenPlayback

internal fun shouldShowFloatingBottomNavigation(
    isTopLevelDestination: Boolean,
    currentTopLevelDestination: TopLevelDestination?,
    autoHideEnabled: Boolean,
    homeBottomNavigationCollapsed: Boolean,
): Boolean =
    isTopLevelDestination &&
        !(
            currentTopLevelDestination == TopLevelDestination.HOME &&
                autoHideEnabled &&
                homeBottomNavigationCollapsed
        )

@Composable
internal fun WallHubFloatingNavigationLayer(
    modalContentActive: Boolean,
    content: @Composable BoxScope.() -> Unit,
    navigation: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), content = content)
        Box(Modifier.fillMaxSize(), content = navigation)
    }
}
