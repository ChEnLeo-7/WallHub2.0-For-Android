package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopDeclarativeMaterialPlanningTest {
    @Test
    fun `JSON binding takes priority over texture filename tokens`() {
        withScene(
            "materials/material.json" to """{"albedoTexture":"materials/normal.tex"}""".toByteArray(),
            "materials/normal.tex" to staticImageTex(),
        ) { root ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE).texture("materials/normal.tex")

            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, resource.strategy)
            assertEquals("compatibility_color_texture_etc2", resource.reason)
        }
    }

    @Test
    fun `declared color follows all four preset strategies`() {
        withScene(
            "materials/material.json" to """{"albedoTexture":"materials/plain.tex"}""".toByteArray(),
            "materials/plain.tex" to staticImageTex(2_048, 2_048),
        ) { root ->
            val strategies =
                MpkgConversionPreset.entries.associateWith { preset ->
                    WorkshopConverter.plan(root, preset, DEVICE).texture("materials/plain.tex").strategy
                }

            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, strategies[MpkgConversionPreset.COMPATIBILITY])
            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, strategies[MpkgConversionPreset.BALANCED])
            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, strategies[MpkgConversionPreset.HIGH_QUALITY])
            assertEquals(ResourceStrategy.PASSTHROUGH, strategies[MpkgConversionPreset.SOURCE_FIDELITY])
        }
    }

    @Test
    fun `source fidelity passes declared data texture through without lossless conversion`() {
        withScene(
            "materials/material.json" to """{"normalTexture":"materials/normal.tex"}""".toByteArray(),
            "materials/normal.tex" to staticImageTex(),
        ) { root ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.SOURCE_FIDELITY, DEVICE).texture("materials/normal.tex")

            assertEquals(ResourceStrategy.PASSTHROUGH, resource.strategy)
            assertEquals("source_fidelity_tex_passthrough", resource.reason)
        }
    }

    @Test
    fun `unrelated JSON cannot override conservative filename semantics`() {
        withScene(
            "scripts/config.json" to """{"albedoTexture":"materials/normal.tex"}""".toByteArray(),
            "materials/normal.tex" to staticImageTex(),
        ) { root ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE).texture("materials/normal.tex")

            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resource.strategy)
            assertEquals("semantic_texture_lossless_rgba", resource.reason)
        }
    }

    @Test
    fun `conflicting JSON bindings choose conservative lossless conversion`() {
        withScene(
            "materials/material.json" to
                """{"albedoTexture":"materials/shared.tex","roughnessTexture":"materials/shared.tex"}""".toByteArray(),
            "materials/shared.tex" to staticImageTex(),
        ) { root ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE).texture("materials/shared.tex")

            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resource.strategy)
            assertEquals("semantic_texture_lossless_rgba", resource.reason)
        }
    }

    @Test
    fun `oversized graph document only degrades unresolved texture`() {
        withScene(
            "materials/good.material.json" to """{"color":"materials/color.tex"}""".toByteArray(),
            "materials/oversized.material.json" to ByteArray(2 * 1024 * 1024 + 1) { ' '.code.toByte() },
            "materials/color.tex" to staticImageTex(),
            "materials/mystery.tex" to staticImageTex(),
        ) { root ->
            val resources = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE)

            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, resources.texture("materials/color.tex").strategy)
            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resources.texture("materials/mystery.tex").strategy)
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, resources.resource("materials/oversized.material.json").strategy)
            assertEquals("material_graph_document_invalid", resources.resource("materials/oversized.material.json").reason)
        }
    }

    @Test
    fun `aggregate graph byte limit skips only later documents`() {
        val boundedDocument = "{}".toByteArray() + ByteArray(2 * 1024 * 1024 - 2) { ' '.code.toByte() }
        withScene(
            "project-a.json" to boundedDocument,
            "project-b.json" to boundedDocument,
            "scene-a.json" to boundedDocument,
            "scene-b.json" to boundedDocument,
            "z.material.json" to """{"color":"materials/late.tex"}""".toByteArray(),
            "materials/late.tex" to staticImageTex(),
        ) { root ->
            val resource = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE).texture("materials/late.tex")

            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resource.strategy)
            val graphResource = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE).resource("z.material.json")
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, graphResource.strategy)
            assertEquals("material_graph_document_invalid", graphResource.reason)
        }
    }

    @Test
    fun `invalid graph document only degrades unresolved texture`() {
        withScene(
            "materials/good.material.json" to """{"color":"materials/color.tex"}""".toByteArray(),
            "materials/broken.material.json" to "{".toByteArray(),
            "materials/color.tex" to staticImageTex(),
            "materials/mystery.tex" to staticImageTex(),
        ) { root ->
            val resources = WorkshopConverter.plan(root, MpkgConversionPreset.COMPATIBILITY, DEVICE)

            assertEquals(ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, resources.texture("materials/color.tex").strategy)
            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resources.texture("materials/mystery.tex").strategy)
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, resources.resource("materials/broken.material.json").strategy)
            assertEquals("material_graph_document_invalid", resources.resource("materials/broken.material.json").reason)
        }
    }

    private fun withScene(vararg entries: Pair<String, ByteArray>, block: (File) -> Unit) {
        val root = Files.createTempDirectory("wallhub-material-graph").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.pkg"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            writeDesktopPkg(root.resolve("scene.pkg"), entries.toList())
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun com.wallhub.prototype.mpkg.ConversionPlan.texture(path: String) = resource(path)

    private fun com.wallhub.prototype.mpkg.ConversionPlan.resource(path: String) = resources.single { it.path == path }

    private fun staticImageTex(width: Int = 2, height: Int = 2): ByteArray =
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

    private fun writeDesktopPkg(file: File, entries: List<Pair<String, ByteArray>>) {
        val magic = "PKGV0001".toByteArray(Charsets.US_ASCII)
        var offset = 0
        file.outputStream().use { output ->
            output.writeIntLe(magic.size)
            output.write(magic)
            output.writeIntLe(entries.size)
            entries.forEach { (path, payload) ->
                val encodedPath = path.toByteArray(Charsets.UTF_8)
                output.writeIntLe(encodedPath.size)
                output.write(encodedPath)
                output.writeIntLe(offset)
                output.writeIntLe(payload.size)
                offset = Math.addExact(offset, payload.size)
            }
            entries.forEach { (_, payload) -> output.write(payload) }
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
        val DEVICE = DeviceCapabilityProfile(256L * 1024 * 1024)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
