package com.wallhub.android.data.settings

import com.wallhub.android.core.database.preferencesFallbackFor
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamApiCredentialRepositoryTest {
    @Test
    fun `migrates legacy key only after encrypted write succeeds`() =
        runTest {
            val secure = FakeEncryptedStringStore()
            val legacy = FakeLegacySteamApiKeyStore(" legacy-key ")
            val repository = SteamApiCredentialRepository(secure, legacy)

            assertEquals("legacy-key", repository.apiKey.first())
            assertEquals("legacy-key", secure.value)
            assertEquals(1, legacy.clearCount)
        }

    @Test
    fun `failed encrypted write preserves legacy key`() =
        runTest {
            val secure = FakeEncryptedStringStore(writeFailure = IOException("disk full"))
            val legacy = FakeLegacySteamApiKeyStore("legacy-key")
            val repository = SteamApiCredentialRepository(secure, legacy)

            assertFailsWith<IOException> { repository.ensureMigrated() }
            assertEquals("legacy-key", legacy.value)
            assertEquals(0, legacy.clearCount)
        }

    @Test
    fun `clear removes encrypted key and emits empty value`() =
        runTest {
            val secure = FakeEncryptedStringStore(initialValue = "saved-key")
            val repository = SteamApiCredentialRepository(secure, FakeLegacySteamApiKeyStore())

            assertEquals("saved-key", repository.apiKey.first())
            repository.setApiKey("  ")

            assertEquals(null, secure.value)
            assertEquals("", repository.apiKey.first())
        }

    @Test
    fun `an unreadable encrypted value is kept rather than cleared`() =
        runTest {
            val secure = FakeEncryptedStringStore(readFailure = IOException("cipher failed"))
            val repository = SteamApiCredentialRepository(secure, FakeLegacySteamApiKeyStore())

            assertEquals("", repository.apiKey.first())
            // The record is still there: a transient key-store failure must not destroy the user's key, and the
            // screen can simply ask for it again.
            assertEquals(0, secure.clearCount)
        }

    @Test
    fun `an unreadable encrypted value does not adopt the legacy plaintext key`() =
        runTest {
            val secure = FakeEncryptedStringStore(readFailure = IOException("cipher failed"))
            val legacy = FakeLegacySteamApiKeyStore("legacy-key")
            val repository = SteamApiCredentialRepository(secure, legacy)

            assertEquals("", repository.apiKey.first())
            // Neither adopted nor destroyed: adopting it silently replaced the current key with an older one,
            // and destroying it removed the only readable copy left.
            assertEquals("legacy-key", legacy.value)
            assertEquals(0, legacy.clearCount)
            assertNull(secure.value)
        }

    @Test
    fun `a readable encrypted value removes a leftover legacy plaintext key`() =
        runTest {
            val secure = FakeEncryptedStringStore(initialValue = "saved-key")
            val legacy = FakeLegacySteamApiKeyStore("legacy-key")
            val repository = SteamApiCredentialRepository(secure, legacy)

            assertEquals("saved-key", repository.apiKey.first())

            // A failed clear used to be skipped forever afterwards; the plaintext copy does not survive the
            // next start any more.
            assertEquals(1, legacy.clearCount)
        }

    @Test
    fun `clearing the key also removes the legacy plaintext copy`() =
        runTest {
            val secure = FakeEncryptedStringStore(initialValue = "saved-key")
            val legacy = FakeLegacySteamApiKeyStore("legacy-key")
            val repository = SteamApiCredentialRepository(secure, legacy)

            repository.setApiKey("  ")

            assertNull(secure.value)
            // Cleared by the migration pass and again by the write; what matters is that no plaintext copy is
            // left behind, not how many times the removal was asked for.
            assertEquals("", legacy.value)
            assertTrue(legacy.clearCount > 0)
        }

    @Test
    fun `preferences string representation redacts api key`() {
        val rendered = AppPreferences(steamApiKey = "super-secret").toString()

        assertFalse("super-secret" in rendered)
        assertFalse("steamApiKey=super-secret" in rendered)
    }

    @Test
    fun `preference IO failure recovers with empty preferences`() {
        assertEquals(0, preferencesFallbackFor(IOException("read failed")).asMap().size)
        assertFailsWith<IllegalStateException> {
            preferencesFallbackFor(IllegalStateException("programming failure"))
        }
    }

    @Test
    fun `proxy URLs reject credentials and unrelated URL components`() {
        assertEquals(true, isSupportedDownloadProxyUrl("socks5://127.0.0.1:1080"))
        assertFalse(isSupportedDownloadProxyUrl("http://user:password@proxy.example:8080"))
        assertFalse(isSupportedDownloadProxyUrl("http://proxy.example:8080/path"))
        assertFalse(isSupportedDownloadProxyUrl("http://proxy.example:8080?token=secret"))
    }
}

private class FakeEncryptedStringStore(
    initialValue: String? = null,
    private val readFailure: Throwable? = null,
    private val writeFailure: Throwable? = null,
) : EncryptedStringStore {
    var value: String? = initialValue
    var clearCount: Int = 0

    override fun read(): EncryptedStringReadResult =
        readFailure?.let(EncryptedStringReadResult::Unreadable)
            ?: value?.let(EncryptedStringReadResult::Value)
            ?: EncryptedStringReadResult.Missing

    override fun write(value: String) {
        writeFailure?.let { throw it }
        this.value = value
    }

    override fun clear() {
        clearCount += 1
        value = null
    }
}

private class FakeLegacySteamApiKeyStore(
    var value: String = "",
) : LegacySteamApiKeyStore {
    var clearCount: Int = 0

    override suspend fun read(): String = value

    override suspend fun clear() {
        clearCount += 1
        value = ""
    }
}
