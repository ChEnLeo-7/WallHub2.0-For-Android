package com.wallhub.android

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavDestination

internal fun wallHubEnterTransition(
    initialDestination: NavDestination,
    targetDestination: NavDestination,
    animateTopLevelTransitions: Boolean,
    popping: Boolean,
): EnterTransition =
    if (initialDestination.isTopLevelDestinationRoute() && targetDestination.isTopLevelDestinationRoute()) {
        if (animateTopLevelTransitions) {
            wallHubTopLevelEnterTransition(topLevelNavigationDirection(initialDestination, targetDestination))
        } else {
            EnterTransition.None
        }
    } else if (popping) {
        wallHubBackEnterTransition()
    } else {
        wallHubForwardEnterTransition()
    }

internal fun wallHubExitTransition(
    initialDestination: NavDestination,
    targetDestination: NavDestination,
    animateTopLevelTransitions: Boolean,
    popping: Boolean,
): ExitTransition =
    if (initialDestination.isTopLevelDestinationRoute() && targetDestination.isTopLevelDestinationRoute()) {
        if (animateTopLevelTransitions) {
            wallHubTopLevelExitTransition(topLevelNavigationDirection(initialDestination, targetDestination))
        } else {
            ExitTransition.None
        }
    } else if (popping) {
        wallHubBackExitTransition()
    } else {
        wallHubForwardExitTransition()
    }

private fun wallHubTopLevelEnterTransition(direction: Int): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { width -> direction * width },
        animationSpec = tween(TOP_LEVEL_TRANSITION_DURATION_MS, easing = NAVIGATION_EASING),
    )

private fun wallHubTopLevelExitTransition(direction: Int): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { width -> -direction * width },
        animationSpec = tween(TOP_LEVEL_TRANSITION_DURATION_MS, easing = NAVIGATION_EASING),
    )

private fun wallHubForwardEnterTransition(): EnterTransition =
    fadeIn(tween(SECONDARY_ENTER_DURATION_MS, easing = NAVIGATION_EASING)) +
        slideInHorizontally(
            initialOffsetX = { width -> width / SECONDARY_ENTER_OFFSET_DIVISOR },
            animationSpec = tween(SECONDARY_ENTER_DURATION_MS, easing = NAVIGATION_EASING),
        )

private fun wallHubForwardExitTransition(): ExitTransition =
    fadeOut(tween(SECONDARY_EXIT_DURATION_MS, easing = NAVIGATION_EASING)) +
        slideOutHorizontally(
            targetOffsetX = { width -> -width / SECONDARY_EXIT_OFFSET_DIVISOR },
            animationSpec = tween(SECONDARY_EXIT_DURATION_MS, easing = NAVIGATION_EASING),
        )

private fun wallHubBackEnterTransition(): EnterTransition =
    fadeIn(tween(SECONDARY_ENTER_DURATION_MS, easing = NAVIGATION_EASING)) +
        slideInHorizontally(
            initialOffsetX = { width -> -width / SECONDARY_EXIT_OFFSET_DIVISOR },
            animationSpec = tween(SECONDARY_ENTER_DURATION_MS, easing = NAVIGATION_EASING),
        )

private fun wallHubBackExitTransition(): ExitTransition =
    fadeOut(tween(SECONDARY_EXIT_DURATION_MS, easing = NAVIGATION_EASING)) +
        slideOutHorizontally(
            targetOffsetX = { width -> width / SECONDARY_ENTER_OFFSET_DIVISOR },
            animationSpec = tween(SECONDARY_EXIT_DURATION_MS, easing = NAVIGATION_EASING),
        )

internal const val BOTTOM_NAVIGATION_FADE_DURATION_MS = 180
internal const val BOTTOM_NAVIGATION_ICON_FADE_DURATION_MS = 160
private const val TOP_LEVEL_TRANSITION_DURATION_MS = 340
private const val SECONDARY_ENTER_DURATION_MS = 320
private const val SECONDARY_EXIT_DURATION_MS = 220
private const val SECONDARY_ENTER_OFFSET_DIVISOR = 9
private const val SECONDARY_EXIT_OFFSET_DIVISOR = 18
internal val NAVIGATION_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
