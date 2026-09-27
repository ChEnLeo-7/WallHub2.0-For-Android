package com.wallhub.android.data.steam

import android.content.Context
import com.wallhub.android.R

/**
 * The user-facing text the SDK session backend publishes with each phase.
 *
 * The backend takes this catalogue rather than a `Context` so its state machine stays a JVM unit-test
 * subject: a test supplies fixed strings and asserts phases, not Android resources. Every string is the
 * app's existing session vocabulary, so the login UI renders the SDK backend exactly as it renders kSteam.
 */
internal interface SteamContentSdkSessionMessages {
    val restoringSession: String

    val connecting: String

    val requestingLogin: String

    val enterCredentials: String

    val enterGuardCode: String

    val confirmMobile: String

    val codeSubmitted: String

    val signedIn: String

    val signedOut: String

    val credentialsExpired: String

    val sessionStorageUnavailable: String

    val restoreFailed: String

    val loginTimeout: String

    val unavailable: String

    val authRejected: String

    val networkFailed: String

    /** Published when an established connection was lost and could not be rebuilt from the stored token. */
    val sessionConnectionLost: String

    val challengeUnsupported: String

    val noGuardRequest: String

    val noManualLogin: String
}

/** The catalogue backed by the app's own string resources. */
internal class AndroidSteamContentSdkSessionMessages(
    private val context: Context,
) : SteamContentSdkSessionMessages {
    override val restoringSession: String
        get() = context.getString(R.string.backend_steam_restoring_session)

    override val connecting: String
        get() = context.getString(R.string.backend_steam_connecting)

    override val requestingLogin: String
        get() = context.getString(R.string.backend_steam_requesting_login)

    override val enterCredentials: String
        get() = context.getString(R.string.backend_steam_enter_credentials)

    override val enterGuardCode: String
        get() = context.getString(R.string.backend_steam_enter_guard_code)

    override val confirmMobile: String
        get() = context.getString(R.string.backend_steam_confirm_mobile)

    override val codeSubmitted: String
        get() = context.getString(R.string.backend_steam_code_submitted)

    override val signedIn: String
        get() = context.getString(R.string.backend_steam_sdk_session_only)

    override val signedOut: String
        get() = context.getString(R.string.backend_steam_signed_out)

    override val credentialsExpired: String
        get() = context.getString(R.string.backend_steam_credentials_expired)

    override val sessionStorageUnavailable: String
        get() = context.getString(R.string.backend_steam_session_storage_unavailable)

    override val restoreFailed: String
        get() = context.getString(R.string.backend_steam_restore_failed)

    override val loginTimeout: String
        get() = context.getString(R.string.backend_steam_login_timeout)

    override val unavailable: String
        get() = context.getString(R.string.backend_steam_sdk_session_unavailable)

    override val authRejected: String
        get() = context.getString(R.string.backend_steam_auth_rejected)

    override val networkFailed: String
        get() = context.getString(R.string.backend_steam_network_failed)

    override val sessionConnectionLost: String
        get() = context.getString(R.string.backend_steam_session_connection_lost)

    override val challengeUnsupported: String
        get() = context.getString(R.string.backend_steam_challenge_unsupported)

    override val noGuardRequest: String
        get() = context.getString(R.string.backend_steam_no_guard_request)

    override val noManualLogin: String
        get() = context.getString(R.string.backend_steam_no_manual_login)
}
