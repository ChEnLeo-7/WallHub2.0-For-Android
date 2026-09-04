package com.wallhub.android.data.steam

import com.wallhub.android.data.security.EncryptedStringReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KsteamEncryptedPersistenceDriverTest {
    @Test
    fun `missing payload starts with an empty persistence document`() {
        assertEquals("{}", kSteamPersistencePayload(EncryptedStringReadResult.Missing))
    }

    @Test
    fun `unreadable payload fails instead of being replaced with an empty document`() {
        assertFailsWith<KsteamSessionStorageException> {
            kSteamPersistencePayload(
                EncryptedStringReadResult.Unreadable(IllegalStateException("key unavailable")),
            )
        }
    }

    @Test
    fun `readable payload is returned unchanged`() {
        assertEquals(
            "{\"secure:1:refresh_token\":\"refresh\"}",
            kSteamPersistencePayload(
                EncryptedStringReadResult.Value("{\"secure:1:refresh_token\":\"refresh\"}"),
            ),
        )
    }
}
