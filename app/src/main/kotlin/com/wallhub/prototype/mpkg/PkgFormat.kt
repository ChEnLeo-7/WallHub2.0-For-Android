package com.wallhub.prototype.mpkg

import java.io.File
import java.io.RandomAccessFile

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
    internal const val MAX_ENTRY_COUNT = 50_000
    internal const val MAX_PATH_LENGTH = 4 * 1024
    internal const val MAX_INDEX_BYTES = 64L * 1024L * 1024L

    fun readIndex(file: File): PkgArchive {
        RandomAccessFile(file, "r").use { input ->
            val magic = input.readLengthString(256)
            require(magic.startsWith("PKGV")) { "Unsupported PKG magic: $magic" }
            val count = input.readIntLe()
            require(count in 0..MAX_ENTRY_COUNT) { "Invalid PKG entry count: $count" }
            val index = ArrayList<IndexedPkgEntry>(count)
            var indexBytes = 0L
            repeat(count) {
                val path = MpkgWriter.normalizePath(input.readLengthString(MAX_PATH_LENGTH))
                val offset = input.readUIntLe()
                val length = input.readUIntLe()
                indexBytes += Integer.BYTES.toLong() * 3L + path.toByteArray(Charsets.UTF_8).size
                require(indexBytes <= MAX_INDEX_BYTES) { "PKG index exceeds $MAX_INDEX_BYTES bytes" }
                index += IndexedPkgEntry(path, offset, length)
            }
            val dataStart = input.filePointer
            val fileLength = input.length()
            index.indices.forEach { entryIndex ->
                val entry = index[entryIndex]
                require(dataStart <= fileLength && entry.offset <= fileLength - dataStart) {
                    "PKG entry offset exceeds file: ${entry.path}"
                }
                val absoluteOffset = dataStart + entry.offset
                require(entry.length <= fileLength - absoluteOffset) {
                    "PKG entry range exceeds file: ${entry.path}"
                }
                index[entryIndex] = entry.copy(offset = absoluteOffset)
            }
            index
                .sortedBy(IndexedPkgEntry::offset)
                .zipWithNext()
                .forEach { (previous, next) ->
                    require(previous.offset + previous.length <= next.offset) {
                        "PKG entry ranges overlap: ${previous.path} and ${next.path}"
                    }
                }
            return PkgArchive(file, index)
        }
    }
}
