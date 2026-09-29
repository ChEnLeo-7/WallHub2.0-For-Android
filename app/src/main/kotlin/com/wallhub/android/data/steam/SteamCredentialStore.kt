package com.wallhub.android.data.steam

import android.content.Context
import com.wallhub.android.data.security.AndroidKeystoreEncryptedStringStore
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import java.util.Base64

internal data class PersistedSteamCredential(
    val accountName: String,
    val refreshToken: String,
    val personaName: String? = null,
    val avatarUrl: String? = null,
)

internal sealed interface SteamCredentialReadResult {
    data object Missing : SteamCredentialReadResult

    data class Value(
        val credential: PersistedSteamCredential,
    ) : SteamCredentialReadResult

    data class Unreadable(
        val cause: Throwable,
    ) : SteamCredentialReadResult
}

/**
 * The one durable copy of the account this install stays signed in as.
 *
 * Every mutation is a compare-and-replace under this instance's own lock. Callers outside cannot make a
 * read/compare/write atomic by locking around three calls, and the two writers that exist - an attempt
 * that just authenticated, and a clear issued for a credential the service refused - can interleave, so a
 * stale clear must be able to say *which* state it decided against and be refused when that state has
 * moved on. [generation] is that state: it advances on every write and every successful clear.
 */
internal class EncryptedSteamCredentialStore(
    private val encryptedStore: EncryptedStringStore,
) {
    private var generation = 0L

    constructor(context: Context) : this(
        AndroidKeystoreEncryptedStringStore(
            context = context,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        ),
    )

    @Synchronized
    fun read(): SteamCredentialReadResult =
        when (val result = encryptedStore.read()) {
            EncryptedStringReadResult.Missing -> SteamCredentialReadResult.Missing
            is EncryptedStringReadResult.Unreadable -> SteamCredentialReadResult.Unreadable(result.cause)
            is EncryptedStringReadResult.Value ->
                runCatching { decodeSteamCredential(result.value) }
                    .fold(
                        onSuccess = { credential -> SteamCredentialReadResult.Value(credential) },
                        onFailure = { error -> SteamCredentialReadResult.Unreadable(error) },
                    )
        }

    @Synchronized
    fun load(): PersistedSteamCredential? =
        (read() as? SteamCredentialReadResult.Value)?.credential

    @Synchronized
    fun save(credential: PersistedSteamCredential) {
        require(credential.accountName.isNotBlank()) { "Steam account name is required" }
        require(credential.refreshToken.isNotBlank()) { "Steam refresh token is required" }
        encryptedStore.write(encodeSteamCredential(credential))
        generation += 1
    }

    /**
     * Writes [credential] unless the store already holds that same refresh token.
     *
     * The compare runs inside this store's lock, so a clear between a caller's read and its write can no
     * longer be reverted by the write, and two writers cannot both decide they are changing the value.
     * Returns whether the store holds [credential]'s token afterwards; a failure to write throws, as
     * [save] does, and the caller reports it.
     */
    @Synchronized
    fun saveIfChanged(credential: PersistedSteamCredential): Boolean {
        val stored = (read() as? SteamCredentialReadResult.Value)?.credential?.refreshToken
        val unchanged = stored != null && stored == credential.refreshToken
        if (!unchanged) save(credential)
        return true
    }

    /** Clears the stored credential. Throws when the underlying store could not be cleared. */
    @Synchronized
    fun clear() {
        encryptedStore.clear()
        generation += 1
    }

    /**
     * Clears only when nothing has been written since [generationAtDecision].
     *
     * Used by a decision taken against one specific stored credential - "the service refused this token" -
     * so that a newer credential written while that decision was in flight is not the one that gets wiped.
     * Returns whether the store is empty *and* this call is what emptied it, or that it refused because the
     * state had moved on.
     */
    @Synchronized
    fun clearIfUnchanged(generationAtDecision: Long): Boolean {
        if (generation != generationAtDecision) return false
        clear()
        return true
    }

    /** The value a caller captures before deciding that the credential it read should be cleared. */
    @Synchronized
    fun generation(): Long = generation

    private companion object {
        const val PREFERENCES_NAME = "wallhub_formal_steam_session"
        const val KEY_ALIAS = "wallhub_formal_steam_refresh_token"
    }
}

private fun String.encodeRecordField(): String =
    Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))

private fun String?.encodeNullableRecordField(): String = this?.encodeRecordField().orEmpty()

private fun String.decodeRecordField(): String =
    String(Base64.getDecoder().decode(this), Charsets.UTF_8)

private fun String?.decodeNullableRecordField(): String? =
    this?.takeIf(String::isNotEmpty)?.decodeRecordField()?.takeIf(String::isNotBlank)

internal fun encodeSteamCredential(credential: PersistedSteamCredential): String =
    listOf(
        STEAM_CREDENTIAL_RECORD_VERSION,
        credential.accountName.encodeRecordField(),
        credential.refreshToken.encodeRecordField(),
        credential.personaName.encodeNullableRecordField(),
        credential.avatarUrl.encodeNullableRecordField(),
    ).joinToString(STEAM_CREDENTIAL_RECORD_SEPARATOR)

internal fun decodeSteamCredential(payload: String): PersistedSteamCredential {
    val fields = payload.split(STEAM_CREDENTIAL_RECORD_SEPARATOR)
    return when (fields.firstOrNull()) {
        STEAM_CREDENTIAL_LEGACY_RECORD_VERSION -> {
            require(fields.size == 3 && fields[1].isNotBlank() && fields[2].isNotBlank())
            PersistedSteamCredential(accountName = fields[1], refreshToken = fields[2])
        }

        STEAM_CREDENTIAL_RECORD_VERSION -> {
            require(fields.size == 5)
            PersistedSteamCredential(
                accountName = fields[1].decodeRecordField().also { require(it.isNotBlank()) },
                refreshToken = fields[2].decodeRecordField().also { require(it.isNotBlank()) },
                personaName = fields[3].decodeNullableRecordField(),
                avatarUrl = fields[4].decodeNullableRecordField(),
            )
        }

        else -> throw IllegalArgumentException("Unsupported Steam credential record")
    }
}

private const val STEAM_CREDENTIAL_LEGACY_RECORD_VERSION = "v1"
private const val STEAM_CREDENTIAL_RECORD_VERSION = "v2"
private const val STEAM_CREDENTIAL_RECORD_SEPARATOR = "\u001F"

/** Converts a Steam avatar hash into the matching CDN URL. */
internal fun ByteArray.toSteamAvatarUrl(): String? {
    if (isEmpty() || all { byte -> byte.toInt() == 0 }) return null
    val hash = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "https://avatars.fastly.steamstatic.com/${hash}_medium.jpg"
}

/**
 * Converts a Steam avatar hash the service reported as text into the matching CDN URL.
 *
 * The SDK hands an avatar across as the hash and never as a URL, and the player family reports that hash as
 * text, so this is where every such URL is built rather than each call site spelling one out.
 */
internal fun String?.toSteamAvatarUrl(): String? =
    this?.trim()?.takeIf(String::isNotBlank)?.let { hash ->
        "https://avatars.fastly.steamstatic.com/${hash}_medium.jpg"
    }

internal data class SteamProfile(
    val displayName: String,
    val avatarUrl: String? = null,
)
