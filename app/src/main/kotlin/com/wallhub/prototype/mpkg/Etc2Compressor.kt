package com.wallhub.prototype.mpkg

import android.util.Log

/**
 * WallHub's own ETC2 RGBA8 encoder, backed by the app-owned native module under
 * `app/src/main/cpp` (etcpak, BSD-3-Clause, vendored there with its license).
 *
 * The encoder used to live in the removed `wallhub-rust` depot core, which meant a
 * texture conversion reached into the download engine's native library. It is now a
 * module of this app: `scripts/build-etc2.sh` compiles it into
 * `libwallhub_etc2.so` for every shipped ABI, and nothing outside this file knows
 * that a native library is involved.
 *
 * The library is loaded lazily on first touch. When it is missing for the current
 * ABI [available] is false and [compressRgba] throws a message the caller already
 * knows how to report; the MPKG converter turns that into "keep the original
 * texture", which is how every other conversion failure behaves.
 */
internal object Etc2Compressor {
    private const val LOG_TAG = "WallHubEtc2"
    private const val LIBRARY_NAME = "wallhub_etc2"

    val available: Boolean =
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.w(LOG_TAG, "$LIBRARY_NAME is unavailable for this ABI", error)
            false
        }

    /**
     * Encodes [pixels] (R, G, B, A bytes, row-major) as ETC2 RGBA8 and returns
     * `width * height` bytes of 4x4 blocks.
     */
    fun compressRgba(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(available) { "$LIBRARY_NAME native library is unavailable" }
        require(width > 0 && height > 0) { "ETC2 dimensions must be positive" }
        require(width % 4 == 0 && height % 4 == 0) {
            "ETC2 dimensions must be multiples of four: ${width}x$height"
        }
        val expected = Math.multiplyExact(width, height)
        require(pixels.size == expected * 4) {
            "ETC2 input is ${pixels.size} bytes for a ${width}x$height RGBA image"
        }
        return requireNotNull(nativeCompressRgba(pixels, width, height)) {
            "The ETC2 encoder rejected a ${width}x$height image"
        }
    }

    /** Returns null only for input the validation in [compressRgba] already rejects. */
    private external fun nativeCompressRgba(
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray?
}
