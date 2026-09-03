package com.wallhub.android.core.model

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Depot file flags, engine-neutral equivalent of Steam's EDepotFileFlag. */
enum class DepotFileFlag(val code: Int) {
    UserConfig(1),
    VersionedUserConfig(2),
    Encrypted(4),
    ReadOnly(8),
    Hidden(16),
    Executable(32),
    Directory(64),
    CustomExecutable(128),
    InstallScript(256),
    Symlink(512),
    ;

    companion object {
        fun fromMask(mask: Int): Set<DepotFileFlag> = entries.filter { flag -> flag.code and mask != 0 }.toSet()
    }
}

/** Engine-neutral depot file record replacing JavaSteam's FileData. */
class DepotFileSpec(
    var fileName: String,
    val totalSize: Long,
    val fileHash: ByteArray,
    val flags: Set<DepotFileFlag>,
    val chunks: List<DepotChunkSpec>,
    var linkTarget: String = "",
)

/**
 * Engine-neutral depot manifest replacing JavaSteam's DepotManifest. Filenames may still be
 * encrypted when the manifest metadata advertises [filenamesEncrypted]; call
 * [decryptFilenames] with the depot decryption key before consuming file paths.
 */
class DepotManifestSpec(
    val filenamesEncrypted: Boolean,
    val depotId: Int,
    val manifestGid: Long,
    val totalUncompressedSize: Long,
    val files: List<DepotFileSpec>,
) {
    /**
     * Decrypts in-place encrypted file names with the depot key.
     *
     * Every encrypted name is a Base64-url payload whose first 16 bytes hold an AES/ECB
     * encrypted IV; the remainder decrypts via AES/CBC/PKCS7 with the same depot key.
     *
     * @return true when the manifest is usable (nothing to decrypt or every name decoded).
     */
    fun decryptFilenames(depotKey: ByteArray): Boolean {
        if (!filenamesEncrypted) return true
        require(depotKey.size == 32) { "Depot filename decryption requires a 32 byte key" }
        val ecbCipher = Cipher.getInstance("AES/ECB/NoPadding")
        val cbcCipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        val secretKey = SecretKeySpec(depotKey, "AES")
        files.forEach { file ->
            val decoded = decryptName(ecbCipher, cbcCipher, secretKey, file.fileName)
                ?: return false
            file.fileName = decoded
            if (file.linkTarget.isNotEmpty()) {
                val decodedLink = decryptName(ecbCipher, cbcCipher, secretKey, file.linkTarget)
                    ?: return false
                file.linkTarget = decodedLink
            }
        }
        return true
    }

    private fun decryptName(
        ecbCipher: Cipher,
        cbcCipher: Cipher,
        secretKey: SecretKeySpec,
        name: String,
    ): String? {
        val encrypted =
            try {
                java.util.Base64.getUrlDecoder().decode(
                    name
                        .replace('+', '-')
                        .replace('/', '_')
                        .replace("\n", "")
                        .replace("\r", "")
                        .replace(" ", ""),
                )
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (encrypted.size <= IV_LENGTH || encrypted.size % IV_LENGTH != 0) return null
        return try {
            val iv =
                ecbCipher
                    .apply { init(Cipher.DECRYPT_MODE, secretKey) }
                    .doFinal(encrypted, 0, IV_LENGTH)
            val decrypted =
                cbcCipher
                    .apply { init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv)) }
                    .doFinal(encrypted, IV_LENGTH, encrypted.size - IV_LENGTH)
            var length = decrypted.size
            if (length > 0 && decrypted[length - 1] == 0.toByte()) length--
            String(decrypted, 0, length, Charsets.UTF_8).replace('\\', File.separatorChar)
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val IV_LENGTH = 16
    }
}
