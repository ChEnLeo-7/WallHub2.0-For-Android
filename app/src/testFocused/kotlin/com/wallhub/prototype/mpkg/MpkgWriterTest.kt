package com.wallhub.prototype.mpkg

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpkgWriterTest {
    @Test
    fun `entry paths are normalized to forward slashes`() {
        assertEquals("materials/scene.json", MpkgWriter.normalizePath("materials\\scene.json"))
        assertEquals("materials/scene.json", MpkgWriter.normalizePath("  materials/scene.json  "))
        assertEquals("scene.pkg", MpkgWriter.normalizePath("scene.pkg"))
    }

    @Test
    fun `entry paths cannot escape the package`() {
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("../escape.pkg") }
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("materials/../../escape.pkg") }
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("/absolute.pkg") }
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("materials//scene.json") }
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("materials/./scene.json") }
        assertFailsWith<IllegalArgumentException> { MpkgWriter.normalizePath("   ") }
    }

    @Test
    fun `writer lays out header index and payload in path order`() {
        val root = Files.createTempDirectory("wallhub-mpkg-write").toFile()
        try {
            val output = root.resolve("scene.mpkg")
            val manifest =
                MpkgWriter.write(
                    entries =
                        listOf(
                            MpkgInputEntry("b.txt", ByteArrayPayload("xyz".toByteArray())),
                            MpkgInputEntry("A.txt", ByteArrayPayload("ab".toByteArray())),
                        ),
                    outputFile = output,
                    magic = SCENE_MPKG_MAGIC,
                )

            assertEquals(SCENE_MPKG_MAGIC, manifest.magic)
            assertEquals(listOf("A.txt", "b.txt"), manifest.entries.map { it.path })
            assertEquals(listOf(0L, 2L), manifest.entries.map { it.offset })
            assertEquals(listOf(2L, 3L), manifest.entries.map { it.length })

            val cursor = ByteCursor(output.readBytes())
            assertEquals(SCENE_MPKG_MAGIC.length, cursor.readIntLe())
            assertEquals(
                SCENE_MPKG_MAGIC,
                cursor.readBytes(SCENE_MPKG_MAGIC.length).toString(Charsets.US_ASCII),
            )
            assertEquals(2, cursor.readIntLe())
            repeat(2) { index ->
                val pathLength = cursor.readIntLe()
                val path = cursor.readBytes(pathLength).toString(Charsets.UTF_8)
                assertEquals(manifest.entries[index].path, path)
                assertEquals(manifest.entries[index].offset, cursor.readIntLe().toLong())
                assertEquals(manifest.entries[index].length, cursor.readIntLe().toLong())
            }
            assertEquals("abxyz", cursor.remainingBytes().toString(Charsets.US_ASCII))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writer rejects duplicate paths after normalization`() {
        val root = Files.createTempDirectory("wallhub-mpkg-duplicate").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                MpkgWriter.write(
                    entries =
                        listOf(
                            MpkgInputEntry("materials/scene.json", ByteArrayPayload(byteArrayOf(1))),
                            MpkgInputEntry("materials\\scene.json ", ByteArrayPayload(byteArrayOf(2))),
                        ),
                    outputFile = root.resolve("duplicate.mpkg"),
                    magic = SCENE_MPKG_MAGIC,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writer rejects a path above the format limit`() {
        val root = Files.createTempDirectory("wallhub-mpkg-path-limit").toFile()
        try {
            val longPath = "x".repeat(MpkgWriter.MAX_PATH_BYTES + 1)
            assertFailsWith<MpkgFormatLimitException> {
                MpkgWriter.write(
                    entries = listOf(MpkgInputEntry(longPath, ByteArrayPayload(byteArrayOf(1)))),
                    outputFile = root.resolve("long.mpkg"),
                    magic = SCENE_MPKG_MAGIC,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writer rejects a magic above the format limit`() {
        val root = Files.createTempDirectory("wallhub-mpkg-magic-limit").toFile()
        try {
            val oversizedMagic = "PKGM" + "0".repeat(MpkgWriter.MAX_PATH_BYTES)
            assertFailsWith<MpkgFormatLimitException> {
                MpkgWriter.write(
                    entries = emptyList(),
                    outputFile = root.resolve("magic.mpkg"),
                    magic = oversizedMagic,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writer propagates cancellation and leaves no partial output`() {
        val root = Files.createTempDirectory("wallhub-mpkg-cancel").toFile()
        try {
            val output = root.resolve("cancelled.mpkg")
            assertFailsWith<IllegalStateException> {
                MpkgWriter.write(
                    entries = listOf(MpkgInputEntry("a.txt", ByteArrayPayload(byteArrayOf(1)))),
                    outputFile = output,
                    magic = SCENE_MPKG_MAGIC,
                    checkCancellation = { throw IllegalStateException("cancelled") },
                )
            }
            assertTrue(!output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writer rejects a magic without the pkgm prefix`() {
        val root = Files.createTempDirectory("wallhub-mpkg-magic").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                MpkgWriter.write(
                    entries = emptyList(),
                    outputFile = root.resolve("bad.mpkg"),
                    magic = "NOTPKG",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `file payload validates its declared length`() {
        val root = Files.createTempDirectory("wallhub-mpkg-payload").toFile()
        try {
            val source = root.resolve("source.bin")
            source.writeBytes(ByteArray(4))
            assertFailsWith<IllegalArgumentException> { FilePayload(source, length = 5L) }
        } finally {
            root.deleteRecursively()
        }
    }
}
