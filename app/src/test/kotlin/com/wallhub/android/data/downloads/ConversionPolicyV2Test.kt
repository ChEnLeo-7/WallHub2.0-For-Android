package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.ConversionPlanJsonCodec
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.policy
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionPolicyV2Test {
    @Test
    fun `3299228616 unknown texture shape is planned as passthrough with a stable warning`() {
        withScene("materials/number.am.tex", "not-a-recognized-tex-shape".toByteArray()) { root, source ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE)

            assertEquals(3, plan.version)
            assertEquals(3, plan.policyVersion)
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, plan.resources.single().strategy)
            assertEquals("tex_passthrough_unknown_profile", plan.resources.single().reason)

            val output = root.resolve("converted.mpkg")
            val report = WorkshopConverter.execute(plan, root, output)
            val archive = PkgReader.readIndex(output)
            assertArrayEquals(source, archive.readBytes(archive.entries.single { it.path.endsWith(".tex") }, source.size.toLong()))
            assertTrue(report.warnings.any { it.startsWith("tex_passthrough_unknown_profile:") })
        }
    }

    @Test
    fun `3757572576 legacy texture input budget path is passthrough instead of reject`() {
        val oversizedUnknown = ByteArray(33 * MIB.toInt()) { index -> (index * 31).toByte() }
        withScene("materials/全年龄.tex", oversizedUnknown) { root, _ ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE).resources.single()

            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, resource.strategy)
            assertEquals("tex_passthrough_resource_budget_exceeded", resource.reason)
        }
    }

    @Test
    fun `official presets retain stable ids while selecting quarter half and full texture reduction`() {
        assertEquals(4, MpkgConversionPreset.COMPATIBILITY.policy().textureReduction)
        assertEquals(2, MpkgConversionPreset.BALANCED.policy().textureReduction)
        assertEquals(1, MpkgConversionPreset.HIGH_QUALITY.policy().textureReduction)
        assertEquals(3, MpkgConversionPreset.COMPATIBILITY.policy().version)
    }

    @Test
    fun `official presets convert ordinary image backed material textures to ETC2`() {
        withScene("materials/全年龄.tex", staticImageTex(2048, 2048)) { root, _ ->
            val strategies =
                MpkgConversionPreset.entries.associateWith { preset ->
                    WorkshopConverter.plan(root, preset, DEVICE).resources.single().strategy
                }

            assertEquals(ResourceStrategy.DOWNSAMPLE_TO_ETC2, strategies[MpkgConversionPreset.COMPATIBILITY])
            assertEquals(ResourceStrategy.DOWNSAMPLE_TO_ETC2, strategies[MpkgConversionPreset.BALANCED])
            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, strategies[MpkgConversionPreset.HIGH_QUALITY])
        }
    }

    @Test
    fun `audio strategy follows preserve audio snapshot`() {
        val audio = byteArrayOf(9, 8, 7, 6)
        withScene("audio/theme.ogg", audio) { root, source ->
            val removePlan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE, preserveAudio = false)
            val preservePlan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE, preserveAudio = true)

            assertEquals(ResourceStrategy.REMOVE_AUDIO, removePlan.resources.single().strategy)
            assertEquals(ResourceStrategy.COPY, preservePlan.resources.single().strategy)
            assertTrue(removePlan != preservePlan)

            val removedOutput = root.resolve("removed.mpkg")
            val preservedOutput = root.resolve("preserved.mpkg")
            WorkshopConverter.execute(removePlan, root, removedOutput)
            WorkshopConverter.execute(preservePlan, root, preservedOutput)

            assertTrue(PkgReader.readIndex(removedOutput).entries.none { it.path == "audio/theme.ogg" })
            val preservedArchive = PkgReader.readIndex(preservedOutput)
            assertArrayEquals(
                source,
                preservedArchive.readBytes(preservedArchive.entries.single { it.path == "audio/theme.ogg" }, source.size.toLong()),
            )
        }
    }

    @Test
    fun `plan codec v3 preserves audio preference and equality`() {
        withScene("audio/theme.mp3", byteArrayOf(1, 2, 3)) { root, _ ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE, preserveAudio = true)
            val decoded = ConversionPlanJsonCodec.decode(ConversionPlanJsonCodec.encode(plan))

            assertEquals(plan, decoded)
            assertTrue(decoded.preserveAudio)
            assertEquals(ResourceStrategy.COPY, decoded.resources.single().strategy)
        }
    }

    @Test
    fun `plan codec v3 rejects v2 JSON`() {
        withScene("scripts/demo.txt", byteArrayOf(1, 2, 3)) { root, _ ->
            val encoded = ConversionPlanJsonCodec.encode(WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE))
            val v2 = encoded.replaceFirst("\"version\":3", "\"version\":2")

            assertThrows(IllegalArgumentException::class.java) { ConversionPlanJsonCodec.decode(v2) }
        }
    }

    private fun withScene(path: String, payload: ByteArray, block: (File, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("wallhub-policy-v2").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.pkg"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            writeDesktopPkg(root.resolve("scene.pkg"), path, payload)
            block(root, payload)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun staticImageTex(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(0, 0, width, height, width, height, 0).forEach(output::writeIntLe)
            output.writeCString("TEXB0004")
            val png =
                ByteArrayOutputStream().use { image ->
                    image.write(PNG_SIGNATURE)
                    image.writeIntBe(13)
                    image.write("IHDR".toByteArray(Charsets.US_ASCII))
                    image.writeIntBe(width)
                    image.writeIntBe(height)
                    image.write(byteArrayOf(8, 6, 0, 0, 0))
                    image.writeIntBe(0)
                    image.toByteArray()
                }
            listOf(1, 13, 0, 1, width, height, 0, 0, png.size).forEach(output::writeIntLe)
            output.write(png)
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
        val DEVICE = DeviceCapabilityProfile(256L * MIB)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
