package com.wallhub.prototype.mpkg

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.data.downloads.executeRegeneratingStalePlan
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import net.jpountz.lz4.LZ4Factory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TexConversionMemoryModelTest {
    @Test
    fun `planned R8 depth texture is admitted by execution budget`() {
        val root = Files.createTempDirectory("wallhub-r8-admission").toFile()
        try {
            root.resolve("preview.jpg").writeBytes(byteArrayOf(1))
            root.resolve("project.json").writeText("{\"type\":\"scene\",\"file\":\"scene.pkg\"}")
            val source = root.resolve("scene.pkg")
            val path = "materials/jadebackground_depth.tex"
            val length = 1_612_725
            RandomAccessFile(source, "rw").use { output ->
                fun le(value: Int) = output.writeInt(Integer.reverseBytes(value))
                fun text(value: String) {
                    val bytes = value.toByteArray(Charsets.US_ASCII)
                    le(bytes.size)
                    output.write(bytes)
                }
                fun cstring(value: String) {
                    output.write(value.toByteArray(Charsets.US_ASCII))
                    output.write(0)
                }
                text("PKGV0001")
                le(1)
                text(path)
                le(0)
                le(length)
                val start = output.filePointer
                cstring("TEXV0005")
                cstring("TEXI0001")
                listOf(9, 2, 5096, 2867, 5096, 2867, 0).forEach(::le)
                cstring("TEXB0004")
                listOf(1, -1, 0, 1, 5096, 2867, 1, 14_610_232, 1_612_634).forEach(::le)
                output.setLength(start + length)
            }
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.HIGH_QUALITY, DeviceCapabilityProfile(256L * 1024 * 1024))
            val resource = plan.resources.single()
            assertEquals(ResourceStrategy.CONVERT_TO_RGBA8888_LZ4, resource.strategy)
            val entry = PkgReader.readIndex(source).entries.single()
            val peak = TexMobileConverter.estimateConversionPeakBytes(source, entry.offset, entry.length, resource.strategy)
            val admitted = MemoryWeightedResourcePipeline(2, 160L * 1024 * 1024)
                .execute(listOf(ResourceWork(0, peak, path)), {}) { item, _ -> item.payload }
            assertEquals(listOf(path), admitted)
            // A v6 journal carries the strategy and device profile, not an old memory estimate.
            val restoredPlan = ConversionPlanJsonCodec.decode(ConversionPlanJsonCodec.encode(plan))
            assertFailsWith<CancellationException> {
                WorkshopConverter.execute(restoredPlan, root, root.resolve("output.mpkg"), "scene", { throw CancellationException("stop before decode") })
            }
            var generations = 0
            val stalePlan = restoredPlan.copy(resources = listOf(resource.copy(reason = "old_strategy_reason")))
            assertFailsWith<CancellationException> {
                executeRegeneratingStalePlan(
                    initialPlan = stalePlan,
                    generate = { generations++; plan },
                    execute = { current ->
                        WorkshopConverter.execute(current, root, root.resolve("output.mpkg"), "scene", { throw CancellationException("stop before decode") })
                    },
                )
            }
            assertEquals(1, generations)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `target shaped JPEG TEX reproduces aggregate rejection but has safe strip peak`() {
        val root = Files.createTempDirectory("wallhub-target-shaped-memory-model").toFile()
        try {
            val source = root.resolve("synthetic-target.tex")
            writeSparseImageBackedTex(source, width = 6144, height = 4096, entryBytes = 41_532_756L)

            val aggregate =
                TexMobileConverter.estimateConversionPeakBytes(
                    source,
                    0,
                    source.length(),
                    ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                )
            val bounded =
                assertNotNull(
                    TexMobileConverter.estimateExclusiveBoundedPeakBytes(
                        source,
                        0,
                        source.length(),
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                    ),
                )

            assertEquals(214_547_796L, aggregate)
            assertEquals(72_482_816L, bounded)
            assertTrue(aggregate > 160L * 1024L * 1024L)
            assertTrue(bounded < 160L * 1024L * 1024L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `8192 by 6144 ETC2 strip conversion has a bounded exclusive peak below a 160 MiB budget`() {
        val root = Files.createTempDirectory("wallhub-etc2-memory-model").toFile()
        try {
            val source = root.resolve("synthetic-8192.tex")
            source.writeBytes(imageBackedTexMetadata(width = 8192, height = 6144))

            val aggregate =
                TexMobileConverter.estimateConversionPeakBytes(
                    source,
                    0,
                    source.length(),
                    ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                )
            val bounded =
                assertNotNull(
                    TexMobileConverter.estimateExclusiveBoundedPeakBytes(
                        source,
                        0,
                        source.length(),
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                    ),
                )

            assertTrue(aggregate > 160L * 1024L * 1024L)
            // max(48 MiB ETC2 output + 60 MiB strip, 48 MiB input + LZ4 max output + 8 MiB workspace)
            assertEquals(113_377_280L, bounded)
            assertTrue(bounded < 160L * 1024L * 1024L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop DXT animation remains recognized by file inspection`() {
        val root = Files.createTempDirectory("wallhub-dxt-probe").toFile()
        try {
            val source = root.resolve("animated.tex")
            val bytes = ByteArrayOutputStream().use { output ->
                output.writeCString("TEXV0005")
                output.writeCString("TEXI0001")
                listOf(7, 4, 4, 4, 4, 4, 0).forEach { output.writeIntLe(it) }
                output.writeCString("TEXB0004")
                listOf(1, -1, 0, 1, 4, 4, 0, 8, 8).forEach { output.writeIntLe(it) }
                output.write(ByteArray(8))
                output.writeCString("TEXS0003")
                listOf(1, 4, 4).forEach { output.writeIntLe(it) }
                output.write(ByteArray(32))
                output.toByteArray()
            }
            source.writeBytes(bytes)
            assertEquals(TexRuntimeDesktopFamily.STANDARD_BLOCK_COMPRESSED_ANIMATED, TexMobileConverter.classifyRuntimeDesktopFamily(bytes))
            assertEquals(TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP, TexMobileConverter.inspectEntryRoute(source, 0, source.length()))
            val conditional = ByteArrayOutputStream().use { output ->
                output.writeCString("TEXV0005")
                output.writeCString("TEXI0001")
                listOf(7, 0, 4, 4, 4, 4, 0).forEach { output.writeIntLe(it) }
                output.writeCString("TEXB0004")
                listOf(1, -1, 1, 1, 1, 0).forEach { output.writeIntLe(it) }
                output.writeCString("{\"condition\":{\"condition\":\"x\",\"name\":\"x\"}}")
                listOf(1, 4, 4, 0, 8, 8).forEach { output.writeIntLe(it) }
                output.write(ByteArray(8))
                listOf(1, 0, 0, 0, 0, 0, 4, 4, 0, 1).forEach { output.writeIntLe(it) }
                output.write(0)
                output.toByteArray()
            }
            source.writeBytes(conditional)
            assertEquals(TexRuntimeDesktopFamily.CONDITIONAL_VARIANT_EXTENDED, TexMobileConverter.classifyRuntimeDesktopFamily(conditional))
            assertEquals(TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP, TexMobileConverter.inspectEntryRoute(source, 0, source.length()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `static R8 conversion preserves cropped pixels through LZ4`() {
        val root = Files.createTempDirectory("wallhub-r8-output").toFile()
        try {
            val source = root.resolve("source.tex")
            val pixels = ByteArray(16) { it.toByte() }
            val compressed = LZ4Factory.fastestJavaInstance().fastCompressor().compress(pixels)
            val header = ByteArrayOutputStream().use { output ->
                output.writeCString("TEXV0005")
                output.writeCString("TEXI0001")
                listOf(9, 2, 4, 4, 2, 2, 0).forEach { output.writeIntLe(it) }
                output.writeCString("TEXB0004")
                listOf(1, -1, 0, 1, 4, 4, 1, pixels.size, compressed.size).forEach { output.writeIntLe(it) }
                output.write(compressed)
                output.toByteArray()
            }
            source.writeBytes(header)
            val result = root.resolve("output.tex")
            assertTrue(TexMobileConverter.convertStaticToRgbaFile(source, 0, source.length(), result).converted)
            val converted = result.readBytes()
            val decoded = ByteArray(16)
            LZ4Factory.fastestJavaInstance().safeDecompressor().decompress(converted, 91, converted.size - 91, decoded, 0, decoded.size)
            assertContentEquals(byteArrayOf(0, 0, 0, -1, 1, 1, 1, -1, 4, 4, 4, -1, 5, 5, 5, -1), decoded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `static texture weights include padded decode and crop for each native format`() {
        val root = Files.createTempDirectory("wallhub-static-weights").toFile()
        try {
            for ((format, decompressed) in listOf(0 to 67_108_864, 7 to 8_388_608, 8 to 33_554_432, 9 to 16_777_216)) {
                val source = root.resolve("$format.tex")
                writeSparseNativeTex(source, format, 4096, 4096, 1024, 1024, decompressed)
                val peak = TexMobileConverter.estimateConversionPeakBytes(source, 0, source.length(), ResourceStrategy.CONVERT_TO_RGBA8888_LZ4)
                assertTrue(peak > 75L * 1024 * 1024, "format $format must include the base-size allocation")
                val heapAtBoundary = (peak * 8 + 4) / 5
                assertTrue(TexMobileConverter.staticConversionFitsBudget(format, 4096, 4096, 1024, 1024, 1000, 909, decompressed.toLong(), heapAtBoundary))
                assertTrue(!TexMobileConverter.staticConversionFitsBudget(format, 4096, 4096, 1024, 1024, 1000, 909, decompressed.toLong(), heapAtBoundary - 2))
                val pipeline = MemoryWeightedResourcePipeline(2, peak)
                assertEquals(listOf(format), pipeline.execute(listOf(ResourceWork(0, peak, format)), {}) { item, _ -> item.payload })
                assertFailsWith<ResourceMemoryBudgetException> {
                    MemoryWeightedResourcePipeline(2, peak - 1).execute(listOf(ResourceWork(0, peak, format)), {}) { item, _ -> item.payload }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid enormous texture dimensions cannot be admitted`() {
        val root = Files.createTempDirectory("wallhub-overflow-weights").toFile()
        try {
            val source = root.resolve("overflow.tex")
            writeSparseNativeTex(source, 9, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 1)
            assertEquals(Long.MAX_VALUE, TexMobileConverter.estimateConversionPeakBytes(source, 0, source.length(), ResourceStrategy.CONVERT_TO_RGBA8888_LZ4))
            assertTrue(!TexMobileConverter.staticConversionFitsBudget(9, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 1000, 909, 1, 256L * 1024 * 1024))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSparseNativeTex(file: java.io.File, format: Int, baseWidth: Int, baseHeight: Int, width: Int, height: Int, decodedBytes: Int) {
        val header = ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            listOf(format, 2, baseWidth, baseHeight, width, height, 0).forEach { output.writeIntLe(it) }
            output.writeCString("TEXB0004")
            listOf(1, -1, 0, 1, baseWidth, baseHeight, 1, decodedBytes, 909).forEach { output.writeIntLe(it) }
            output.toByteArray()
        }
        RandomAccessFile(file, "rw").use { output ->
            output.write(header)
            output.setLength(1000)
        }
    }

    private fun writeSparseImageBackedTex(file: java.io.File, width: Int, height: Int, entryBytes: Long) {
        val headerBytes = imageBackedTexMetadata(width, height, storedBytes = Math.toIntExact(entryBytes - 91L))
        RandomAccessFile(file, "rw").use { output ->
            output.write(headerBytes)
            output.setLength(entryBytes)
        }
    }

    private fun imageBackedTexMetadata(width: Int, height: Int, storedBytes: Int = 24): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            output.writeIntLe(0)
            output.writeIntLe(0)
            output.writeIntLe(width)
            output.writeIntLe(height)
            output.writeIntLe(width)
            output.writeIntLe(height)
            output.writeIntLe(0)
            output.writeCString("TEXB0004")
            output.writeIntLe(1)
            output.writeIntLe(2)
            output.writeIntLe(0)
            output.writeIntLe(1)
            output.writeIntLe(width)
            output.writeIntLe(height)
            output.writeIntLe(0)
            output.writeIntLe(storedBytes)
            output.writeIntLe(storedBytes)
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            output.write(byteArrayOf(0, 0, 0, 13))
            output.write("IHDR".toByteArray(Charsets.US_ASCII))
            output.writeIntBe(width)
            output.writeIntBe(height)
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
