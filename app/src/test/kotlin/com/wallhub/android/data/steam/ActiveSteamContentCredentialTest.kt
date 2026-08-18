package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamContentCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActiveSteamContentCredentialTest {
    @Test
    fun activeCredentialCanBeSelectedWithoutReadingStorage() {
        val active = SteamContentCredential("active", "active-token")
        var storageRead = false

        val selected =
            selectSteamContentCredential(active, hasUsableSession = true) {
                storageRead = true
                SteamContentCredential("stored", "stored-token")
            }

        assertEquals(active, selected)
        assertFalse(storageRead)
    }

    @Test
    fun storedCredentialIsUsedWhenActiveSessionIsNotUsable() {
        val stored = SteamContentCredential("stored", "stored-token")

        val selected =
            selectSteamContentCredential(
                activeCredential = SteamContentCredential("stale", "stale-token"),
                hasUsableSession = false,
            ) { stored }

        assertEquals(stored, selected)
    }
}
