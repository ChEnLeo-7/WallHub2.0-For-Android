package com.wallhub.android.data.steam

import com.wallhub.android.accountCollectionPrefetchIdentity
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountCollectionPrefetchLifecycleTest {
    @Test
    fun `persona updates retain one account prefetch identity`() {
        val signedIn = SteamSessionState(phase = SteamSessionPhase.SIGNED_IN, accountName = "Account")
        val personaUpdated = signedIn.copy(personaName = "Display name", avatarUrl = "https://example.test/avatar")

        assertEquals("account", accountCollectionPrefetchIdentity(signedIn))
        assertEquals(
            accountCollectionPrefetchIdentity(signedIn),
            accountCollectionPrefetchIdentity(personaUpdated),
        )
    }

    @Test
    fun `non authenticated phases do not prefetch account collections`() {
        assertNull(
            accountCollectionPrefetchIdentity(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNING_IN,
                    accountName = "account",
                    hasStoredSession = true,
                ),
            ),
        )
    }
}
