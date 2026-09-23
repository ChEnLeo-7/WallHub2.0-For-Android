package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * The two implementations of [SteamSessionRepository] this build can choose between.
 *
 * Both are the session owner for the whole process: the UI observes one flow and calls one set of
 * logon, guard, restore, and logout methods regardless of which is selected.
 */
internal enum class SteamSessionBackend {
    /** kSteam's `Account` handler owns the session: the default backend. */
    KSTEAM,

    /** The Steam Content SDK owns the session and its challenge handling. */
    STEAM_CONTENT_SDK,
}

/**
 * The single decision point for the session backend.
 *
 * `wallhub.useSdkSession` (surfaced as `BuildConfig.USE_SDK_SESSION`) stays off by default, so the
 * unchecked value selects the existing kSteam session behaviour byte for byte. Every caller obtains the
 * repository through [SteamSessionBackendRouter] rather than testing the flag itself, so a later parity
 * comparison and the deletion of the old arm touch this one place.
 */
internal fun selectSteamSessionBackend(useSdkSession: Boolean): SteamSessionBackend =
    if (useSdkSession) {
        SteamSessionBackend.STEAM_CONTENT_SDK
    } else {
        SteamSessionBackend.KSTEAM
    }

/**
 * Holds both session repositories and answers with the one the flag selects.
 *
 * The SDK arm is supplied as a factory so the dependency graph does not build it, and therefore does not
 * load the SDK's native library, unless the flag actually selects it. Only the selected backend is ever
 * invoked: a failure on the SDK backend never falls through to kSteam, and the unchecked default never
 * touches SDK state. [session] is bound to the selected backend once, because the flag is a build-time
 * value the process never changes.
 */
internal class SteamSessionBackendRouter(
    private val kSteamBackend: SteamSessionRepository,
    private val sdkBackend: () -> SteamSessionRepository,
    private val useSdkSession: Boolean,
) : SteamSessionRepository {
    val backend: SteamSessionBackend = selectSteamSessionBackend(useSdkSession)

    private val selected: SteamSessionRepository by lazy {
        when (backend) {
            SteamSessionBackend.KSTEAM -> kSteamBackend
            SteamSessionBackend.STEAM_CONTENT_SDK -> sdkBackend()
        }
    }

    override val session: StateFlow<SteamSessionState> = selected.session

    override fun restorePersistedSession() = selected.restorePersistedSession()

    override fun onAppForegrounded() = selected.onAppForegrounded()

    override fun onAppBackgrounded() = selected.onAppBackgrounded()

    override fun login(
        accountName: String,
        password: String,
    ) = selected.login(accountName, password)

    override fun submitSteamGuardCode(code: String) = selected.submitSteamGuardCode(code)

    override fun useManualSteamGuardFallback() = selected.useManualSteamGuardFallback()

    override fun logout() = selected.logout()
}
