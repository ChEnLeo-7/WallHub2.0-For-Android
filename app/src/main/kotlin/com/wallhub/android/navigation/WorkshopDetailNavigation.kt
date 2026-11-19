package com.wallhub.android

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/** Reject queued clicks as soon as their source leaves the top of the stack. */
internal fun NavHostController.navigateToWorkshopDetail(
    source: NavBackStackEntry,
    workshopId: Long,
) {
    if (currentBackStackEntry != source || source.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(WorkshopDetailDestination(workshopId)) {
        launchSingleTop = true
    }
}
