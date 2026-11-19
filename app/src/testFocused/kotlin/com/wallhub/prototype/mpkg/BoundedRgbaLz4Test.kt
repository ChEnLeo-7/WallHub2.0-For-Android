package com.wallhub.prototype.mpkg

import net.jpountz.lz4.LZ4Factory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedRgbaLz4Test {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `chunk boundaries produce one raw block accepted by fast and safe decompressors`() {
        val chunk = 1024 * 1024
        for (size in listOf(0, 1, 4, 5, 12, 15, 270, chunk - 1, chunk, chunk + 1, chunk + 4, chunk + 12, chunk * 2)) {
            val raw = ByteArray(size) { (it % 251).toByte() }
            assertRoundTrip(raw)
        }
    }

    @Test
    fun `literal only input spans many chunks without terminating the raw block early`() {
        // Every four-byte word is unique, so the fast compressor cannot emit a match.
        val raw = literalOnlyBytes(3 * 1024 * 1024 + 31)
        val compressed = assertRoundTrip(raw)
        assertTrue(compressed.size > raw.size)
    }

    @Test
    fun `long repeats remain compressed across chunks and final literal only tail`() {
        val raw = ByteArray(3 * 1024 * 1024 + 4) { 0x5a }
        val compressed = assertRoundTrip(raw)
        assertTrue(compressed.size < raw.size / 100)
    }

    @Test
    fun `deferred literals join the next chunk first match and preserve alpha bytes`() {
        val chunk = 1024 * 1024
        val raw = literalOnlyBytes(chunk * 3 + 29)
        raw.fill(0x61, chunk * 2 + 500, chunk * 3)
        assertRoundTrip(raw)
    }

    @Test
    fun `incompressible random bytes round trip across chunk and extension boundaries`() {
        val random = Random(1942)
        for (size in listOf(14, 15, 16, 269, 270, 271, 1024 * 1024 + 17, 3 * 1024 * 1024 + 7)) {
            assertRoundTrip(random.nextBytes(size))
        }
    }

    @Test
    fun `cancellation after writing starts deletes partial output and keeps source`() {
        val source = temporaryFolder.newFile().apply { writeBytes(literalOnlyBytes(3 * 1024 * 1024)) }
        val output = File(temporaryFolder.root, "cancelled.lz4")
        val cancellation = CancellationException("cancel conversion")
        var pollsAfterOpening = 0
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                BoundedRgbaLz4.compress(source, output) {
                    if (output.exists() && ++pollsAfterOpening == 45) throw cancellation
                }
            },
        )
        assertFalse(output.exists())
        assertEquals(3L * 1024 * 1024, source.length())
    }

    @Test
    fun `cancellation while streaming deferred literal data deletes partial output`() {
        val source = temporaryFolder.newFile().apply { writeBytes(literalOnlyBytes(3 * 1024 * 1024)) }
        val output = File(temporaryFolder.root, "cancelled-copy.lz4")
        assertFailsWith<CancellationException> {
            BoundedRgbaLz4.compress(source, output) {
                if (output.length() > 128 * 1024) throw CancellationException("cancel literal copy")
            }
        }
        assertFalse(output.exists())
    }

    @Test
    fun `source read failure deletes partial output`() {
        val source = temporaryFolder.newFile().apply { writeBytes(ByteArray(2 * 1024 * 1024) { 5 }) }
        val output = File(temporaryFolder.root, "truncated.lz4")
        var truncated = false
        assertFailsWith<IOException> {
            BoundedRgbaLz4.compress(source, output) {
                if (!truncated && output.exists()) {
                    source.writeBytes(byteArrayOf(1))
                    truncated = true
                }
            }
        }
        assertFalse(output.exists())
    }

    @Test
    fun `invalid source and same file output fail without changing existing files`() {
        val source = temporaryFolder.newFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val original = source.readBytes()
        assertFailsWith<IllegalArgumentException> { BoundedRgbaLz4.compress(source, source) }
        assertContentEquals(original, source.readBytes())
        assertFailsWith<IllegalArgumentException> {
            BoundedRgbaLz4.compress(File(temporaryFolder.root, "missing"), source)
        }
        assertContentEquals(original, source.readBytes())
    }

    private fun literalOnlyBytes(size: Int): ByteArray =
        ByteArray(size) { index ->
            // Low seven bits only, with the high bit marking each counter's low byte.
            // A four-byte window identifies its alignment and its unique counter.
            val counter = index / 4
            val shift = (index % 4) * 7
            ((counter ushr shift and 0x7f) or if (index % 4 == 0) 0x80 else 0).toByte()
        }

    private fun assertRoundTrip(raw: ByteArray): ByteArray {
        val source = temporaryFolder.newFile()
        val output = temporaryFolder.newFile()
        source.writeBytes(raw)
        val length = BoundedRgbaLz4.compress(source, output)
        val compressed = output.readBytes()
        assertEquals(compressed.size.toLong(), length)
        val factory = LZ4Factory.fastestJavaInstance()
        val fastDecoded = ByteArray(raw.size)
        assertEquals(compressed.size, factory.fastDecompressor().decompress(compressed, 0, fastDecoded, 0, raw.size))
        assertContentEquals(raw, fastDecoded, "fast decoder changed ${raw.size} bytes")
        val safeDecoded = ByteArray(raw.size)
        assertEquals(raw.size, factory.safeDecompressor().decompress(compressed, 0, compressed.size, safeDecoded, 0, raw.size))
        assertContentEquals(raw, safeDecoded, "safe decoder changed ${raw.size} bytes")
        assertContentEquals(raw, source.readBytes(), "compression changed its source")
        return compressed
    }
}
