package com.wallhub.android.feature.library

import com.wallhub.android.core.model.SteamSessionPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySignedInLoadPolicyTest {
    @Test
    fun `first signed-in observation loads even when restored UI already says signed in`() {
        assertTrue(
            shouldLoadLibraryForSignedInSession(
                previousPhase = SteamSessionPhase.SIGNED_IN,
                activeAccountName = null,
                observedAccountName = "account",
                refreshWhenSignedIn = false,
            ),
        )
    }

    @Test
    fun `stable observed account does not reload without a refresh request`() {
        assertFalse(
            shouldLoadLibraryForSignedInSession(
                previousPhase = SteamSessionPhase.SIGNED_IN,
                activeAccountName = "account",
                observedAccountName = "account",
                refreshWhenSignedIn = false,
            ),
        )
    }

    @Test
    fun `account changes and deferred refreshes both load`() {
        assertTrue(
            shouldLoadLibraryForSignedInSession(
                previousPhase = SteamSessionPhase.SIGNED_IN,
                activeAccountName = "first",
                observedAccountName = "second",
                refreshWhenSignedIn = false,
            ),
        )
        assertTrue(
            shouldLoadLibraryForSignedInSession(
                previousPhase = SteamSessionPhase.SIGNED_IN,
                activeAccountName = "account",
                observedAccountName = "account",
                refreshWhenSignedIn = true,
            ),
        )
    }
}
