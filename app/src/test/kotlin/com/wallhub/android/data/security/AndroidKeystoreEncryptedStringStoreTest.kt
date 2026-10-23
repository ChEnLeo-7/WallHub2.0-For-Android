package com.wallhub.android.data.security

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The encrypted-secret store, exercised with real AES-GCM over the two seams the Android key store and the
 * preferences file sit behind.
 *
 * This is the class that holds the account, and it had no test at all: everything asserted here is a property
 * a wrong edit could quietly break - a torn record must not read as "no secret", a record under another key
 * must not read as "no secret", and a write that cannot be committed must never cost the readable value that
 * is already stored.
 */
class AndroidKeystoreEncryptedStringStoreTest {
    @Test
    fun `a written value reads back`() {
        val store = AndroidKeystoreEncryptedStringStore(FakeRecords(), FakeKeys())

        store.write("secret-value")

        assertEquals(EncryptedStringReadResult.Value("secret-value"), store.read())
    }

    @Test
    fun `nothing stored reads as missing`() {
        val store = AndroidKeystoreEncryptedStringStore(FakeRecords(), FakeKeys())

        assertEquals(EncryptedStringReadResult.Missing, store.read())
    }

    @Test
    fun `every write uses a fresh initialization vector`() {
        val records = FakeRecords()
        val store = AndroidKeystoreEncryptedStringStore(records, FakeKeys())

        store.write("first")
        val firstVector = records.storedInitializationVector
        store.write("second")

        assertNotEquals(firstVector, records.storedInitializationVector)
    }

    @Test
    fun `a record missing half of itself is unreadable rather than empty`() {
        val records = FakeRecords()
        records.storedCiphertext = "AAAAAAAAAAAAAAAAAAAAAA=="
        val store = AndroidKeystoreEncryptedStringStore(records, FakeKeys())

        assertIs<EncryptedStringReadResult.Unreadable>(store.read())
    }

    @Test
    fun `a record whose key is gone is unreadable rather than empty`() {
        val records = FakeRecords()
        val keys = FakeKeys()
        val store = AndroidKeystoreEncryptedStringStore(records, keys)
        store.write("secret-value")
        keys.forget()

        // The record is still there and the key is not: the caller has to be able to tell this apart from
        // "nothing was ever stored", because one of them means the account is about to be lost.
        assertIs<EncryptedStringReadResult.Unreadable>(store.read())
    }

    @Test
    fun `a record written under another key is unreadable rather than empty`() {
        val records = FakeRecords()
        val store = AndroidKeystoreEncryptedStringStore(records, FakeKeys())
        store.write("secret-value")
        val other = AndroidKeystoreEncryptedStringStore(records, FakeKeys())

        assertIs<EncryptedStringReadResult.Unreadable>(other.read())
    }

    @Test
    fun `an unusable key without a stored record is replaced on first write`() {
        val records = FakeRecords()
        val keys = FakeKeys(desKey())
        val store = AndroidKeystoreEncryptedStringStore(records, keys)

        store.write("recovered-value")

        assertEquals(1, keys.deleted)
        assertEquals(EncryptedStringReadResult.Value("recovered-value"), store.read())
    }

    @Test
    fun `an unusable key is replaced when the stored record is already unreadable`() {
        val records = FakeRecords()
        val writer = AndroidKeystoreEncryptedStringStore(records, FakeKeys())
        writer.write("previous-value")
        // A key the AES cipher cannot be set up with, over a record that therefore cannot be read.
        val keys = FakeKeys(desKey())
        val store = AndroidKeystoreEncryptedStringStore(records, keys)

        store.write("recovered-value")

        assertEquals(1, keys.deleted)
        assertEquals(EncryptedStringReadResult.Value("recovered-value"), store.read())
    }

    @Test
    fun `a write that cannot be committed keeps the key and the readable value`() {
        val records = FakeRecords()
        val keys = FakeKeys()
        val store = AndroidKeystoreEncryptedStringStore(records, keys)
        store.write("previous-value")
        records.writeFailure = GeneralSecurityException("the record could not be made durable")

        assertFailsWith<GeneralSecurityException> { store.write("replacement-value") }

        // The stored value still decrypts, so the key must survive: replacing it here is what would turn a
        // failed write into a lost account.
        assertEquals(0, keys.deleted)
        assertEquals(EncryptedStringReadResult.Value("previous-value"), store.read())
    }

    @Test
    fun `a clear that cannot be committed is reported to the caller`() {
        val records = FakeRecords()
        val store = AndroidKeystoreEncryptedStringStore(records, FakeKeys())
        store.write("secret-value")
        records.clearFailure = IllegalStateException("commit failed")

        assertFailsWith<IllegalStateException> { store.clear() }
    }

    @Test
    fun `clearing removes the record and leaves the store missing`() {
        val store = AndroidKeystoreEncryptedStringStore(FakeRecords(), FakeKeys())
        store.write("secret-value")

        store.clear()

        assertEquals(EncryptedStringReadResult.Missing, store.read())
    }

    private class FakeRecords : CipherTextRecordStore {
        var storedCiphertext: String? = null
        var storedInitializationVector: String? = null
        var writeFailure: Throwable? = null
        var clearFailure: Throwable? = null

        override fun ciphertext(): String? = storedCiphertext

        override fun initializationVector(): String? = storedInitializationVector

        override fun write(
            ciphertext: String,
            initializationVector: String,
        ) {
            writeFailure?.let { throw it }
            storedCiphertext = ciphertext
            storedInitializationVector = initializationVector
        }

        override fun clear() {
            clearFailure?.let { throw it }
            storedCiphertext = null
            storedInitializationVector = null
        }
    }

    private class FakeKeys(
        private var key: SecretKey? = aesKey(),
    ) : SecretKeyAccess {
        var deleted = 0
            private set

        override fun existing(): SecretKey? = key

        override fun create(): SecretKey = aesKey().also { key = it }

        override fun delete() {
            deleted += 1
            key = null
        }

        /** Stands for a key store entry that is gone, as after a wipe of the store. */
        fun forget() {
            key = null
        }
    }

    private companion object {
        fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        /** A usable key of the wrong algorithm, so setting up the AES cipher with it fails. */
        fun desKey(): SecretKey = KeyGenerator.getInstance("DES").apply { init(56) }.generateKey()
    }
}
