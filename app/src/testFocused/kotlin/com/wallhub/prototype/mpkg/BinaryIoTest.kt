package com.wallhub.prototype.mpkg

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryIoTest {
    @Test
    fun `integer writer uses little endian byte order`() {
        val output = ByteArrayOutputStream()
        output.writeIntLe(0x01020304)

        assertEquals(listOf<Byte>(0x04, 0x03, 0x02, 0x01), output.toByteArray().toList())
    }

    @Test
    fun `long writer uses little endian byte order`() {
        val output = ByteArrayOutputStream()
        output.writeLongLe(0x0102030405060708L)

        assertEquals(
            listOf<Byte>(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            output.toByteArray().toList(),
        )
    }

    @Test
    fun `length string writes utf8 byte count then payload`() {
        val output = ByteArrayOutputStream()
        output.writeLengthString("hé")

        assertEquals(listOf<Byte>(0x03, 0x00, 0x00, 0x00, 'h'.code.toByte(), 0xC3.toByte(), 0xA9.toByte()), output.toByteArray().toList())
    }

    @Test
    fun `c string writes ascii payload and terminator`() {
        val output = ByteArrayOutputStream()
        output.writeCString("ab")

        assertEquals(listOf<Byte>(0x61, 0x62, 0x00), output.toByteArray().toList())
    }

    @Test
    fun `cursor reads little endian integer and advances`() {
        val cursor = ByteCursor(byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x7f))

        assertEquals(0x01020304, cursor.readIntLe())
        assertEquals(listOf<Byte>(0x7f), cursor.readBytes(1).toList())
        assertTrue(cursor.remainingBytes().isEmpty())
    }

    @Test
    fun `cursor rejects reads past the buffer`() {
        val cursor = ByteCursor(byteArrayOf(1, 2))

        assertFailsWith<EOFException> { cursor.readIntLe() }
    }

    @Test
    fun `cursor rejects negative lengths`() {
        val cursor = ByteCursor(byteArrayOf(1, 2))

        assertFailsWith<IllegalArgumentException> { cursor.readBytes(-1) }
        assertFailsWith<IllegalArgumentException> { cursor.skip(-1) }
    }

    @Test
    fun `cursor skip advances without copying`() {
        val cursor = ByteCursor(byteArrayOf(1, 2, 3, 4))
        cursor.skip(2)

        assertEquals(listOf<Byte>(3, 4), cursor.remainingBytes().toList())
    }

    @Test
    fun `cursor reads c string until terminator`() {
        val cursor = ByteCursor(byteArrayOf(0x61, 0x62, 0x00, 0x63))

        assertEquals("ab", cursor.readCString())
        assertEquals(listOf<Byte>(0x63), cursor.remainingBytes().toList())
    }

    @Test
    fun `cursor rejects unterminated or oversized c strings`() {
        assertFailsWith<EOFException> { ByteCursor(byteArrayOf(0x61, 0x62)).readCString() }
        assertFailsWith<IllegalArgumentException> { ByteCursor(byteArrayOf(0x61, 0x62, 0x63, 0x00)).readCString(maxLength = 2) }
    }

    @Test
    fun `random access helpers round trip written values`() {
        val root = Files.createTempDirectory("wallhub-binary-io").toFile()
        try {
            val file = root.resolve("values.bin")
            ByteArrayOutputStream().also {
                it.writeIntLe(7)
                it.writeLengthString("scene")
                file.writeBytes(it.toByteArray())
            }

            RandomAccessFile(file, "r").use { input ->
                assertEquals(7, input.readIntLe())
                assertEquals("scene", input.readLengthString(maxLength = 16))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `length string reader rejects a length above the declared maximum`() {
        val root = Files.createTempDirectory("wallhub-binary-limit").toFile()
        try {
            val file = root.resolve("limit.bin")
            ByteArrayOutputStream().also {
                it.writeIntLe(32)
                it.write(ByteArray(32))
                file.writeBytes(it.toByteArray())
            }

            RandomAccessFile(file, "r").use { input ->
                assertFailsWith<IllegalArgumentException> { input.readLengthString(maxLength = 8) }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
