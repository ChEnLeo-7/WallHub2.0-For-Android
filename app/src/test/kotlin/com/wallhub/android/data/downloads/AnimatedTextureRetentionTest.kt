package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.TexMobileConverter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedTextureRetentionTest {
    @Test
    fun animatedTextureCanBeRetainedWithoutFlatteningItsFrames() {
        val output = Files.createTempFile("wallhub-animated", ".tex").toFile()
        try {
            val result = TexMobileConverter.convertToFile(animatedRgbaTex(), output)

            assertFalse(result.converted)
            assertTrue(result.canRetainOriginal)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    @Test
    fun validTextureWithoutUnambiguousBaseMipmapCanBeRetained() {
        val output = Files.createTempFile("wallhub-mipmap", ".tex").toFile()
        try {
            val result = TexMobileConverter.convertToFile(ambiguousBaseMipmapTex(), output)

            assertFalse(result.converted)
            assertTrue(result.canRetainOriginal)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    @Test
    fun invalidTextureCannotBeRetained() {
        val output = Files.createTempFile("wallhub-invalid", ".tex").toFile()
        try {
            val result = TexMobileConverter.convertToFile(byteArrayOf(1, 2, 3), output)

            assertFalse(result.converted)
            assertFalse(result.canRetainOriginal)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    @Test
    fun structurallyValidDesktopPayloadMismatchCanBeRetained() {
        val output = Files.createTempFile("wallhub-desktop-layout", ".tex").toFile()
        try {
            val result = TexMobileConverter.convertToFile(desktopPayloadMismatchTex(), output)

            assertFalse(result.converted)
            assertTrue(result.canRetainOriginal)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    @Test
    fun oversizedDimensionsRetainAValidTextureInsteadOfAllocatingRgbaPeak() {
        val output = Files.createTempFile("wallhub-large-texture", ".tex").toFile()
        try {
            val result = TexMobileConverter.convertToFile(largeEnvelopeOnlyTex(), output)

            assertFalse(result.converted)
            assertTrue(result.canRetainOriginal)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    private fun largeEnvelopeOnlyTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 0, 4096, 4096, 4096, 4096, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 0).forEach { output.writeIntLe(it) }
            output.toByteArray()
        }

    private fun desktopPayloadMismatchTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 0, 1, 1, 1, 1, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 1, 1, 1, 0, 8, 8).forEach { output.writeIntLe(it) }
            output.write(ByteArray(8) { it.toByte() })
            output.toByteArray()
        }

    private fun animatedRgbaTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 4, 1, 1, 1, 1, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 1, 1, 1, 0, 4, 4).forEach { output.writeIntLe(it) }
            output.write(byteArrayOf(1, 2, 3, -1))
            output.toByteArray()
        }

    private fun ambiguousBaseMipmapTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 0, 1, 1, 1, 1, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 2).forEach { output.writeIntLe(it) }
            repeat(2) {
                listOf(1, 1, 0, 4, 4).forEach { output.writeIntLe(it) }
                output.write(byteArrayOf(1, 2, 3, -1))
            }
            output.toByteArray()
        }

    private fun ByteArrayOutputStream.writeCString(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
        write(0)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }
}
