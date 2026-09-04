package com.wallhub.android.data.steam

import android.content.Context
import bruhcollective.itaysonlab.ksteam.models.SteamId
import bruhcollective.itaysonlab.ksteam.persistence.KsteamPersistenceDriver
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import com.wallhub.android.data.security.EncryptedStringReadResult
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
    override fun getString(key: String): String? = entries.optString(key, "").takeIf(String::isNotEmpty)

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

    @Synchronized
    override fun secureGetSteamIds(): List<SteamId> =
        entries
            .keys()
            .asSequence()
            .filter { it.startsWith(SECURE_KEY_PREFIX) }
            .map { it.removePrefix(SECURE_KEY_PREFIX).substringBefore(':') }
            .distinct()
            .map { SteamId(it.toULong()) }
            .toList()

    @Synchronized
    override fun secureGet(
        id: SteamId,
        key: String,
    ): String? = entries.optString(secureKey(id, key), "").takeIf(String::isNotEmpty)

    @Synchronized
    override fun secureSet(
        id: SteamId,
        key: String,
        value: String,
    ) {
        entries.put(secureKey(id, key), value)
        persist()
    }

    @Synchronized
    override fun secureSet(
        id: SteamId,
        vararg pairs: Pair<String, String>,
    ) {
        pairs.forEach { (key, value) -> entries.put(secureKey(id, key), value) }
        persist()
    }

    @Synchronized
    override fun secureDelete(
        id: SteamId,
        vararg key: String,
    ) {
        key.forEach { entries.remove(secureKey(id, it)) }
        persist()
    }

    @Synchronized
    override fun secureContainsKey(
        id: SteamId,
        key: String,
    ): Boolean = entries.has(secureKey(id, key))

    private fun secureKey(
        id: SteamId,
        key: String,
    ): String = "$SECURE_KEY_PREFIX${id.id}:$key"

    private fun readPayload(): String =
        kSteamPersistencePayload(encryptedStore.read())

    private fun persist() {
        encryptedStore.write(entries.toString())
    }

    private companion object {
        const val PREFERENCES_NAME = "wallhub_ksteam_session"
        const val KEY_ALIAS = "wallhub_ksteam_session_payload"
        const val SECURE_KEY_PREFIX = "secure:"
    }
}

internal fun kSteamPersistencePayload(result: EncryptedStringReadResult): String =
    when (result) {
        is EncryptedStringReadResult.Value -> result.value
        EncryptedStringReadResult.Missing -> "{}"
        is EncryptedStringReadResult.Unreadable ->
            throw KsteamSessionStorageException(result.cause)
    }

internal class KsteamSessionStorageException(
    cause: Throwable,
) : IllegalStateException("Unable to read kSteam session storage", cause)
