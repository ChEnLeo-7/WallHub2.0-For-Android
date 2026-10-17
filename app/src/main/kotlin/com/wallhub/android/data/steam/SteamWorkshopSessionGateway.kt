package com.wallhub.android.data.steam

import android.util.Log
import java.util.concurrent.CancellationException

/** Public reads never wait for authentication; account-scoped reads require the signed-in CM session. */
internal enum class WorkshopSessionAccess {
    PUBLIC,
    AUTHENTICATED_ACCOUNT,
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
