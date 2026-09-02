package com.wallhub.android.data.steam

import android.content.Context
import bruhcollective.itaysonlab.ksteam.persistence.KsteamPersistenceDriver
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import org.json.JSONObject

/**
 * Keystore-encrypted persistence driver for the kSteam engine. kSteam stores refresh
 * tokens and session material here; the payload is encrypted at rest with the same
 * Android Keystore scheme WallHub already uses for its own credential store.
 */
internal class KsteamEncryptedPersistenceDriver private constructor(
    private val encryptedStore: AndroidKeystoreEncryptedStringStore,
) : KsteamPersistenceDriver {
    constructor(context: Context) : this(
        AndroidKeystoreEncryptedStringStore(
            context = context,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        ),
    )

    private val entries = JSONObject(readPayload())

    @Synchronized
    override fun getString(key: String): String? = entries.optString(key, "")

    @Synchronized
    override fun getLong(key: String): Long = entries.optLong(key, 0L)

    @Synchronized
    override fun getInt(key: String): Int = entries.optInt(key, 0)

    @Synchronized
    override fun set(
        key: String,
        value: String,
    ) {
        entries.put(key, value)
        persist()
    }

    @Synchronized
    override fun set(
        key: String,
        value: Long,
    ) {
        entries.put(key, value)
        persist()
    }

    @Synchronized
    override fun set(
        key: String,
        value: Int,
    ) {
        entries.put(key, value)
        persist()
    }

    @Synchronized
    override fun containsKey(key: String): Boolean = entries.has(key)

    @Synchronized
    override fun delete(vararg key: String) {
        key.forEach(entries::remove)
        persist()
    }

    private fun readPayload(): String =
        when (val result = encryptedStore.read()) {
            is com.wallhub.android.data.security.EncryptedStringReadResult.Value -> result.value
            else -> "{}"
        }

    private fun persist() {
        encryptedStore.write(entries.toString())
    }

    private companion object {
        const val PREFERENCES_NAME = "wallhub_ksteam_session"
        const val KEY_ALIAS = "wallhub_ksteam_session_payload"
    }
}
