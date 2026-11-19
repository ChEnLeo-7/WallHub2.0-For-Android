package com.wallhub.prototype.mpkg

import net.jpountz.lz4.LZ4Factory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RgbaLz4CodecTest {
    @Test
    fun `HC3 and fast candidates round trip complete RGBA bytes including alpha`() {
        val rgba = ByteArray(1024 * 1024)
        Random(42).nextBytes(rgba)
        for (index in 3 until rgba.size step 4) rgba[index] = (index * 31).toByte()

        RgbaLz4Mode.entries.forEach { mode ->
            val block = RgbaLz4Codec.compress(rgba, mode)
            val decoded = ByteArray(rgba.size)
            val decodedLength =
                LZ4Factory.fastestJavaInstance().safeDecompressor()
                    .decompress(block.bytes, 0, block.length, decoded, 0, decoded.size)

            assertEquals(rgba.size, decodedLength)
            assertContentEquals(rgba, decoded, "$mode changed RGBA or alpha bytes")
        }
    }

    @Test
    fun `invalid mode fails explicitly instead of silently falling back`() {
        assertFailsWith<IllegalArgumentException> { RgbaLz4Mode.fromStableId("unknown") }
    }
}
