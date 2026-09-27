package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.TexMobileConverter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The oversized ETC2 path reads an image out of a desktop TEX.
 *
 * A real 4K Workshop background is neither block aligned (item 3779168864 ships 3862x2160) nor
 * necessarily a PNG (the same item embeds a JPEG), and both used to be reasons to copy the desktop
 * TEX into the MPKG untouched - which the Android client renders as an empty scene. These pin the
 * two decisions that no longer reject it.
 */
class TexMobileConverterEmbeddedImageTest {
    @Test
    fun `etc2 block alignment pads each axis to the next four texels`() {
        assertEquals(4, TexMobileConverter.etc2BlockAligned(1))
        assertEquals(4, TexMobileConverter.etc2BlockAligned(4))
        assertEquals(2160, TexMobileConverter.etc2BlockAligned(2160))
        assertEquals(3864, TexMobileConverter.etc2BlockAligned(3862))
        assertEquals(1040, TexMobileConverter.etc2BlockAligned(1037))
        assertEquals(1920, TexMobileConverter.etc2BlockAligned(1920))
        assertEquals(1284, TexMobileConverter.etc2BlockAligned(1281))
    }

    @Test
    fun `embedded image offset finds a jpeg behind a container prefix`() {
        val payload = byteArrayOf(1, 2, 3) + JPEG_FIXTURE + byteArrayOf(4, 5)

        assertEquals(3, TexMobileConverter.embeddedImageOffset(payload))
    }

    @Test
    fun `embedded image offset finds a png`() {
        val payload = PNG_SIGNATURE + byteArrayOf(9, 9)

        assertEquals(0, TexMobileConverter.embeddedImageOffset(payload))
    }

    @Test
    fun `embedded image offset reports a payload with neither signature`() {
        assertEquals(-1, TexMobileConverter.embeddedImageOffset(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals(-1, TexMobileConverter.embeddedImageOffset(ByteArray(0)))
    }

    /**
     * The fixture is the shipped shape: format 0, `imageFormat` FIF_JPEG, one uncompressed base mipmap
     * whose payload is a JPEG, and a width that is two texels short of a block edge. It must reach the
     * decoder; before the fix the alignment gate rejected it and the caller silently kept the desktop
     * TEX. A JVM run has no BitmapRegionDecoder, so the remaining failure is the decoder itself, and the
     * assertions are that it is no longer the alignment or image-format gate.
     */
    @Test
    fun `an unaligned jpeg backed tex reaches the decoder instead of being rejected`() {
        val source = Files.createTempFile("wallhub-jpeg-tex", ".tex").toFile()
        val output = Files.createTempFile("wallhub-jpeg-etc2", ".tex").toFile()
        try {
            source.writeBytes(jpegBackedUnalignedTex())

            val result = TexMobileConverter.convertToEtc2File(source, 0L, source.length(), output)

            assertFalse(result.converted)
            assertFalse("alignment gate still rejects it: ${result.reason}", result.reason.contains("aligned"))
            assertFalse("image-format gate still rejects it: ${result.reason}", result.reason.contains("image-backed"))
            assertFalse("payload is still searched for a PNG: ${result.reason}", result.reason.contains("PNG"))
            assertFalse(output.exists())
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun `a payload whose base mipmap carries no image stays rejected`() {
        val source = Files.createTempFile("wallhub-noimage-tex", ".tex").toFile()
        val output = Files.createTempFile("wallhub-noimage-etc2", ".tex").toFile()
        try {
            val tex = jpegBackedUnalignedTex().copyOf()
            // Strip the JPEG signature out of the base mipmap payload, leaving the same envelope.
            val start = tex.size - JPEG_FIXTURE.size
            tex[start] = 0
            source.writeBytes(tex)

            val result = TexMobileConverter.convertToEtc2File(source, 0L, source.length(), output)

            assertFalse(result.converted)
            assertTrue(result.reason.contains("PNG or JPEG"))
            assertFalse(output.exists())
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun jpegBackedUnalignedTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            output.writeIntLe(TEX_FORMAT_RGBA8888)
            output.writeIntLe(0)
            output.writeIntLe(UNALIGNED_WIDTH)
            output.writeIntLe(ALIGNED_HEIGHT)
            output.writeIntLe(UNALIGNED_WIDTH)
            output.writeIntLe(ALIGNED_HEIGHT)
            output.writeIntLe(0)
            output.writeCString("TEXB0004")
            output.writeIntLe(1)
            output.writeIntLe(FREE_IMAGE_JPEG)
            output.writeIntLe(0)
            output.writeIntLe(1)
            output.writeIntLe(UNALIGNED_WIDTH)
            output.writeIntLe(ALIGNED_HEIGHT)
            output.writeIntLe(0)
            output.writeIntLe(0)
            output.writeIntLe(JPEG_FIXTURE.size)
            output.write(JPEG_FIXTURE)
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

    private companion object {
        const val TEX_FORMAT_RGBA8888 = 0
        const val FREE_IMAGE_JPEG = 2
        const val UNALIGNED_WIDTH = 3862
        const val ALIGNED_HEIGHT = 2160
        val JPEG_FIXTURE =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xE0.toByte(),
                0x00,
                0x10,
                0x4A,
                0x46,
                0x49,
                0x46,
                0x00,
                0x01,
                0x01,
                0x00,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0xFF.toByte(),
                0xD9.toByte(),
            )
        val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
