package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SteamSessionBackendSelectionTest {
    @Test
    fun theDefaultFlagSelectsTheKSteamBackend() {
        assertEquals(
            SteamSessionBackend.KSTEAM,
            selectSteamSessionBackend(useSdkSession = false),
        )
    }

    @Test
    fun theOptInFlagSelectsTheSteamContentSdkBackend() {
        assertEquals(
            SteamSessionBackend.STEAM_CONTENT_SDK,
            selectSteamSessionBackend(useSdkSession = true),
        )
    }

    @Test
    fun theRouterServesOnlyTheSelectedBackend() {
        val kSteam = RecordingRepository()
        val sdk = RecordingRepository()
        var sdkResolutions = 0

        val unchecked = SteamSessionBackendRouter(kSteam, { sdkResolutions += 1; sdk }, useSdkSession = false)
        assertSame(kSteam.session, unchecked.session)
        unchecked.restorePersistedSession()
        unchecked.login("account", "value")
        unchecked.submitSteamGuardCode("value")
        unchecked.useManualSteamGuardFallback()
        unchecked.onAppForegrounded()
        unchecked.onAppBackgrounded()
        unchecked.logout()

        assertEquals(
            listOf("restore", "login", "guard", "manualFallback", "foregrounded", "backgrounded", "logout"),
            kSteam.calls,
        )
        assertEquals(emptyList<String>(), sdk.calls)
        assertEquals(0, sdkResolutions)
        assertEquals(SteamSessionBackend.KSTEAM, unchecked.backend)

        val optedIn = SteamSessionBackendRouter(kSteam, { sdkResolutions += 1; sdk }, useSdkSession = true)
        assertSame(sdk.session, optedIn.session)
        optedIn.login("account", "value")
        assertEquals(listOf("login"), sdk.calls)
        assertEquals(1, sdkResolutions)
        assertEquals(SteamSessionBackend.STEAM_CONTENT_SDK, optedIn.backend)
    }

    private class RecordingRepository : SteamSessionRepository {
        val calls = mutableListOf<String>()

        private val mutableSession = MutableStateFlow(SteamSessionState())
        override val session: StateFlow<SteamSessionState> = mutableSession.asStateFlow()

        override fun restorePersistedSession() {
            calls += "restore"
        }

        override fun login(
            accountName: String,
            password: String,
        ) {
            calls += "login"
        }

        override fun submitSteamGuardCode(code: String) {
            calls += "guard"
        }

        override fun useManualSteamGuardFallback() {
            calls += "manualFallback"
        }

        override fun onAppForegrounded() {
            calls += "foregrounded"
        }

        override fun onAppBackgrounded() {
            calls += "backgrounded"
        }

        override fun logout() {
            calls += "logout"
        }
    }
}
