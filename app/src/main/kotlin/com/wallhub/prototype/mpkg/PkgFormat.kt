package com.wallhub.prototype.mpkg

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** The source declares a PKG/MPKG structure that cannot be represented or is internally inconsistent. */
class PkgFormatException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The source cannot supply all bytes declared by an otherwise representable PKG/MPKG structure. */
class PkgSourceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class IndexedPkgEntry(
    val path: String,
    val offset: Long,
    val length: Long,
)

data class PkgArchive(
    val file: File,
    val entries: List<IndexedPkgEntry>,
) {
    fun readBytes(
        entry: IndexedPkgEntry,
        maxBytes: Long = Int.MAX_VALUE.toLong(),
    ): ByteArray {
        require(entry.length <= maxBytes && entry.length <= Int.MAX_VALUE) {
            "PKG entry exceeds the $maxBytes-byte transform limit: ${entry.path} (${entry.length})"
        }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(entry.offset)
            ByteArray(entry.length.toInt()).also(input::readFully)
        }
    }
}

object PkgReader {
    internal const val MAX_PATH_LENGTH = 4 * 1024
    private const val MIN_ENTRY_INDEX_BYTES = Integer.BYTES.toLong() * 3L

    fun readIndex(file: File): PkgArchive {
        try {
            RandomAccessFile(file, "r").use { input ->
                val fileLength = input.length()
                val magic = readLengthString(input, 256, "magic")
                if (!magic.startsWith("PKGV") && !magic.startsWith("PKGM")) {
                    throw PkgFormatException("Unsupported PKG/MPKG magic: $magic")
                }
                val count = readInt(input, "entry count")
                if (count < 0) throw PkgFormatException("Invalid PKG entry count: $count")
                val remaining = checkedSubtract(fileLength, input.filePointer, "PKG source length")
                val minimumIndexBytes = checkedMultiply(count.toLong(), MIN_ENTRY_INDEX_BYTES, "PKG minimum index")
                if (minimumIndexBytes > remaining) {
                    throw PkgFormatException(
                        "PKG entry count cannot fit in the source: $count entries require at least $minimumIndexBytes bytes, " +
                            "but only $remaining bytes remain",
                    )
                }

                // Deliberately do not use count as ArrayList capacity: count is source-controlled.
                val index = ArrayList<IndexedPkgEntry>()
                val paths = HashSet<String>()
                repeat(count) {
                    val encodedPathLength = readInt(input, "entry path length")
                    if (encodedPathLength !in 1..MAX_PATH_LENGTH) {
                        throw PkgFormatException("Invalid PKG string length: $encodedPathLength")
                    }
                    val bytesAfterPathLength = checkedSubtract(fileLength, input.filePointer, "PKG source length")
                    val bytesNeeded = checkedAdd(encodedPathLength.toLong(), Integer.BYTES.toLong() * 2L, "PKG index entry")
                    if (bytesNeeded > bytesAfterPathLength) {
                        throw PkgSourceException("Truncated PKG index entry ${index.size}")
                    }
                    val encodedPath = ByteArray(encodedPathLength)
                    input.readFully(encodedPath)
                    val path =
                        try {
                            MpkgWriter.normalizePath(encodedPath.toString(Charsets.UTF_8))
                        } catch (error: IllegalArgumentException) {
                            throw PkgFormatException("Invalid PKG entry path", error)
                        }
                    if (!paths.add(path)) throw PkgFormatException("Duplicate PKG entry path: $path")
                    val offset = readUInt(input, "entry offset")
                    val length = readUInt(input, "entry length")
                    index += IndexedPkgEntry(path, offset, length)
                }
                val dataStart = input.filePointer
                index.indices.forEach { entryIndex ->
                    val entry = index[entryIndex]
                    val absoluteOffset = checkedAdd(dataStart, entry.offset, "PKG entry offset")
                    val absoluteEnd = checkedAdd(absoluteOffset, entry.length, "PKG entry range")
                    if (absoluteOffset > fileLength) {
                        throw PkgSourceException("PKG entry offset exceeds file: ${entry.path}")
                    }
                    if (absoluteEnd > fileLength) {
                        throw PkgSourceException("PKG entry range exceeds file: ${entry.path}")
                    }
                    index[entryIndex] = entry.copy(offset = absoluteOffset)
                }
                index
                    .sortedBy(IndexedPkgEntry::offset)
                    .zipWithNext()
                    .forEach { (previous, next) ->
                        val previousEnd = checkedAdd(previous.offset, previous.length, "PKG entry range")
                        if (previousEnd > next.offset) {
                            throw PkgFormatException("PKG entry ranges overlap: ${previous.path} and ${next.path}")
                        }
                    }
                return PkgArchive(file, index)
            }
        } catch (error: PkgFormatException) {
            throw error
        } catch (error: PkgSourceException) {
            throw error
        } catch (error: EOFException) {
            throw PkgSourceException("Truncated PKG source: $file", error)
        } catch (error: IOException) {
            throw PkgSourceException("Unable to read PKG source: $file", error)
        }
    }

    private fun readLengthString(
        input: RandomAccessFile,
        maxLength: Int,
        subject: String,
    ): String {
        val length = readInt(input, "$subject length")
        if (length !in 0..maxLength) throw PkgFormatException("Invalid $subject length: $length")
        val remaining = checkedSubtract(input.length(), input.filePointer, "PKG source length")
        if (length.toLong() > remaining) throw PkgSourceException("Truncated PKG $subject")
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun readInt(
        input: RandomAccessFile,
        subject: String,
    ): Int =
        try {
            input.readIntLe()
        } catch (error: EOFException) {
            throw PkgSourceException("Truncated PKG $subject", error)
        }

    private fun readUInt(
        input: RandomAccessFile,
        subject: String,
    ): Long = Integer.toUnsignedLong(readInt(input, subject))

    private fun checkedAdd(
        left: Long,
        right: Long,
        subject: String,
    ): Long =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw PkgFormatException("$subject overflows", error)
        }

    private fun checkedSubtract(
        left: Long,
        right: Long,
        subject: String,
    ): Long =
        try {
            Math.subtractExact(left, right).also {
                if (it < 0L) throw PkgFormatException("$subject is negative")
            }
        } catch (error: ArithmeticException) {
            throw PkgFormatException("$subject overflows", error)
        }

    private fun checkedMultiply(
        left: Long,
        right: Long,
        subject: String,
    ): Long =
        try {
            Math.multiplyExact(left, right)
        } catch (error: ArithmeticException) {
            throw PkgFormatException("$subject overflows", error)
        }
}
