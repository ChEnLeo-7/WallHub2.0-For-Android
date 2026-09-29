package com.wallhub.android.data.steam

import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the content plane is told about the stored sign-in.
 *
 * The distinction this pins is the one that used to be lost: a store that holds nothing and a store that holds
 * something undecryptable are different answers, and only the first one means "sign in for the first time".
 */
class SteamContentSdkCredentialProviderTest {
    @Test
    fun `a stored credential is served`() =
        runTest {
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = "refresh-value")

            val loaded = providerFor(EncryptedStringReadResult.Value(encodeSteamCredential(credential)))

            val served = loaded.loadContentCredential()
            assertEquals("account", served?.accountName)
            assertEquals("refresh-value", served?.refreshToken)
        }

    @Test
    fun `an empty store answers no credential`() =
        runTest {
            assertNull(providerFor(EncryptedStringReadResult.Missing).loadContentCredential())
        }

    @Test
    fun `an unreadable store is reported rather than answered as no credential`() =
        runTest {
            val provider =
                providerFor(EncryptedStringReadResult.Unreadable(IllegalStateException("the key is gone")))

            val failure =
                assertFailsWith<SteamContentCredentialUnreadableException> { provider.loadContentCredential() }

            // The message names the real situation, so a device reading is not left saying that nothing was
            // stored while an undecryptable record sits in the store.
            assertTrue(failure.message.orEmpty().contains("could not be read"))
        }

    private fun providerFor(result: EncryptedStringReadResult): SteamContentSdkCredentialProvider =
        SteamContentSdkCredentialProvider(
            EncryptedSteamCredentialStore(FakeEncryptedStringStore(result)),
        )

    private class FakeEncryptedStringStore(
        private val result: EncryptedStringReadResult,
    ) : EncryptedStringStore {
        override fun read(): EncryptedStringReadResult = result

        override fun write(value: String) = Unit

        override fun clear() = Unit
    }
}
