package com.wallhub.android.data.steam

import android.content.Context
import bruhcollective.itaysonlab.ksteam.models.SteamId
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

internal class EncryptedSteamCredentialStore(
    private val encryptedStore: EncryptedStringStore,
) {
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
    }

    @Synchronized
    fun clear() {
        encryptedStore.clear()
    }

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

internal data class SteamProfile(
    val displayName: String,
    val avatarUrl: String? = null,
)

/**
 * Extracts the SteamID64 from a Steam refresh token's JWT `sub` claim so legacy
 * (JavaSteam-era) credentials can be migrated into kSteam per-account storage.
 */
internal fun steamIdFromRefreshToken(token: String): SteamId? =
    runCatching {
        val payload = token.split('.').getOrNull(1).orEmpty()
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        val sub = org.json.JSONObject(json).optString("sub").orEmpty()
        sub.toULongOrNull()?.let(::SteamId)
    }.getOrNull()
