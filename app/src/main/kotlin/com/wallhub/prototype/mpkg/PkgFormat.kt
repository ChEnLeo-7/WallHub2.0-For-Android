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
    fun readBytes(entry: IndexedPkgEntry): ByteArray {
        require(entry.length <= Int.MAX_VALUE) { "PKG entry is too large to transform: ${entry.path}" }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(entry.offset)
            ByteArray(entry.length.toInt()).also(input::readFully)
        }
    }
}

object PkgReader {
    private const val MAX_ENTRY_COUNT = 200_000
    private const val MAX_PATH_LENGTH = 16 * 1024

    fun readIndex(file: File): PkgArchive {
        RandomAccessFile(file, "r").use { input ->
            val magic = input.readLengthString(256)
            require(magic.startsWith("PKGV")) { "Unsupported PKG magic: $magic" }
            val count = input.readIntLe()
            require(count in 0..MAX_ENTRY_COUNT) { "Invalid PKG entry count: $count" }
            val relativeIndex = ArrayList<IndexedPkgEntry>(count)
            repeat(count) {
                val path = MpkgWriter.normalizePath(input.readLengthString(MAX_PATH_LENGTH))
                val offset = input.readUIntLe()
                val length = input.readUIntLe()
                relativeIndex += IndexedPkgEntry(path, offset, length)
            }
            val dataStart = input.filePointer
            val index =
                relativeIndex.map { entry ->
                    require(dataStart + entry.offset + entry.length <= input.length()) {
                        "PKG entry range exceeds file: ${entry.path}"
                    }
                    entry.copy(offset = dataStart + entry.offset)
                }
            return PkgArchive(file, index)
        }
    }
}
