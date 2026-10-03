package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.TexMobileConverter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticTexBudgetTest {
    @Test
    fun `real 4K DXT5 and R8 textures fit a 256 MiB serialized conversion budget`() {
        assertTrue(fits(format = 4, textureWidth = 3840, textureHeight = 2176, imageWidth = 3840, imageHeight = 2160, entry = 459268, stored = 293048, decompressed = 8355840))
        assertTrue(fits(format = 4, textureWidth = 3456, textureHeight = 2176, imageWidth = 3449, imageHeight = 2160, entry = 4829307, stored = 3572057, decompressed = 7520256))
        assertTrue(fits(format = 9, textureWidth = 5096, textureHeight = 2867, imageWidth = 5096, imageHeight = 2867, entry = 1612725, stored = 1612634, decompressed = 14610232))
    }

    @Test
    fun `decompressed R8 input is not constrained by the destination RGBA size`() {
        assertTrue(fits(format = 9, textureWidth = 3840, textureHeight = 3000, imageWidth = 3840, imageHeight = 3000, entry = 2 * MIB, stored = 2 * MIB, decompressed = 11 * MIB))
    }

    @Test
    fun `conversion exceeding half the process heap remains rejected`() {
        assertFalse(fits(format = 9, textureWidth = 7000, textureHeight = 7000, imageWidth = 7000, imageHeight = 7000, entry = 8 * MIB, stored = 8 * MIB, decompressed = 49 * MIB))
    }

    private fun fits(
        format: Int,
        textureWidth: Int,
        textureHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        entry: Int,
        stored: Int,
        decompressed: Int,
    ): Boolean =
        TexMobileConverter.staticConversionFitsBudget(
            format,
            textureWidth,
            textureHeight,
            imageWidth,
            imageHeight,
            entry.toLong(),
            stored.toLong(),
            decompressed.toLong(),
            256L * MIB,
        )

    private companion object {
        const val MIB = 1024 * 1024
    }
}
