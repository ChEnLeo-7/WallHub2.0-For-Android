package com.wallhub.android.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
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

internal class AndroidKeystoreEncryptedStringStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) : EncryptedStringStore {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): EncryptedStringReadResult {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val initializationVector = preferences.getString(KEY_INITIALIZATION_VECTOR, null)
        if (ciphertext == null && initializationVector == null) return EncryptedStringReadResult.Missing
        if (ciphertext == null || initializationVector == null) {
            return EncryptedStringReadResult.Unreadable(IllegalStateException("Encrypted value is incomplete"))
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(initializationVector, Base64.NO_WRAP)),
            )
            EncryptedStringReadResult.Value(
                String(
                    cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8,
                ),
            )
        } catch (error: Throwable) {
            EncryptedStringReadResult.Unreadable(error)
        }
    }

    @Synchronized
    override fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        check(
            preferences
                .edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_INITIALIZATION_VECTOR, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to persist encrypted value" }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear encrypted value" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
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
    }

    private companion object {
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_INITIALIZATION_VECTOR = "initialization_vector"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
