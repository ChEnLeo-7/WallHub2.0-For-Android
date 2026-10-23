package com.wallhub.android.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface EncryptedStringReadResult {
    data object Missing : EncryptedStringReadResult

    data class Value(
        val value: String,
    ) : EncryptedStringReadResult

    data class Unreadable(
        val cause: Throwable,
    ) : EncryptedStringReadResult
}

internal interface EncryptedStringStore {
    fun read(): EncryptedStringReadResult

    fun write(value: String)

    fun clear()
}

/** The two stored halves of one encrypted value, still encoded. */
internal interface CipherTextRecordStore {
    fun ciphertext(): String?

    fun initializationVector(): String?

    /** Commits both halves as one record; throws when they could not be made durable. */
    fun write(
        ciphertext: String,
        initializationVector: String,
    )

    /** Removes the record; throws when it could not be removed. */
    fun clear()
}

/** The one AES key this store encrypts with, as the platform's key store holds it. */
internal interface SecretKeyAccess {
    fun existing(): SecretKey?

    fun create(): SecretKey

    fun delete()
}

/**
 * One secret, encrypted with a hardware-backed AES-256-GCM key and stored beside its initialization vector.
 *
 * The Android key store and the preferences file are reached through [SecretKeyAccess] and
 * [CipherTextRecordStore], which is what lets the whole of this class - the record shape, the IV handling and
 * the recovery rules - be exercised on the JVM with real AES-GCM. [read] and [write] are what the app uses.
 */
internal class AndroidKeystoreEncryptedStringStore
    internal constructor(
        private val records: CipherTextRecordStore,
        private val keys: SecretKeyAccess,
    ) : EncryptedStringStore {
    constructor(
        context: Context,
        preferencesName: String,
        keyAlias: String,
    ) : this(
        SharedPreferencesCipherTextRecordStore(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        ),
        AndroidKeyStoreSecretKeyAccess(keyAlias),
    )

    @Synchronized
    override fun read(): EncryptedStringReadResult {
        val ciphertext = records.ciphertext()
        val initializationVector = records.initializationVector()
        if (ciphertext == null && initializationVector == null) return EncryptedStringReadResult.Missing
        if (ciphertext == null || initializationVector == null) {
            return EncryptedStringReadResult.Unreadable(IllegalStateException("Encrypted value is incomplete"))
        }
        return try {
            val key = keys.existing() ?: error("Encrypted value key is missing")
            EncryptedStringReadResult.Value(decrypt(key, ciphertext, initializationVector))
        } catch (error: Throwable) {
            // A record that exists but cannot be read is kept, never cleared: the cause can be a key that is
            // temporarily unavailable, and the caller - not this store - decides what that means.
            EncryptedStringReadResult.Unreadable(error)
        }
    }

    @Synchronized
    override fun write(value: String) {
        val existing = keys.existing()
        if (existing != null) {
            // A stale/unusable alias can survive after its preferences record has already disappeared. Probe the
            // key itself before relying on record readability, otherwise that empty-store case can never heal.
            if (records.ciphertext() == null && records.initializationVector() == null && !canEncrypt(existing)) {
                keys.delete()
                encryptAndStore(value, keys.create())
                return
            }
            val failure = runCatching { encryptAndStore(value, existing) }.exceptionOrNull()
            if (failure == null) return
            // Two failures wear the same shape here and only one of them may replace the key. A key the cipher
            // cannot be set up with is unusable, and a record encrypted under it is therefore already
            // unreadable, so replacing it recovers the store. A record that could not be committed, or a key
            // store hiccup over a record that still decrypts, leaves a readable secret behind: deleting the
            // key there would destroy it, so the failure is reported instead.
            if (failure is GeneralSecurityException && !isStoredRecordReadable(existing)) {
                keys.delete()
                encryptAndStore(value, keys.create())
                return
            }
            throw failure
        }
        encryptAndStore(value, keys.create())
    }

    @Synchronized
    override fun clear() {
        records.clear()
    }

    private fun canEncrypt(key: SecretKey): Boolean =
        runCatching {
            Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
        }.isSuccess

    private fun isStoredRecordReadable(key: SecretKey): Boolean {
        val ciphertext = records.ciphertext() ?: return true
        val initializationVector = records.initializationVector() ?: return false
        return runCatching { decrypt(key, ciphertext, initializationVector) }.isSuccess
    }

    private fun decrypt(
        key: SecretKey,
        ciphertext: String,
        initializationVector: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(initializationVector)),
        )
        return String(cipher.doFinal(decode(ciphertext)), StandardCharsets.UTF_8)
    }

    private fun encryptAndStore(
        value: String,
        key: SecretKey,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        // One commit for both halves, so a record is never a new ciphertext beside an old IV.
        records.write(
            ciphertext = encode(encrypted),
            initializationVector = encode(cipher.iv),
        )
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128

        /**
         * Standard base64 without line breaks: the same alphabet and shape the Android encoder produced, so a
         * record written by an older build stays readable. It is `java.util.Base64` rather than
         * `android.util.Base64` because the latter is a stub under a JVM unit test, which would put the one
         * class that handles the secret outside the reach of the test suite.
         */
        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}

/** The record half, over the preferences file the app already uses for this secret. */
private class SharedPreferencesCipherTextRecordStore(
    private val preferences: SharedPreferences,
) : CipherTextRecordStore {
    override fun ciphertext(): String? = preferences.getString(KEY_CIPHERTEXT, null)

    override fun initializationVector(): String? = preferences.getString(KEY_INITIALIZATION_VECTOR, null)

    override fun write(
        ciphertext: String,
        initializationVector: String,
    ) {
        check(
            preferences
                .edit()
                .putString(KEY_CIPHERTEXT, ciphertext)
                .putString(KEY_INITIALIZATION_VECTOR, initializationVector)
                .commit(),
        ) { "Unable to persist encrypted value" }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear encrypted value" }
    }

    private companion object {
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_INITIALIZATION_VECTOR = "initialization_vector"
    }
}

/** The key half, over the platform key store. */
private class AndroidKeyStoreSecretKeyAccess(
    private val keyAlias: String,
) : SecretKeyAccess {
    override fun existing(): SecretKey? {
        val keyStore = keyStore()
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    override fun create(): SecretKey =
        KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()

    override fun delete() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
