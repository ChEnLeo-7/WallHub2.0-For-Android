package com.wallhub.prototype.mpkg

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.io.RandomAccessFile

internal fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write(value ushr 8 and 0xff)
    write(value ushr 16 and 0xff)
    write(value ushr 24 and 0xff)
}

internal fun OutputStream.writeLongLe(value: Long) {
    repeat(8) { shift -> write((value ushr (shift * 8) and 0xff).toInt()) }
}

internal fun OutputStream.writeLengthString(value: String) {
    val data = value.toByteArray(Charsets.UTF_8)
    writeIntLe(data.size)
    write(data)
}

internal fun OutputStream.writeCString(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
    write(0)
}

internal fun RandomAccessFile.readIntLe(): Int = Integer.reverseBytes(readInt())

internal fun RandomAccessFile.readUIntLe(): Long = Integer.toUnsignedLong(readIntLe())

internal fun RandomAccessFile.readLengthString(maxLength: Int): String {
    val size = readIntLe()
    require(size in 0..maxLength) { "Invalid string length: $size" }
    val data = ByteArray(size)
    readFully(data)
    return data.toString(Charsets.UTF_8)
}

internal class ByteCursor(
    private val data: ByteArray,
) {
    private var position = 0

    fun readIntLe(): Int {
        ensureAvailable(4)
        val value =
            (data[position].toInt() and 0xff) or
                ((data[position + 1].toInt() and 0xff) shl 8) or
                ((data[position + 2].toInt() and 0xff) shl 16) or
                ((data[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Negative binary length" }
        ensureAvailable(length)
        return data.copyOfRange(position, position + length).also { position += length }
    }

    fun skip(length: Int) {
        require(length >= 0) { "Negative binary length" }
        ensureAvailable(length)
        position += length
    }

    fun readCString(maxLength: Int = 64): String {
        val start = position
        while (position < data.size && data[position].toInt() != 0) {
            position += 1
            require(position - start <= maxLength) { "C string is too long" }
        }
        if (position >= data.size) throw EOFException("Unterminated C string")
        val result = data.copyOfRange(start, position).toString(Charsets.US_ASCII)
        position += 1
        return result
    }

    fun remainingBytes(): ByteArray = data.copyOfRange(position, data.size)

    private fun ensureAvailable(length: Int) {
        if (position + length > data.size) throw EOFException("Unexpected end of binary data")
    }
}

internal fun byteArrayOutput(block: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().use { output ->
        output.block()
        output.toByteArray()
    }
