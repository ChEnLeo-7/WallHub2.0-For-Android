package com.wallhub.android.data.settings

import android.content.Context
import android.util.Log
import com.wallhub.android.core.database.AppPreferencesStore
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal interface LegacySteamApiKeyStore {
    suspend fun read(): String

    suspend fun clear()
}

@Singleton
class SteamApiCredentialRepository internal constructor(
    private val secureStore: EncryptedStringStore,
    private val legacyStore: LegacySteamApiKeyStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        preferencesStore: AppPreferencesStore,
    ) : this(
        AndroidKeystoreEncryptedStringStore(
            context = context.applicationContext,
            preferencesName = ENCRYPTED_PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        ),
        object : LegacySteamApiKeyStore {
            override suspend fun read(): String = preferencesStore.readLegacySteamApiKey()

            override suspend fun clear() = preferencesStore.clearLegacySteamApiKey()
        },
    )

    private val state = MutableStateFlow<String?>(null)
    private val migrationMutex = Mutex()

    /** Whether the one migration pass has run; a read that failed is retried by the next collection. */
    private var migrated = false

    val apiKey: Flow<String> =
        flow {
            ensureMigrated()
            emitAll(state.filterNotNull())
        }

    suspend fun setApiKey(apiKey: String) {
        ensureMigrated()
        val normalized = apiKey.trim()
        migrationMutex.withLock {
            if (normalized.isEmpty()) {
                secureStore.clear()
                clearLegacyCopy()
            } else {
                secureStore.write(normalized)
                // The plaintext copy from an older build is not kept once an encrypted value exists: leaving
                // it behind is the whole point of the migration.
                clearLegacyCopy()
            }
            state.value = normalized
        }
    }

    /**
     * Resolves the stored key once, preferring the encrypted store and treating the plaintext DataStore entry
     * as a one-time migration input.
     *
     * The important rule is what happens when the encrypted value cannot be read: it is **kept**, nothing is
     * written, and the legacy plaintext value is *not* adopted. Clearing it destroyed the user's key over a
     * transient key-store failure, and falling back to the legacy entry then replaced the current key with an
     * older one; both were one code path. The flow answers empty so the screen can ask for the key again, and
     * because the pass is not marked done the read is retried on the next collection.
     */
    internal suspend fun ensureMigrated() {
        if (migrated) return
        migrationMutex.withLock {
            if (migrated) return
            val encrypted = secureStore.read()
            val encryptedValue = (encrypted as? EncryptedStringReadResult.Value)?.value?.trim()
            if (!encryptedValue.isNullOrEmpty()) {
                state.value = encryptedValue
                clearLegacyCopy()
                migrated = true
                return
            }
            if (encrypted is EncryptedStringReadResult.Unreadable) {
                runCatching {
                    Log.w(TAG, "Encrypted Steam API credential could not be read; it was kept", encrypted.cause)
                }
                state.value = ""
                return
            }

            val legacyValue = legacyStore.read().trim()
            if (legacyValue.isNotEmpty()) {
                secureStore.write(legacyValue)
                state.value = legacyValue
                // Only after the encrypted write succeeded: the plaintext entry is the only copy until then,
                // which is the one thing the previous ordering got right.
                clearLegacyCopy()
            } else {
                state.value = ""
            }
            migrated = true
        }
    }

    /**
     * Removes the plaintext DataStore copy, reporting a failure.
     *
     * A failed clear used to be skipped forever afterwards, because the migration only ran on the branch where
     * the encrypted value was empty. It is attempted whenever a readable encrypted value exists, so a
     * leftover plaintext key does not survive the next start.
     */
    private suspend fun clearLegacyCopy() {
        runCatching { legacyStore.clear() }
            .onFailure { error ->
                runCatching { Log.w(TAG, "The legacy plaintext Steam API credential could not be removed", error) }
            }
    }

    private companion object {
        const val TAG = "SteamApiCredential"
        const val ENCRYPTED_PREFERENCES_NAME = "wallhub_formal_steam_api_credential"
        const val KEY_ALIAS = "wallhub_formal_steam_api_key"
    }
}
