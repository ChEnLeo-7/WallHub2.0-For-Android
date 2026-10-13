package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.TexEntryRoute
import com.wallhub.prototype.mpkg.TexMobileConverter
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
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
    fun `verified video passthrough is not rejected by preset transform budget`() {
        val root = Files.createTempDirectory("wallhub-large-video-plan").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.pkg"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            val payload = mp4Payload("mp42", mediaPaddingBytes = 17 * 1024 * 1024)
            writeDesktopPkg(root.resolve("scene.pkg"), "materials/video.tex", videoTex(payload))

            val balanced = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DeviceCapabilityProfile(256L * 1024 * 1024))
            val compatibility = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DeviceCapabilityProfile(256L * 1024 * 1024))

            assertEquals(ResourceStrategy.RETAIN_VERIFIED_MOBILE, balanced.resources.single().strategy)
            assertEquals(ResourceStrategy.RETAIN_VERIFIED_MOBILE, compatibility.resources.single().strategy)
        } finally {
            root.deleteRecursively()
        }
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

    private fun mp4Payload(
        brand: String,
        mediaPaddingBytes: Int = 0,
    ): ByteArray =
        ftypBox(brand) +
            box("moov", byteArrayOf(1, 2, 3, 4)) +
            box("mdat", byteArrayOf(5, 6, 7, 8) + ByteArray(mediaPaddingBytes))

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

    private fun writeDesktopPkg(file: File, path: String, payload: ByteArray) {
        val magic = "PKGV0001".toByteArray(Charsets.US_ASCII)
        val encodedPath = path.toByteArray(Charsets.UTF_8)
        file.outputStream().use { output ->
            output.writeIntLe(magic.size)
            output.write(magic)
            output.writeIntLe(1)
            output.writeIntLe(encodedPath.size)
            output.write(encodedPath)
            output.writeIntLe(0)
            output.writeIntLe(payload.size)
            output.write(payload)
        }
    }

    private fun java.io.OutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
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

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
