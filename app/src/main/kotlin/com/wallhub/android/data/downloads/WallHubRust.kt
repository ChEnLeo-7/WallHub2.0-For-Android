package com.wallhub.android.data.downloads

import android.util.Log

/**
 * JNI bridge to the `wallhub-rust` native depot core. Safe to touch on any ABI: when the
 * native library is missing (engine not shipped for this build/ABI) [available] is false
 * and every native entry point would throw, so callers must check [available] first.
 */
object WallHubRust {
    private const val LOG_TAG = "WallHubRust"

    val available: Boolean =
        try {
            System.loadLibrary("wallhub_rust")
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.w(LOG_TAG, "wallhub-rust native engine unavailable; staying on the Kotlin engine", error)
            false
        }

    external fun engineVersion(): String

    external fun verifyChunk(
        data: ByteArray,
        expectedChecksum: Int,
    ): Boolean

    /** Decrypts, decompresses and verifies one encrypted depot chunk. */
    external fun decodeChunk(
        encrypted: ByteArray,
        depotKey: ByteArray,
        expectedChecksum: Int,
        uncompressedLength: Int,
    ): ByteArray

    /** Downloads one CDN resource (chunk payload) over HTTP/2. */
    external fun downloadChunk(
        url: String,
        timeoutMs: Int,
    ): ByteArray

    /** Downloads, decrypts, decompresses and verifies one chunk in a single FFI crossing. */
    external fun downloadAndDecodeChunk(
        url: String,
        depotKey: ByteArray,
        expectedChecksum: Int,
        uncompressedLength: Int,
        timeoutMs: Int,
    ): ByteArray
}
