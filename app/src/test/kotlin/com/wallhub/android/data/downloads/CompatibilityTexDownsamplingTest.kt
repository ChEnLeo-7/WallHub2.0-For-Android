package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.TexEntryRoute
import com.wallhub.prototype.mpkg.TexMobileConverter
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTexDownsamplingTest {
    @Test
    fun `compatibility planner converts every decodable static texture to ETC2 without downsampling`() {
        val device = DeviceCapabilityProfile(256L * MIB)
        val background = planSingle("materials/plain_background.tex", staticImageTex(4096, 2048), device)
        val normal = planSingle("materials/plain_background_normal.tex", staticImageTex(4096, 2048), device)
        val mask = planSingle("materials/plain_background_mask.tex", staticImageTex(4096, 2048), device)

        assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, background)
        assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, normal)
        assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, mask)
    }

    @Test
    fun `compatibility downsample geometry preserves aspect ratio and logical dimensions`() {
        assertEquals(2048 to 1079, TexMobileConverter.compatibilityTargetSize(3862, 2035))
        assertEquals(2048 to 1024, TexMobileConverter.compatibilityTargetSize(4096, 2048))
        assertEquals(1 to 2048, TexMobileConverter.compatibilityTargetSize(1, 4096))
        assertEquals(2048 to 2048, TexMobileConverter.compatibilityTargetSize(2048, 2048))
    }

    @Test
    fun `downsample execution emits verified ETC2 with aligned physical and exact logical geometry`() {
        val root = Files.createTempDirectory("wallhub-downsample-output").toFile()
        try {
            val source = root.resolve("source.pkg")
            val tex = staticImageTex(3862, 2035)
            source.writeBytes(tex)
            val output = root.resolve("output.tex")

            val result =
                TexMobileConverter.downsampleToEtc2File(
                    source,
                    0L,
                    source.length(),
                    "materials/plain_background.tex",
                    output,
                    encodeDownsampledEtc2 = { _, _, _, targetWidth, targetHeight, checkCancellation ->
                        checkCancellation()
                        ByteArray(TexMobileConverter.etc2BlockAligned(targetWidth) * TexMobileConverter.etc2BlockAligned(targetHeight))
                    },
                )

            assertTrue(result.reason, result.converted)
            assertEquals(TexEntryRoute.RETAIN_VERIFIED_MOBILE, TexMobileConverter.inspectEntryRoute(output, 0L, output.length()))
            assertEquals(2048, readIntLe(output.readBytes(), 26))
            assertEquals(1080, readIntLe(output.readBytes(), 30))
            assertEquals(2048, readIntLe(output.readBytes(), 34))
            assertEquals(1079, readIntLe(output.readBytes(), 38))
            assertEquals(2048, readIntLe(output.readBytes(), 71))
            assertEquals(1080, readIntLe(output.readBytes(), 75))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `downsample rejects animation and observes cancellation before decoding`() {
        val root = Files.createTempDirectory("wallhub-downsample-cancel").toFile()
        try {
            val animated = root.resolve("animated.tex").apply { writeBytes(staticImageTex(4096, 2048, flags = 4)) }
            val output = root.resolve("output.tex")
            val animatedResult =
                TexMobileConverter.downsampleToEtc2File(
                    animated,
                    0L,
                    animated.length(),
                    "materials/plain_background.tex",
                    output,
                )
            assertFalse(animatedResult.converted)
            assertFalse(output.exists())

            val source = root.resolve("source.tex").apply { writeBytes(staticImageTex(4096, 2048)) }
            var decoderCalled = false
            assertThrows(CancellationException::class.java) {
                TexMobileConverter.downsampleToEtc2File(
                    source,
                    0L,
                    source.length(),
                    "materials/plain_background.tex",
                    output,
                    checkCancellation = { throw CancellationException("cancelled") },
                    encodeDownsampledEtc2 = { _, _, _, _, _, _ ->
                        decoderCalled = true
                        ByteArray(0)
                    },
                )
            }
            assertFalse(decoderCalled)
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun planSingle(path: String, tex: ByteArray, device: DeviceCapabilityProfile): ResourceStrategy {
        val root = Files.createTempDirectory("wallhub-downsample-plan").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.pkg"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            writeDesktopPkg(root.resolve("scene.pkg"), path, tex)
            return WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, device).resources.single().strategy
        } finally {
            root.deleteRecursively()
        }
    }

    private fun staticImageTex(width: Int, height: Int, flags: Int = 0): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, flags, width, height, width, height, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            val png = pngHeader(width, height)
            listOf(1, 13, 0, 1, width, height, 0, 0, png.size).forEach { output.writeIntLe(it) }
            output.write(png)
            output.toByteArray()
        }

    private fun pngHeader(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(PNG_SIGNATURE)
            output.writeIntBe(13)
            output.write("IHDR".toByteArray(Charsets.US_ASCII))
            output.writeIntBe(width)
            output.writeIntBe(height)
            output.write(byteArrayOf(8, 6, 0, 0, 0))
            output.writeIntBe(0)
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

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArrayOutputStream.writeCString(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
        write(0)
    }

    private fun java.io.OutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun java.io.OutputStream.writeIntBe(value: Int) {
        write(value ushr 24 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 8 and 0xff)
        write(value and 0xff)
    }

    private companion object {
        const val MIB = 1024L * 1024L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
