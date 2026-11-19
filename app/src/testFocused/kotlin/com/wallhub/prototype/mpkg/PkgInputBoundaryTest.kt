package com.wallhub.prototype.mpkg

import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PkgInputBoundaryTest {
    @Test
    fun `entry count above the package limit is rejected before reading entries`() {
        val root = Files.createTempDirectory("wallhub-pkg-count").toFile()
        try {
            val source = root.resolve("scene.pkg")
            val count = PkgReader.MAX_ENTRY_COUNT + 1
            RandomAccessFile(source, "rw").use { output ->
                output.writeInt(Integer.reverseBytes(8))
                output.write("PKGV0001".toByteArray(Charsets.US_ASCII))
                output.writeInt(Integer.reverseBytes(count))
                output.setLength(output.filePointer + count.toLong() * 12L)
            }

            val error = assertFailsWith<PkgFormatException> { PkgReader.readIndex(source) }

            assertTrue(error.message.orEmpty().contains("entry count"))
            assertEquals(16L + count.toLong() * 12L, source.length())
        } finally {
            root.deleteRecursively()
        }
    }
}
