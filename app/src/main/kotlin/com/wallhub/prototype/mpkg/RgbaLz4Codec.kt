package com.wallhub.prototype.mpkg

import com.wallhub.android.BuildConfig
import net.jpountz.lz4.LZ4Factory

enum class RgbaLz4Mode(
    val stableId: String,
) {
    HC3("hc3"),
    FAST("fast"),
    ;

    companion object {
        fun fromStableId(value: String): RgbaLz4Mode =
            entries.singleOrNull { it.stableId == value }
                ?: throw IllegalArgumentException("Unknown RGBA LZ4 mode")
    }
}

data class CompressedLz4Block(
    val bytes: ByteArray,
    val length: Int,
)

/** Raw LZ4 block encoding for mobile TEX. Selection is explicit and failures are never hidden. */
object RgbaLz4Codec {
    val configuredMode: RgbaLz4Mode by lazy { RgbaLz4Mode.fromStableId(BuildConfig.MPKG_LZ4_MODE) }

    fun compress(
        rgba: ByteArray,
        mode: RgbaLz4Mode = configuredMode,
    ): CompressedLz4Block =
        MpkgTimingSession.measureCurrent(MpkgPhase.RGBA_LZ4) {
            val factory = LZ4Factory.fastestJavaInstance()
            val compressor =
                when (mode) {
                    RgbaLz4Mode.HC3 -> factory.highCompressor(RGBA_LZ4_HC_LEVEL)
                    RgbaLz4Mode.FAST -> factory.fastCompressor()
                }
            val compressed = ByteArray(compressor.maxCompressedLength(rgba.size))
            val length = compressor.compress(rgba, 0, rgba.size, compressed, 0, compressed.size)
            check(length in 1..compressed.size || rgba.isEmpty()) { "LZ4 compressor returned an invalid length" }
            CompressedLz4Block(compressed, length)
        }
}

internal const val RGBA_LZ4_HC_LEVEL = 3
