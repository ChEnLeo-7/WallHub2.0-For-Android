package com.wallhub.android

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountCollectionCountIdentityTest {
    @Test
    fun `only an authenticated connected session has a count refresh identity`() {
        SteamSessionPhase.entries
            .filterNot { it == SteamSessionPhase.SIGNED_IN }
            .forEach { phase ->
                assertNull(
                    accountCollectionCountIdentity(
                        SteamSessionState(
                            phase = phase,
                            accountName = "account-a",
                            hasStoredSession = true,
                            isConnected = false,
                        ),
                    ),
                )
            }
        assertNull(
            accountCollectionCountIdentity(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNED_IN,
                    accountName = "account-a",
                    isConnected = false,
                ),
            ),
        )
        assertEquals(
            "account-a",
            accountCollectionCountIdentity(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNED_IN,
                    accountName = "Account-A",
                    isConnected = true,
                ),
            ),
        )
    }
}
