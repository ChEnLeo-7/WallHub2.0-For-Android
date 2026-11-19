package com.wallhub.prototype.mpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Etc2CompressorFocusedDeviceTest {
    @Test
    fun arm64JniProducesDeterministicSizedEtc2BlockWithAlpha() {
        assertTrue(Etc2Compressor.available)
        val rgba =
            ByteArray(4 * 4 * 4).also { pixels ->
                repeat(16) { pixel ->
                    pixels[pixel * 4] = (pixel * 11).toByte()
                    pixels[pixel * 4 + 1] = (255 - pixel * 7).toByte()
                    pixels[pixel * 4 + 2] = (pixel * 3).toByte()
                    pixels[pixel * 4 + 3] = (pixel * 13).toByte()
                }
            }

        val first = Etc2Compressor.compressRgba(rgba, 4, 4)
        val second = Etc2Compressor.compressRgba(rgba, 4, 4)

        assertEquals(16, first.size)
        assertArrayEquals(first, second)
    }
}
