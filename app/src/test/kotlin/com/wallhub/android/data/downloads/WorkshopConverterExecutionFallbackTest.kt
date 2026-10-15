package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.ConversionCheckpointRequest
import com.wallhub.prototype.mpkg.ConversionReportJsonCodec
import com.wallhub.prototype.mpkg.ConversionResourceCheckpoint
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.PkgReader
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class WorkshopConverterExecutionFallbackTest {
    @Test
    fun `planned texture conversion decoder failure passes source bytes through with stable warning`() {
        val source = staticImageTex(2, 2)
        withScene(listOf("materials/color.tex" to source)) { root ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.HIGH_QUALITY, DEVICE)
            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, plan.resources.single().strategy)

            val output = root.resolve("converted.mpkg")
            val report =
                WorkshopConverter.execute(
                    plan = plan,
                    inputDir = root,
                    outputFile = output,
                    staticImageDecoder = { _, _, _, _ -> error("unstable decoder detail") },
                )

            val archive = PkgReader.readIndex(output)
            val texture = archive.entries.single { it.path == "materials/color.tex" }
            assertArrayEquals(source, archive.readBytes(texture, source.size.toLong()))
            assertEquals(0, report.convertedTextures)
            assertEquals(1, report.copiedTextures)
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, report.outcomes.single().strategy)
            assertEquals("texture_conversion_failed", report.warningDetails.single().code)
            assertEquals("materials/color.tex", report.warningDetails.single().path)
            assertEquals(mapOf("texture_conversion_failed" to 1), report.warningCounts)
            assertFalse(report.warnings.single().contains("unstable decoder detail"))
        }
    }

    @Test
    fun `shader rewrite failure passes source bytes through with stable warning`() {
        val source = "void main() { vec4 color = vec4(1, 0, 0, 1); }".toByteArray()
        withScene(listOf("shaders/main.frag" to source)) { root ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE)
            val output = root.resolve("converted.mpkg")

            val report =
                WorkshopConverter.execute(
                    plan = plan,
                    inputDir = root,
                    outputFile = output,
                    shaderRewriter = { _, _ -> error("unstable rewrite detail") },
                )

            val archive = PkgReader.readIndex(output)
            val shader = archive.entries.single { it.path == "shaders/main.frag" }
            assertArrayEquals(source, archive.readBytes(shader, source.size.toLong()))
            assertEquals(0, report.rewrittenShaders)
            assertEquals(ResourceStrategy.PASSTHROUGH_WITH_WARNING, report.outcomes.single().strategy)
            assertEquals("shader_rewrite_failed", report.warningDetails.single().code)
            assertEquals("shaders/main.frag", report.warningDetails.single().path)
            assertFalse(report.warnings.single().contains("unstable rewrite detail"))
        }
    }

    @Test
    fun `interrupted execution reuses each already verified resource`() {
        val first = "void first() { gl_FragColor = vec4(1.0); }".toByteArray()
        val second = "void second() { gl_FragColor = vec4(0.0); }".toByteArray()
        withScene(listOf("shaders/first.frag" to first, "shaders/second.frag" to second)) { root ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE)
            val products = linkedMapOf<String, File>()
            val checkpoint =
                object : ConversionResourceCheckpoint {
                    override fun reuse(request: ConversionCheckpointRequest): File? = products[request.path]

                    override fun record(request: ConversionCheckpointRequest, verifiedOutput: File): File {
                        return root.resolve("checkpoint-${products.size}.frag").also {
                            verifiedOutput.copyTo(it, overwrite = true)
                            products[request.path] = it
                        }
                    }
                }
            var rewrites = 0
            assertThrows(CancellationException::class.java) {
                WorkshopConverter.execute(
                    plan,
                    root,
                    root.resolve("interrupted.mpkg"),
                    shaderRewriter = { source, _ ->
                        rewrites += 1
                        if (rewrites == 2) throw CancellationException("interrupted")
                        "$source\n// mobile"
                    },
                    resourceCheckpoint = checkpoint,
                )
            }
            assertEquals(setOf("shaders/first.frag"), products.keys)

            WorkshopConverter.execute(
                plan,
                root,
                root.resolve("resumed.mpkg"),
                shaderRewriter = { source, _ ->
                    rewrites += 1
                    "$source\n// mobile"
                },
                resourceCheckpoint = checkpoint,
            )
            assertEquals(3, rewrites)
            assertEquals(setOf("shaders/first.frag", "shaders/second.frag"), products.keys)
        }
    }

    @Test
    fun `warning details are path sorted capped at 100 and extra warnings are counted by code`() {
        val entries = (105 downTo 1).map { index -> "shaders/${index.toString().padStart(3, '0')}.frag" to "sample".toByteArray() }
        withScene(entries) { root ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE)

            val report =
                WorkshopConverter.execute(
                    plan = plan,
                    inputDir = root,
                    outputFile = root.resolve("converted.mpkg"),
                    shaderRewriter = { _, _ -> error("rewrite failed") },
                )

            assertEquals(100, report.warningDetails.size)
            assertEquals(report.warningDetails.sortedBy { it.path }, report.warningDetails)
            assertEquals("shaders/001.frag", report.warningDetails.first().path)
            assertEquals("shaders/100.frag", report.warningDetails.last().path)
            assertEquals(mapOf("shader_rewrite_failed" to 105), report.warningCounts)
            val persisted = JSONObject(ConversionReportJsonCodec.encode(report))
            assertEquals(2, persisted.getInt("version"))
            assertEquals(100, persisted.getJSONArray("warningDetails").length())
            assertEquals(105, persisted.getJSONObject("warningCounts").getInt("shader_rewrite_failed"))
            assertEquals(105, persisted.getInt("warningCount"))
            assertEquals("rewrite_shader", persisted.getJSONArray("warningDetails").getJSONObject(0).getString("strategy"))
            assertEquals(
                "rewrite_shader",
                ConversionReportJsonCodec.decodeSummary(persisted.toString())?.warningDetails?.first()?.strategy,
            )
        }
    }

    @Test
    fun `source truncation during execute is fatal and leaves no output`() {
        val source = "not-a-recognized-tex-shape".toByteArray()
        withScene(listOf("materials/source.tex" to source)) { root ->
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DEVICE)
            val output = root.resolve("converted.mpkg")
            var truncated = false

            assertThrows(Exception::class.java) {
                WorkshopConverter.execute(
                    plan = plan,
                    inputDir = root,
                    outputFile = output,
                    checkCancellation = {
                        if (!truncated) {
                            truncated = true
                            RandomAccessFile(root.resolve("scene.pkg"), "rw").use { it.setLength(1L) }
                        }
                    },
                )
            }
            assertFalse(output.exists())
        }
    }

    private fun withScene(entries: List<Pair<String, ByteArray>>, block: (File) -> Unit) {
        val root = Files.createTempDirectory("wallhub-execute-fallback").toFile()
        try {
            root.resolve("project.json").writeText("""{"type":"scene","file":"scene.pkg"}""")
            root.resolve("preview.png").writeBytes(PNG_SIGNATURE)
            writeDesktopPkg(root.resolve("scene.pkg"), entries)
            block(root)
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

    private fun writeDesktopPkg(file: File, entries: List<Pair<String, ByteArray>>) {
        val magic = "PKGV0001".toByteArray(Charsets.US_ASCII)
        val encodedPaths = entries.map { it.first.toByteArray(Charsets.UTF_8) }
        var offset = 0
        file.outputStream().use { output ->
            output.writeIntLe(magic.size)
            output.write(magic)
            output.writeIntLe(entries.size)
            entries.indices.forEach { index ->
                val payload = entries[index].second
                val path = encodedPaths[index]
                output.writeIntLe(path.size)
                output.write(path)
                output.writeIntLe(offset)
                output.writeIntLe(payload.size)
                offset += payload.size
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
        val DEVICE = DeviceCapabilityProfile(256L * 1024L * 1024L)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
