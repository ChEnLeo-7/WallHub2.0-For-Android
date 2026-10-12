package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.TexEntryRoute
import com.wallhub.prototype.mpkg.TexMobileConverter
import com.wallhub.prototype.mpkg.WorkshopConverter
import com.wallhub.prototype.mpkg.WorkshopKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `animated image texture with verified TEXS0003 descriptor reaches ETC2 conversion`() {
        val source = Files.createTempFile("wallhub-animated-image", ".tex").toFile()
        try {
            source.writeBytes(animatedImageTex("TEXS0003"))

            assertEquals(
                TexEntryRoute.CONVERT_IMAGE_TO_ETC2,
                TexMobileConverter.inspectEntryRoute(source, 0L, source.length()),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `animated image texture with unknown trailing data remains unsupported`() {
        val source = Files.createTempFile("wallhub-animated-unknown", ".tex").toFile()
        try {
            source.writeBytes(animatedImageTex("UNKNOWN3"))

            assertEquals(
                TexEntryRoute.UNSUPPORTED,
                TexMobileConverter.inspectEntryRoute(source, 0L, source.length()),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `actual-shape unaligned animated TEX converts to verified RGBA and preserves descriptor exactly`() {
        val root = Files.createTempDirectory("wallhub-animated-rgba").toFile()
        try {
            val source = root.resolve("source.tex").apply { writeBytes(actualShapeAnimatedTex()) }
            val output = root.resolve("output.tex")
            assertEquals(TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA, TexMobileConverter.inspectEntryRoute(source, 0L, source.length()))

            val result =
                TexMobileConverter.convertAnimatedImageToRgbaFile(
                    source,
                    0L,
                    source.length(),
                    output,
                    decodeImage = ::syntheticRgba,
                )

            assertTrue(result.reason, result.converted)
            assertEquals(TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE, TexMobileConverter.inspectEntryRoute(output, 0L, output.length()))
            assertEquals(LOGICAL_WIDTH, readIntLe(output, 26L))
            assertEquals(LOGICAL_HEIGHT, readIntLe(output, 30L))
            assertArrayEquals(ANIMATION_DESCRIPTOR, output.readBytes().takeLast(ANIMATION_DESCRIPTOR.size).toByteArray())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `actual-shape animated TEX is rejected when the heap cannot hold decode and compression peaks`() {
        val source = Files.createTempFile("wallhub-animated-budget", ".tex").toFile()
        try {
            source.writeBytes(actualShapeAnimatedTex())

            assertEquals(
                TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET,
                TexMobileConverter.inspectEntryRoute(source, 0L, source.length(), maxHeapBytes = 16L * 1024 * 1024),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `WorkshopConverter converts complete synthetic package and reinspects animated output`() {
        val root = Files.createTempDirectory("wallhub-animated-workshop").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.json","title":"Synthetic"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            writeDesktopPkg(root.resolve("scene.pkg"), "materials/animated.tex", actualShapeAnimatedTex())
            val output = root.resolve("converted.mpkg")

            val report = WorkshopConverter.convert(root, output, animatedImageDecoder = ::syntheticRgba)

            assertEquals(WorkshopKind.SCENE, report.kind)
            assertEquals(1, report.convertedTextures)
            val archive = PkgReader.readIndex(output)
            val texture = archive.entries.single { it.path == "materials/animated.tex" }
            assertEquals(
                TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE,
                TexMobileConverter.inspectEntryRoute(output, texture.offset, texture.length),
            )
            assertArrayEquals(ANIMATION_DESCRIPTOR, archive.readBytes(texture).takeLast(ANIMATION_DESCRIPTOR.size).toByteArray())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun syntheticRgba(image: File, width: Int, height: Int, checkCancellation: () -> Unit): ByteArray {
        checkCancellation()
        assertTrue(image.readBytes().copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        assertEquals(LOGICAL_WIDTH, width)
        assertEquals(LOGICAL_HEIGHT, height)
        return ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4)) { index -> index.toByte() }
    }

    private fun actualShapeAnimatedTex(): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 6, 2048, 1024, LOGICAL_WIDTH, LOGICAL_HEIGHT, 0).forEach { value -> output.writeIntLe(value) }
            output.writeCString("TEXB0003")
            output.writeIntLe(1)
            output.writeIntLe(FREE_IMAGE_PNG)
            output.writeIntLe(ACTUAL_MIPMAPS.size)
            ACTUAL_MIPMAPS.forEach { (width, height) ->
                listOf(width, height, 0, 0, PNG_SIGNATURE.size).forEach { value -> output.writeIntLe(value) }
                output.write(PNG_SIGNATURE)
            }
            output.write(ANIMATION_DESCRIPTOR)
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

    private fun readIntLe(file: File, offset: Long): Int =
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            Integer.reverseBytes(input.readInt())
        }

    private fun animatedImageTex(tailMagic: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(TEX_FORMAT_RGBA8888, TEX_FLAG_GIF or 2, 1200, 1200, 1200, 1200, -682089).forEach {
                output.writeIntLe(it)
            }
            output.writeCString("TEXB0004")
            listOf(1, FREE_IMAGE_PNG, 0, 4).forEach { output.writeIntLe(it) }
            listOf(1200 to 1200, 600 to 600, 300 to 300, 150 to 150).forEach { (width, height) ->
                output.writeIntLe(width)
                output.writeIntLe(height)
                output.writeIntLe(0)
                output.writeIntLe(0)
                output.writeIntLe(PNG_SIGNATURE.size)
                output.write(PNG_SIGNATURE)
            }
            output.writeCString(tailMagic)
            listOf(25, 240, 240).forEach { output.writeIntLe(it) }
            output.write(ByteArray(25 * 32))
            output.toByteArray()
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

    private fun java.io.OutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private companion object {
        const val TEX_FORMAT_RGBA8888 = 0
        const val TEX_FLAG_GIF = 4
        const val FREE_IMAGE_PNG = 13
        const val FREE_IMAGE_JPEG = 2
        const val LOGICAL_WIDTH = 1603
        const val LOGICAL_HEIGHT = 616
        const val UNALIGNED_WIDTH = 3862
        const val ALIGNED_HEIGHT = 2160
        val ACTUAL_MIPMAPS =
            listOf(1603 to 616, 801 to 308, 400 to 154, 200 to 77, 100 to 38, 50 to 19, 25 to 9)
        val ANIMATION_DESCRIPTOR =
            ByteArrayOutputStream().use { output ->
                output.write("TEXS0003".toByteArray(Charsets.US_ASCII))
                output.write(0)
                listOf(2, LOGICAL_WIDTH, LOGICAL_HEIGHT).forEach { value ->
                    output.write(value and 0xff)
                    output.write(value ushr 8 and 0xff)
                    output.write(value ushr 16 and 0xff)
                    output.write(value ushr 24 and 0xff)
                }
                output.write(ByteArray(64) { index -> (index * 3 + 1).toByte() })
                output.toByteArray()
            }
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
