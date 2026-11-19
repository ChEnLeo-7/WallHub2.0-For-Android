package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamCredentialTransactionTest {
    @Test
    fun `relogin keeps stored credential until replacement is committed`() {
        val store = FakeCredentialStore(hasCredential = true)

        store.startLogin()
        assertTrue(store.hasCredential)

        store.failLogin()
        assertTrue(store.hasCredential)

        store.commitSuccessfulLogin()
        assertTrue(store.hasCredential)
        assertEquals(1, store.replacements)
    }

    @Test
    fun `explicit logout clears stored credential`() {
        val store = FakeCredentialStore(hasCredential = true)

        store.logout()

        assertFalse(store.hasCredential)
    }

    @Test
    fun `older asynchronous clear finishes before a new login can commit`() {
        val store = FakeCredentialStore(hasCredential = true)
        val clear = store.scheduleLogoutClear()

        store.startLogin(clear)
        store.commitSuccessfulLogin()

        assertTrue(clear.completed)
        assertTrue(store.hasCredential)
        assertEquals(1, store.replacements)
    }

    @Test
    fun `stale expired-session clear cannot remove a newer login`() {
        val store = FakeCredentialStore(hasCredential = true)
        val staleClear = store.scheduleExpiredClear()

        store.commitSuccessfulLogin()
        staleClear.complete()

        assertTrue(store.hasCredential)
        assertFalse(staleClear.cleared)
    }

    private class FakeCredentialStore(
        var hasCredential: Boolean,
    ) {
        private var generation = 0
        var replacements = 0
            private set

        fun startLogin(clearBarrier: FakeClear? = null) {
            clearBarrier?.complete()
        }

        fun failLogin() = Unit

        fun commitSuccessfulLogin() {
            generation += 1
            hasCredential = true
            replacements += 1
        }

        fun logout() {
            hasCredential = false
        }

        fun scheduleLogoutClear() =
            FakeClear {
                hasCredential = false
                true
            }

        fun scheduleExpiredClear(): FakeClear {
            val clearGeneration = generation
            return FakeClear {
                if (clearGeneration == generation) {
                    hasCredential = false
                    true
                } else {
                    false
                }
            }
        }
    }

    private class FakeClear(
        private val clear: () -> Boolean,
    ) {
        var completed = false
            private set
        var cleared = false
            private set

        fun complete() {
            cleared = clear()
            completed = true
        }
    }
}
