package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.WorkshopSort
import java.util.concurrent.CancellationException

/** Public reads never wait for authentication; account-scoped reads require the signed-in CM session. */
internal enum class WorkshopSessionAccess {
    PUBLIC,
    AUTHENTICATED_ACCOUNT,
}

internal fun workshopSessionAccessForSort(sort: WorkshopSort): WorkshopSessionAccess =
    when (sort) {
        WorkshopSort.FRIENDS_FAVORITES,
        WorkshopSort.FRIENDS_CREATED,
        -> WorkshopSessionAccess.AUTHENTICATED_ACCOUNT
        else -> WorkshopSessionAccess.PUBLIC
    }

internal suspend fun awaitWorkshopSession(
    access: WorkshopSessionAccess,
    awaitAuthenticated: suspend () -> Unit,
) {
    if (access == WorkshopSessionAccess.AUTHENTICATED_ACCOUNT) awaitAuthenticated()
}

/**
 * Converts only the SDK's typed provider/session unavailability into the nullable public-read contract.
 * Cancellation always propagates; account collection callers deliberately do not use this helper.
 */
internal inline fun <T> runCatchingUnavailable(block: () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalStateException) {
        Log.w(CM_GATEWAY_TAG, "workshop read unavailable: ${error.message}")
        null
    }

private const val CM_GATEWAY_TAG = "WallHubSdkWorkshop"
