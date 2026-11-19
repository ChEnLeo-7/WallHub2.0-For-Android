package com.wallhub.prototype.mpkg

import com.wallhub.android.data.downloads.classifyConversionFailure

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkshopNoPlanMemoryAdmissionTest {
    @Test
    fun `public no-plan conversion admits shader using its rewrite weight`() {
        val root = Files.createTempDirectory("wallhub-no-plan-shader-admission").toFile()
        try {
            root.resolve("preview.jpg").writeBytes(byteArrayOf(1))
            root.resolve("project.json").writeText("{\"type\":\"scene\",\"file\":\"scene.pkg\"}")
            val budget = Runtime.getRuntime().maxMemory() * 5L / 8L
            val sourceBytes = ((budget - 1024L * 1024L) / 6L + 1L).coerceAtLeast(MAX_SHADER_SOURCE_BYTES + 1L)
            writeSingleSparseEntryPkg(root.resolve("scene.pkg"), "materials/synthetic.frag", sourceBytes)
            val output = root.resolve("output.mpkg")

            val error =
                assertFailsWith<IllegalArgumentException> {
                    WorkshopConverter.convert(root, output, "scene")
                }

            assertEquals("Resource work exceeds the conversion memory budget", error.message)
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `public no-plan conversion rejects ETC2 resource without a verified safe bound`() {
        val root = Files.createTempDirectory("wallhub-no-plan-admission").toFile()
        try {
            root.resolve("preview.jpg").writeBytes(byteArrayOf(1))
            root.resolve("project.json").writeText("{\"type\":\"scene\",\"file\":\"scene.pkg\"}")
            writeSingleEntryPkg(
                root.resolve("scene.pkg"),
                "materials/synthetic.tex",
                imageBackedTexMetadata(width = 100_000, height = 100_000),
            )
            val output = root.resolve("output.mpkg")

            val error =
                assertFailsWith<IllegalArgumentException> {
                    WorkshopConverter.convert(root, output, "scene")
                }

            assertEquals("Resource work exceeds the conversion memory budget", error.message)
            val failure = classifyConversionFailure(error)
            assertEquals("memory_budget", failure.reasonCode)
            assertTrue(failure.fallbackMessage.contains("materials/synthetic.tex"))
            assertTrue(failure.fallbackMessage.contains("CONVERT_TO_ETC2_RGBA8_LZ4"))
            assertTrue(failure.fallbackMessage.contains("100000x100000"))
            assertTrue(failure.fallbackMessage.contains("estimatedPeakBytes="))
            assertTrue(failure.fallbackMessage.contains("memoryBudgetBytes="))
            assertFalse(failure.fallbackMessage.contains(root.absolutePath))
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSingleSparseEntryPkg(file: File, path: String, payloadBytes: Long) {
        require(payloadBytes in 1..UInt.MAX_VALUE.toLong())
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.writeLengthString("PKGV0001")
            output.writeLe(1)
            output.writeLengthString(path)
            output.writeLe(0)
            output.writeLe(payloadBytes.toInt())
            output.setLength(output.filePointer + payloadBytes)
        }
    }

    private fun writeSingleEntryPkg(file: File, path: String, payload: ByteArray) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.writeLengthString("PKGV0001")
            output.writeLe(1)
            output.writeLengthString(path)
            output.writeLe(0)
            output.writeLe(payload.size)
            output.write(payload)
        }
    }

    private fun imageBackedTexMetadata(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.writeCString("TEXV0005")
            output.writeCString("TEXI0001")
            repeat(2) { output.writeLe(0) }
            output.writeLe(width)
            output.writeLe(height)
            output.writeLe(width)
            output.writeLe(height)
            output.writeLe(0)
            output.writeCString("TEXB0004")
            output.writeLe(1)
            output.writeLe(13)
            output.writeLe(0)
            output.writeLe(1)
            output.writeLe(width)
            output.writeLe(height)
            output.writeLe(0)
            output.writeLe(24)
            output.writeLe(24)
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            output.write(byteArrayOf(0, 0, 0, 13))
            output.write("IHDR".toByteArray(Charsets.US_ASCII))
            output.writeBe(width)
            output.writeBe(height)
            output.toByteArray()
        }

    private fun RandomAccessFile.writeLengthString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLe(bytes.size)
        write(bytes)
    }

    private fun RandomAccessFile.writeLe(value: Int) = writeInt(Integer.reverseBytes(value))

    private fun ByteArrayOutputStream.writeCString(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
        write(0)
    }

    private fun ByteArrayOutputStream.writeLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun ByteArrayOutputStream.writeBe(value: Int) {
        write(value ushr 24 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 8 and 0xff)
        write(value and 0xff)
    }
}
