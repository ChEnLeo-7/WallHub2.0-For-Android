package com.wallhub.android.feature.home

import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopType

/**
 * Button actions for the expanded Home card panel.
 *
 * [primary] is the current Home default action and always owns the filled primary button. [typeAction]
 * is the type-appropriate secondary action (play for video, download otherwise) and is omitted when
 * it would duplicate the primary action. "Open workshop" is the lowest-priority action and is
 * omitted when it is already the primary action.
 */
internal data class HomePanelActions(
    val primary: HomeCardAction,
    val typeAction: HomeCardAction?,
    val showOpenWorkshop: Boolean,
)

internal fun homePanelActions(
    configuredAction: HomeCardAction,
    type: WorkshopType,
): HomePanelActions {
    val primary =
        if (configuredAction == HomeCardAction.PLAY_VIDEO && type != WorkshopType.VIDEO) {
            HomeCardAction.DOWNLOAD
        } else {
            configuredAction
        }
    val typeAction =
        when {
            type == WorkshopType.VIDEO && primary != HomeCardAction.PLAY_VIDEO -> HomeCardAction.PLAY_VIDEO
            type != WorkshopType.VIDEO && primary != HomeCardAction.DOWNLOAD -> HomeCardAction.DOWNLOAD
            else -> null
        }
    return HomePanelActions(
        primary = primary,
        typeAction = typeAction,
        showOpenWorkshop = primary != HomeCardAction.OPEN_STEAM,
    )
}
