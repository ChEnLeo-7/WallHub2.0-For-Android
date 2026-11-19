package com.wallhub.prototype.mpkg

import net.jpountz.lz4.LZ4Factory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Encodes a file as one raw LZ4 block without a full-size heap buffer or memory mapping. */
internal object BoundedRgbaLz4 {
    const val CHUNK_BYTES = 1024 * 1024

    /** Input, maximum compressed chunk, two copy/output buffers, compressor table and object overhead. */
    const val WORKSPACE_BYTES = 4L * 1024 * 1024

    private const val COPY_BYTES = 64 * 1024
    private const val LENGTH_MASK = 15
    private const val LENGTH_EXTENSION = 255
    private const val MIN_MATCH_BYTES = 4

    /** Returns the encoded length; the caller owns [output], which is removed if encoding fails. */
    // Cleanup must also run for cancellation and allocation failures without replacing their cause.
    @Suppress("TooGenericExceptionCaught")
    fun compress(
        source: File,
        output: File,
        checkCancellation: () -> Unit = {},
    ): Long {
        require(source.isFile) { "RGBA source must be a regular file" }
        require(source.canonicalFile != output.canonicalFile) { "RGBA source and output must be different files" }
        val sourceLength = source.length()
        require(sourceLength <= Int.MAX_VALUE) { "RGBA source exceeds the raw LZ4 block limit" }
        var outputOpened = false
        try {
            checkCancellation()
            RandomAccessFile(source, "r").use { input ->
                FileOutputStream(output).use { fileOutput ->
                    outputOpened = true
                    writeBlock(input, fileOutput, sourceLength, checkCancellation)
                }
            }
            return output.length()
        } catch (failure: Throwable) {
            if (outputOpened && output.exists() && !output.delete()) {
                failure.addSuppressed(IllegalStateException("Could not remove partial RGBA LZ4 output"))
            }
            throw failure
        }
    }

    private fun writeBlock(
        input: RandomAccessFile,
        output: OutputStream,
        sourceLength: Long,
        checkCancellation: () -> Unit,
    ) {
        val compressor = LZ4Factory.fastestJavaInstance().fastCompressor()
        val raw = ByteArray(CHUNK_BYTES)
        val compressed = ByteArray(compressor.maxCompressedLength(CHUNK_BYTES))
        BufferedOutputStream(output, COPY_BYTES).use { destination ->
            val writer = BlockWriter(input, destination, checkCancellation)
            var offset = 0L
            while (offset < sourceLength) {
                checkCancellation()
                val length = minOf(CHUNK_BYTES.toLong(), sourceLength - offset).toInt()
                input.seek(offset)
                var read = 0
                while (read < length) {
                    checkCancellation()
                    val count = minOf(COPY_BYTES, length - read)
                    input.readFully(raw, read, count)
                    read += count
                }
                val compressedLength = compressor.compress(raw, 0, length, compressed, 0, compressed.size)
                checkCancellation()
                writer.appendChunk(compressed, compressedLength, offset, length)
                offset += length
            }
            check(input.length() == sourceLength) { "RGBA source size changed during compression" }
            writer.finish()
            checkCancellation()
        }
    }

    private class BlockWriter(
        private val input: RandomAccessFile,
        private val output: OutputStream,
        private val checkCancellation: () -> Unit,
    ) {
        private val copyBuffer = ByteArray(COPY_BYTES)
        private var pendingOffset = 0L
        private var pendingLength = 0L

        fun appendChunk(
            compressed: ByteArray,
            compressedLength: Int,
            sourceOffset: Long,
            rawLength: Int,
        ) {
            var position = 0
            var decoded = 0L
            while (position < compressedLength) {
                checkCancellation()
                val sequenceStart = position
                val token = compressed[position++].toInt() and 0xff
                var literalLength = token ushr MIN_MATCH_BYTES
                if (literalLength == LENGTH_MASK) {
                    var extension: Int
                    do {
                        extension = compressed[position++].toInt() and 0xff
                        literalLength += extension
                    } while (extension == LENGTH_EXTENSION)
                }
                val literalStart = position
                position += literalLength
                check(position <= compressedLength) { "Invalid compressed chunk literal length" }
                if (position == compressedLength) {
                    deferLiterals(sourceOffset + decoded, literalLength)
                    check(decoded + literalLength == rawLength.toLong()) { "Invalid compressed chunk decoded length" }
                    return
                }
                position += 2 // The match offset stays valid: all matches refer within this chunk.
                var matchLength = token and LENGTH_MASK
                if (matchLength == LENGTH_MASK) {
                    var extension: Int
                    do {
                        extension = compressed[position++].toInt() and 0xff
                        matchLength += extension
                    } while (extension == LENGTH_EXTENSION)
                }
                check(position <= compressedLength) { "Invalid compressed chunk match length" }
                if (pendingLength > 0) {
                    check(pendingOffset + pendingLength == sourceOffset + decoded) { "Noncontiguous deferred literals" }
                    writeToken(pendingLength + literalLength, token and LENGTH_MASK)
                    copySource(pendingOffset, pendingLength + literalLength)
                    copyCompressed(compressed, literalStart + literalLength, position)
                    pendingLength = 0
                } else {
                    copyCompressed(compressed, sequenceStart, position)
                }
                decoded += literalLength.toLong() + matchLength + MIN_MATCH_BYTES
            }
            error("Compressed chunk has no terminal literals")
        }

        private fun deferLiterals(offset: Long, length: Int) {
            if (pendingLength == 0L) {
                pendingOffset = offset
            } else {
                check(pendingOffset + pendingLength == offset) { "Noncontiguous deferred literals" }
            }
            pendingLength += length
        }

        fun finish() {
            // Deferring every chunk's terminal literals preserves the final >=5 literals and
            // >=12 bytes from the last match start, even when the final chunks contain no matches.
            writeToken(pendingLength, 0)
            copySource(pendingOffset, pendingLength)
        }

        private fun writeToken(literalLength: Long, matchToken: Int) {
            output.write((minOf(literalLength, LENGTH_MASK.toLong()).toInt() shl MIN_MATCH_BYTES) or matchToken)
            if (literalLength < LENGTH_MASK) return
            var remaining = literalLength - LENGTH_MASK
            while (remaining >= LENGTH_EXTENSION) {
                checkCancellation()
                val count = minOf((remaining / LENGTH_EXTENSION), copyBuffer.size.toLong()).toInt()
                copyBuffer.fill(LENGTH_EXTENSION.toByte(), 0, count)
                output.write(copyBuffer, 0, count)
                remaining -= count.toLong() * LENGTH_EXTENSION
            }
            output.write(remaining.toInt())
        }

        private fun copySource(offset: Long, length: Long) {
            input.seek(offset)
            var remaining = length
            while (remaining > 0) {
                checkCancellation()
                val count = minOf(remaining, copyBuffer.size.toLong()).toInt()
                input.readFully(copyBuffer, 0, count)
                output.write(copyBuffer, 0, count)
                remaining -= count
            }
        }

        private fun copyCompressed(bytes: ByteArray, start: Int, end: Int) {
            var position = start
            while (position < end) {
                checkCancellation()
                val count = minOf(COPY_BYTES, end - position)
                output.write(bytes, position, count)
                position += count
            }
        }
    }
}
