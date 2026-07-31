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
            } else {
                secureStore.write(normalized)
            }
            state.value = normalized
        }
    }

    internal suspend fun ensureMigrated() {
        if (state.value != null) return
        migrationMutex.withLock {
            if (state.value != null) return
            val encryptedValue =
                when (val encrypted = secureStore.read()) {
                    EncryptedStringReadResult.Missing -> null
                    is EncryptedStringReadResult.Value -> encrypted.value.trim()
                    is EncryptedStringReadResult.Unreadable -> {
                        runCatching {
                            Log.w(TAG, "Encrypted Steam API credential could not be read", encrypted.cause)
                        }
                        secureStore.clear()
                        null
                    }
                }
            if (!encryptedValue.isNullOrEmpty()) {
                state.value = encryptedValue
                return
            }

            val legacyValue = legacyStore.read().trim()
            if (legacyValue.isNotEmpty()) {
                secureStore.write(legacyValue)
                legacyStore.clear()
            }
            state.value = legacyValue
        }
    }

    private companion object {
        const val TAG = "SteamApiCredential"
        const val ENCRYPTED_PREFERENCES_NAME = "wallhub_formal_steam_api_credential"
        const val KEY_ALIAS = "wallhub_formal_steam_api_key"
    }
}
