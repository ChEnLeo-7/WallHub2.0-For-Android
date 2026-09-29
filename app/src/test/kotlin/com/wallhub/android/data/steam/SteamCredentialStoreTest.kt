package com.wallhub.android.data.steam

import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a save that changes nothing does not rewrite the store`() {
        val credential = PersistedSteamCredential(accountName = "account", refreshToken = "refresh")
        val encryptedStore =
            FakeEncryptedStringStore(EncryptedStringReadResult.Value(encodeSteamCredential(credential)))
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertTrue(store.saveIfChanged(credential))
        assertEquals(0, encryptedStore.writeCalls)
        assertEquals(credential, store.load())
    }

    @Test
    fun `a save with a new token replaces the stored credential`() {
        val stored = PersistedSteamCredential(accountName = "account", refreshToken = "old-refresh")
        val rotated = PersistedSteamCredential(accountName = "account", refreshToken = "new-refresh")
        val encryptedStore =
            FakeEncryptedStringStore(EncryptedStringReadResult.Value(encodeSteamCredential(stored)))
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertTrue(store.saveIfChanged(rotated))
        assertEquals(1, encryptedStore.writeCalls)
        assertEquals(rotated, store.load())
    }

    @Test
    fun `a stale clear cannot remove a credential written since`() {
        val stored = PersistedSteamCredential(accountName = "account", refreshToken = "old-refresh")
        val loggedOn = PersistedSteamCredential(accountName = "account", refreshToken = "logged-on")
        val encryptedStore =
            FakeEncryptedStringStore(EncryptedStringReadResult.Value(encodeSteamCredential(stored)))
        val store = EncryptedSteamCredentialStore(encryptedStore)
        // The decision "this stored credential was refused, clear it" is taken against the value read here.
        val generationAtDecision = store.generation()

        store.save(loggedOn)
        val cleared = store.clearIfUnchanged(generationAtDecision)

        // The credential the service refused is gone already; what must survive is the one that replaced it.
        assertFalse(cleared)
        assertEquals(0, encryptedStore.clearCalls)
        assertEquals(loggedOn, store.load())
    }

    @Test
    fun `a clear tied to the current state empties the store`() {
        val stored = PersistedSteamCredential(accountName = "account", refreshToken = "refresh")
        val encryptedStore =
            FakeEncryptedStringStore(EncryptedStringReadResult.Value(encodeSteamCredential(stored)))
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertTrue(store.clearIfUnchanged(store.generation()))
        assertEquals(1, encryptedStore.clearCalls)
        assertEquals(SteamCredentialReadResult.Missing, store.read())
        assertNull(store.load())
    }

    @Test
    fun `a failed clear keeps the stored credential`() {
        val credential = PersistedSteamCredential(accountName = "account", refreshToken = "refresh")
        val encryptedStore =
            FakeEncryptedStringStore(EncryptedStringReadResult.Value(encodeSteamCredential(credential)))
        encryptedStore.clearFailure = IllegalStateException("commit failed")
        val store = EncryptedSteamCredentialStore(encryptedStore)

        assertFailsWith<IllegalStateException> { store.clear() }

        // A clear that threw is a clear that did not happen, and the caller publishes on that distinction.
        assertEquals(credential, store.load())
    }

    private class FakeEncryptedStringStore(
        private var result: EncryptedStringReadResult,
    ) : EncryptedStringStore {
        var clearCalls = 0
            private set
        var writeCalls = 0
            private set
        var clearFailure: Throwable? = null

        override fun read(): EncryptedStringReadResult = result

        override fun write(value: String) {
            writeCalls += 1
            result = EncryptedStringReadResult.Value(value)
        }

        override fun clear() {
            clearFailure?.let { throw it }
            clearCalls += 1
            result = EncryptedStringReadResult.Missing
        }
    }
}
