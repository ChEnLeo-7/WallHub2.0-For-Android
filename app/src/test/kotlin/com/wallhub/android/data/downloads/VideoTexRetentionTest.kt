package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.TexEntryRoute
import com.wallhub.prototype.mpkg.TexMobileConverter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTexRetentionTest {
    @Test
    fun `bounded MP4 backed video TEX is retained byte for byte`() {
        assertRoute(mp4Payload("mp42"), TexEntryRoute.RETAIN_VERIFIED_VIDEO)
        assertRoute(mp4Payload("isom"), TexEntryRoute.RETAIN_VERIFIED_VIDEO)
    }

    @Test
    fun `unknown and image ISO BMFF brands stay unsupported`() {
        assertRoute(mp4Payload("JUNK"), TexEntryRoute.UNSUPPORTED_VIDEO)
        assertRoute(mp4Payload("avif"), TexEntryRoute.UNSUPPORTED_VIDEO)
    }

    @Test
    fun `ftyp without movie and media boxes stays unsupported`() {
        assertRoute(ftypBox("mp42"), TexEntryRoute.UNSUPPORTED_VIDEO)
    }

    @Test
    fun `truncated ISO BMFF boxes stay unsupported`() {
        assertRoute(ftypBox("isom") + boxHeader(size = 64, type = "moov") + byteArrayOf(1, 2), TexEntryRoute.UNSUPPORTED_VIDEO)
    }

    @Test
    fun `invalid TEX compression marker stays unsupported`() {
        val bytes = videoTex(mp4Payload("isom")).copyOf()
        // TEXB0004 fixed header: compression marker begins at byte 79 for this synthetic envelope.
        bytes[79] = 2
        assertRouteBytes(bytes, TexEntryRoute.UNSUPPORTED)
    }

    @Test
    fun `video TEX with trailing bytes stays unsupported`() {
        val source = Files.createTempFile("wallhub-video-tail", ".tex").toFile()
        try {
            source.writeBytes(videoTex(mp4Payload("isom")) + byteArrayOf(1, 2, 3, 4))
            assertEquals(TexEntryRoute.UNSUPPORTED, TexMobileConverter.inspectEntryRoute(source, 0L, source.length()))
        } finally {
            source.delete()
        }
    }

    private fun assertRoute(
        payload: ByteArray,
        expected: TexEntryRoute,
    ) = assertRouteBytes(videoTex(payload), expected)

    private fun assertRouteBytes(
        bytes: ByteArray,
        expected: TexEntryRoute,
    ) {
        val source = Files.createTempFile("wallhub-video", ".tex").toFile()
        try {
            source.writeBytes(bytes)
            assertEquals(expected, TexMobileConverter.inspectEntryRoute(source, 0L, source.length()))
        } finally {
            source.delete()
        }
    }

    private fun mp4Payload(brand: String): ByteArray =
        ftypBox(brand) + box("moov", byteArrayOf(1, 2, 3, 4)) + box("mdat", byteArrayOf(5, 6, 7, 8))

    private fun videoTex(payload: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 34, 3840, 2160, 3840, 2160, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 1, 3840, 2160, 0, 0, payload.size).forEach { output.writeIntLe(it) }
            output.write(payload)
            output.toByteArray()
        }

    private fun ftypBox(majorBrand: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeIntBe(24)
            output.write("ftyp".toByteArray(Charsets.US_ASCII))
            output.write(majorBrand.toByteArray(Charsets.US_ASCII))
            output.writeIntBe(0)
            val compatible = if (majorBrand in setOf("mp42", "isom")) listOf("isom", "mp41") else listOf(majorBrand, majorBrand)
            compatible.forEach { output.write(it.toByteArray(Charsets.US_ASCII)) }
            output.toByteArray()
        }

    private fun box(type: String, body: ByteArray): ByteArray = boxHeader(8 + body.size, type) + body

    private fun boxHeader(
        size: Int,
        type: String,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeIntBe(size)
            output.write(type.toByteArray(Charsets.US_ASCII))
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

    private fun ByteArrayOutputStream.writeIntBe(value: Int) {
        write(value ushr 24 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 8 and 0xff)
        write(value and 0xff)
    }
}
