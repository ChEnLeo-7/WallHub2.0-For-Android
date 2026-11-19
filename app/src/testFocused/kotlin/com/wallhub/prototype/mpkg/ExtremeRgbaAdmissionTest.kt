package com.wallhub.prototype.mpkg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtremeRgbaAdmissionTest {
    @Test
    fun `synthetic large PNG metadata receives bounded RGBA admission without actual device fixtures`() {
        val root = java.nio.file.Files.createTempDirectory("extreme-rgba-bound").toFile()
        try {
            val source = File(root, "image.tex")
            val width = 6000
            val height = 4000
            val bytes = java.io.ByteArrayOutputStream()
            fun text(value: String) { bytes.write(value.toByteArray()); bytes.write(0) }
            fun le(value: Int) { repeat(4) { bytes.write(value ushr (it * 8) and 255) } }
            text("TEXV0005"); text("TEXI0001")
            listOf(0, 2, width, height, width, height, 0).forEach(::le)
            text("TEXB0004")
            listOf(1, 13, 0, 1, width, height, 0, 24, 24).forEach(::le)
            bytes.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 13))
            bytes.write("IHDR".toByteArray())
            for (dimension in listOf(width, height)) repeat(4) { bytes.write(dimension ushr ((3 - it) * 8) and 255) }
            source.writeBytes(bytes.toByteArray())
            val bounded = assertNotNull(TexMobileConverter.estimateExclusiveBoundedPeakBytes(
                source, 0, source.length(), ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
            ))
            assertTrue(bounded < 160L * 1024 * 1024)
            val pipeline = MemoryWeightedResourcePipeline(2, 160L * 1024 * 1024)
            pipeline.execute(listOf(ResourceWork(0, 400L * 1024 * 1024, Unit, bounded)), {}) { _, _ -> Unit }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `real extreme textures have a verified exclusive conversion bound`() {
        val root = File("../artifacts/extreme-memory/fixtures/files/wallhub-workshop")
        org.junit.Assume.assumeTrue("Real device fixtures are optional outside local diagnosis", root.isDirectory)
        val targets = listOf(
            "7368c80e-cf29-43e7-96d8-c3073757f7b7" to "materials/(1).tex",
            "3c6d2575-d197-4126-a2e5-78a79e049abc" to "materials/jadegirl.tex",
        )
        for ((task, path) in targets) {
            val source = File(root, "$task/scene.pkg")
            check(source.isFile) { "Local diagnostic fixture missing: $task" }
            val entry = PkgReader.readIndex(source).entries.single { it.path == path }
            val aggregate = TexMobileConverter.estimateConversionPeakBytes(
                source, entry.offset, entry.length, ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
            )
            assertTrue(aggregate > 160L * 1024 * 1024)
            val bounded = assertNotNull(TexMobileConverter.estimateExclusiveBoundedPeakBytes(
                source, entry.offset, entry.length, ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
            ), path)
            assertTrue(bounded < 160L * 1024 * 1024, "$path peak=$bounded")
            MemoryWeightedResourcePipeline(2, 160L * 1024 * 1024).execute(
                listOf(ResourceWork(0, aggregate, path, bounded)), {},
            ) { item, _ -> item.payload }
        }
    }
}
