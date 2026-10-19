package com.wallhub.prototype.mpkg

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpkgFormatScaleTest {
    @Test
    fun `writer and reader support more than fifty thousand small entries`() =
        withTempDirectory("mpkg-many-entries") { root ->
            val output = root.resolve("many.mpkg")
            val empty = ByteArrayPayload(byteArrayOf())
            val entries = List(50_001) { index -> MpkgInputEntry("items/$index", empty) }

            MpkgWriter.write(entries, output, SCENE_MPKG_MAGIC)
            val archive = PkgReader.readIndex(output)

            assertEquals(50_001, archive.entries.size)
            assertEquals("items/0", archive.entries.first().path)
        }

    @Test
    fun `writer and reader support generated index larger than sixteen MiB`() =
        withTempDirectory("mpkg-large-index") { root ->
            val output = root.resolve("large-index.mpkg")
            val pathLength = MpkgWriter.MAX_PATH_BYTES
            val count = 4_100
            val empty = ByteArrayPayload(byteArrayOf())
            val entries =
                List(count) { index ->
                    val suffix = index.toString().padStart(4, '0')
                    MpkgInputEntry("p".repeat(pathLength - suffix.length) + suffix, empty)
                }

            MpkgWriter.write(entries, output, SCENE_MPKG_MAGIC)
            assertTrue(output.length() > 16L * 1024L * 1024L)
            val archive = PkgReader.readIndex(output)

            assertEquals(count, archive.entries.size)
            assertEquals(pathLength, archive.entries.last().path.toByteArray(Charsets.UTF_8).size)
        }

    @Test
    fun `writer returns typed format limit before u32 payload overflow writes`() =
        withTempDirectory("mpkg-u32-limit") { root ->
            val output = root.resolve("overflow.mpkg")
            val virtualPayload = FilePayload(root.resolve("never-read"), 0x1_0000_0000L)

            val failure =
                runCatching {
                    MpkgWriter.write(listOf(MpkgInputEntry("huge", virtualPayload)), output, SCENE_MPKG_MAGIC)
                }.exceptionOrNull()

            assertTrue(failure is MpkgFormatLimitException)
            assertFalse(output.exists())
        }

    @Test
    fun `writer accepts exactly four KiB UTF-8 path and sorts normalized paths stably`() =
        withTempDirectory("mpkg-path-order") { root ->
            val output = root.resolve("ordered.mpkg")
            val exactPath = "z".repeat(MpkgWriter.MAX_PATH_BYTES)
            val entries =
                listOf(
                    MpkgInputEntry("b\\two", ByteArrayPayload(byteArrayOf(2))),
                    MpkgInputEntry(exactPath, ByteArrayPayload(byteArrayOf(3))),
                    MpkgInputEntry("A/one", ByteArrayPayload(byteArrayOf(1))),
                    MpkgInputEntry("a/One", ByteArrayPayload(byteArrayOf(4))),
                )

            val manifest = MpkgWriter.write(entries, output, SCENE_MPKG_MAGIC)
            val archive = PkgReader.readIndex(output)

            assertEquals(listOf("A/one", "a/One", "b/two", exactPath), manifest.entries.map { it.path })
            assertEquals(manifest.entries.map { it.path }, archive.entries.map { it.path })
        }

    @Test
    fun `writer rejects duplicate normalized paths before writing`() =
        withTempDirectory("mpkg-duplicate") { root ->
            val output = root.resolve("duplicate.mpkg")
            val entries =
                listOf(
                    MpkgInputEntry("folder\\item", ByteArrayPayload(byteArrayOf())),
                    MpkgInputEntry("folder/item", ByteArrayPayload(byteArrayOf())),
                )

            val failure = runCatching { MpkgWriter.write(entries, output, SCENE_MPKG_MAGIC) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("Duplicate"))
            assertFalse(output.exists())
        }

    @Test
    fun `reader rejects impossible malicious count without allocation`() =
        withTempDirectory("mpkg-malicious-count") { root ->
            val source = root.resolve("count.mpkg")
            writeHeader(source, Int.MAX_VALUE)

            val failure = runCatching { PkgReader.readIndex(source) }.exceptionOrNull()

            assertTrue(failure is PkgFormatException)
            assertTrue(failure?.message.orEmpty().contains("cannot fit"))
        }

    @Test
    fun `reader distinguishes truncated source from impossible format`() =
        withTempDirectory("mpkg-truncated") { root ->
            val source = root.resolve("truncated.mpkg")
            BufferedOutputStream(FileOutputStream(source)).use { stream ->
                val magic = SCENE_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)
                stream.writeIntLe(magic.size)
                stream.write(magic)
                stream.writeIntLe(1)
                stream.writeIntLe(4)
                stream.write(byteArrayOf('a'.code.toByte()))
                stream.write(ByteArray(8))
            }

            val failure = runCatching { PkgReader.readIndex(source) }.exceptionOrNull()

            assertTrue(failure is PkgSourceException)
        }

    @Test
    fun `reader rejects u32 range beyond source without creating sparse four GiB file`() =
        withTempDirectory("mpkg-u32-offset") { root ->
            val source = root.resolve("offset.mpkg")
            BufferedOutputStream(FileOutputStream(source)).use { stream ->
                val magic = SCENE_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)
                stream.writeIntLe(magic.size)
                stream.write(magic)
                stream.writeIntLe(1)
                stream.writeIntLe(1)
                stream.write('x'.code)
                stream.writeIntLe(-1)
                stream.writeIntLe(1)
            }

            val failure = runCatching { PkgReader.readIndex(source) }.exceptionOrNull()

            assertTrue(failure is PkgSourceException)
            assertTrue(failure?.message.orEmpty().contains("offset"))
        }

    private fun writeHeader(
        file: File,
        count: Int,
    ) {
        BufferedOutputStream(FileOutputStream(file)).use { stream ->
            val magic = SCENE_MPKG_MAGIC.toByteArray(Charsets.US_ASCII)
            stream.writeIntLe(magic.size)
            stream.write(magic)
            stream.writeIntLe(count)
        }
    }

    private fun withTempDirectory(
        prefix: String,
        block: (File) -> Unit,
    ) {
        val root = Files.createTempDirectory(prefix).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
