package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.TexEntryRoute
import com.wallhub.prototype.mpkg.TexMobileConverter
import com.wallhub.prototype.mpkg.TexRuntimeDesktopFamily
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedRuntimeDesktopTexTest {
    @Test
    fun `classifies a structurally complete DXT animated sprite sheet`() {
        assertEquals(
            TexRuntimeDesktopFamily.STANDARD_BLOCK_COMPRESSED_ANIMATED,
            TexMobileConverter.classifyRuntimeDesktopFamily(standardAnimatedDxtTex()),
        )
    }

    @Test
    fun `rejects an animated descriptor whose frames do not cover the sprite sheet`() {
        assertNull(TexMobileConverter.classifyRuntimeDesktopFamily(standardAnimatedDxtTex(frameCount = 2)))
    }

    @Test
    fun `classifies bounded conditional variant grammar and rejects truncated variants`() {
        val valid = conditionalVariantTex()
        assertEquals(
            TexRuntimeDesktopFamily.CONDITIONAL_VARIANT_EXTENDED,
            TexMobileConverter.classifyRuntimeDesktopFamily(valid),
        )
        assertNull(TexMobileConverter.classifyRuntimeDesktopFamily(valid.copyOf(valid.size - 1)))
        assertNull(TexMobileConverter.classifyRuntimeDesktopFamily(conditionalVariantTex(declaredVariants = 2)))
    }

    @Test
    fun `plan and execute retain verified runtime desktop bytes and reinspection accepts MPKG`() {
        val root = Files.createTempDirectory("wallhub-runtime-desktop").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.json"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            val tex = standardAnimatedDxtTex()
            writeDesktopPkg(root.resolve("scene.pkg"), "materials/runtime.tex", tex)

            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DeviceCapabilityProfile(256L * 1024 * 1024))
            assertEquals(ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP, plan.resources.single().strategy)
            assertEquals("verified_runtime_desktop_standard_block_compressed_animated", plan.resources.single().reason)

            val output = root.resolve("output.mpkg")
            val report = WorkshopConverter.execute(plan, root, output)
            assertEquals("retained_verified_runtime_desktop_payload", report.outcomes.single().reason)
            val archive = PkgReader.readIndex(output)
            val entry = archive.entries.single { it.path == "materials/runtime.tex" }
            assertArrayEquals(tex, archive.readBytes(entry))
            assertEquals(TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP, TexMobileConverter.inspectEntryRoute(output, entry.offset, entry.length))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun standardAnimatedDxtTex(frameCount: Int = 3): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(DXT5, GIF_FLAG, 256, 768, 256, 768, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 1).forEach { output.writeIntLe(it) }
            output.writeDxtMip(256, 768)
            output.writeCString("TEXS0003")
            listOf(frameCount, 256, 256).forEach { output.writeIntLe(it) }
            output.write(ByteArray(frameCount * 32))
            output.toByteArray()
        }

    private fun conditionalVariantTex(declaredVariants: Int = 1): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(DXT5, 0, 8, 8, 8, 8, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 1, 1, declaredVariants, 0).forEach { output.writeIntLe(it) }
            output.writeCString("""{"condition":{"condition":"1","name":"palette"}}""")
            output.writeIntLe(1)
            output.writeDxtMip(8, 8)
            output.writeIntLe(1)
            // Exact extended record shape observed in conditional TEX: three selector fields,
            // bounded rectangle, metadata value, then a length-delimited variant payload.
            listOf(1, 1, 1, 0, 0, 8, 8, 13, 8).forEach { output.writeIntLe(it) }
            output.write(ByteArray(8) { (it + 1).toByte() })
            output.toByteArray()
        }

    private fun ByteArrayOutputStream.writeDxtMip(width: Int, height: Int) {
        val bytes = ((width + 3) / 4) * ((height + 3) / 4) * 16
        listOf(width, height, 0, bytes, bytes).forEach { writeIntLe(it) }
        write(ByteArray(bytes))
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

    private fun java.io.OutputStream.writeCString(value: String) {
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
        const val DXT5 = 4
        const val GIF_FLAG = 4
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
