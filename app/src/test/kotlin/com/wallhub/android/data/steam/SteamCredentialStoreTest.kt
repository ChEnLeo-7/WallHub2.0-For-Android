package com.wallhub.android.data.steam

import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SteamCredentialStoreTest {
    @Test
    fun `missing encrypted session is distinct from unreadable session`() {
        val store =
            EncryptedSteamCredentialStore(
                FakeEncryptedStringStore(EncryptedStringReadResult.Missing),
            )

        assertEquals(SteamCredentialReadResult.Missing, store.read())
    }

    @Test
    fun `unreadable encrypted session is retained for a later login replacement`() {
        val encryptedStore =
            FakeEncryptedStringStore(
                EncryptedStringReadResult.Unreadable(IllegalStateException("key unavailable")),
            )
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertIs<SteamCredentialReadResult.Unreadable>(store.read())
        assertEquals(0, encryptedStore.clearCalls)
    }

    @Test
    fun `malformed encrypted session is retained instead of being cleared`() {
        val encryptedStore = FakeEncryptedStringStore(EncryptedStringReadResult.Value("malformed"))
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertIs<SteamCredentialReadResult.Unreadable>(store.read())
        assertEquals(0, encryptedStore.clearCalls)
    }

    @Test
    fun `valid encrypted session remains readable`() {
        val credential = PersistedSteamCredential(accountName = "account", refreshToken = "refresh")
        val encryptedStore =
            FakeEncryptedStringStore(
                EncryptedStringReadResult.Value(encodeSteamCredential(credential)),
            )
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertEquals(SteamCredentialReadResult.Value(credential), store.read())
    }

    private class FakeEncryptedStringStore(
        private val result: EncryptedStringReadResult,
    ) : EncryptedStringStore {
        var clearCalls = 0
            private set

        override fun read(): EncryptedStringReadResult = result

        override fun write(value: String) = Unit

        override fun clear() {
            clearCalls += 1
        }
    }
}
