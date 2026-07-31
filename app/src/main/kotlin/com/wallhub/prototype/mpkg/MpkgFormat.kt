package com.wallhub.prototype.mpkg

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val SCENE_MPKG_MAGIC = "PKGM0020"
const val VIDEO_MPKG_MAGIC = "PKGM0014"

sealed interface MpkgPayload {
    val length: Long

    fun writeTo(
        output: BufferedOutputStream,
        checkCancellation: () -> Unit = {},
    )
}

data class ByteArrayPayload(
    val bytes: ByteArray,
) : MpkgPayload {
    override val length: Long get() = bytes.size.toLong()

    override fun writeTo(
        output: BufferedOutputStream,
        checkCancellation: () -> Unit,
    ) {
        checkCancellation()
        output.write(bytes)
    }
}

data class FilePayload(
    val file: File,
    override val length: Long = file.length(),
) : MpkgPayload {
    init {
        require(file.isFile) { "MPKG source file is missing: $file" }
        require(length >= 0L && length <= file.length()) { "MPKG source file length is invalid: $file" }
    }

    override fun writeTo(
        output: BufferedOutputStream,
        checkCancellation: () -> Unit,
    ) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0L) {
                checkCancellation()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "Unexpected end of MPKG source file: $file" }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

data class FileSlicePayload(
    val file: File,
    val offset: Long,
    override val length: Long,
) : MpkgPayload {
    init {
        require(file.isFile) { "MPKG source file is missing: $file" }
        require(offset >= 0L && length >= 0L && offset <= file.length() - length) {
            "MPKG source range is invalid: $file"
        }
    }

    override fun writeTo(
        output: BufferedOutputStream,
        checkCancellation: () -> Unit,
    ) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0L) {
                checkCancellation()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "Unexpected end of MPKG source file: $file" }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

data class MpkgInputEntry(
    val path: String,
    val payload: MpkgPayload,
)

data class MpkgEntryInfo(
    val path: String,
    val length: Long,
    val offset: Long,
)

data class MpkgManifest(
    val magic: String,
    val entries: List<MpkgEntryInfo>,
)

object MpkgWriter {
    private const val MAX_U32 = 0xffff_ffffL

    fun write(
        entries: List<MpkgInputEntry>,
        outputFile: File,
        magic: String,
        checkCancellation: () -> Unit = {},
    ): MpkgManifest {
        require(magic.startsWith("PKGM")) { "Unsupported MPKG magic: $magic" }
        val normalized = linkedMapOf<String, MpkgInputEntry>()
        entries.forEach { entry ->
            checkCancellation()
            val path = normalizePath(entry.path)
            require(normalized.put(path, entry.copy(path = path)) == null) {
                "Duplicate MPKG entry path: $path"
            }
        }
        val ordered =
            normalized.values.sortedWith(
                compareBy<MpkgInputEntry> { it.path.substringBeforeLast('/', "").lowercase() }
                    .thenBy { it.path.substringAfterLast('/').lowercase() },
            )
        require(ordered.size <= Int.MAX_VALUE) { "Too many MPKG files" }

        var offset = 0L
        val manifestEntries =
            ordered.map { entry ->
                require(entry.payload.length in 0..MAX_U32) { "MPKG entry is too large: ${entry.path}" }
                val item = MpkgEntryInfo(entry.path, entry.payload.length, offset)
                offset += entry.payload.length
                require(offset <= MAX_U32) { "MPKG payload exceeds 4 GiB format limit" }
                item
            }
        val table =
            byteArrayOutput {
                manifestEntries.forEach { entry ->
                    val name = entry.path.toByteArray(Charsets.UTF_8)
                    writeIntLe(name.size)
                    write(name)
                    writeIntLe(entry.offset.toInt())
                    writeIntLe(entry.length.toInt())
                }
            }
        writeAtomically(outputFile) { temporaryFile ->
            BufferedOutputStream(FileOutputStream(temporaryFile)).use { output ->
                val magicBytes = magic.toByteArray(Charsets.US_ASCII)
                output.writeIntLe(magicBytes.size)
                output.write(magicBytes)
                output.writeIntLe(manifestEntries.size)
                output.write(table)
                ordered.forEach { entry ->
                    checkCancellation()
                    entry.payload.writeTo(output, checkCancellation)
                }
            }
        }
        return MpkgManifest(magic, manifestEntries)
    }

    internal fun normalizePath(rawPath: String): String {
        val canonical = rawPath.replace('\\', '/').trim()
        require(!canonical.startsWith('/')) { "MPKG entry path must be relative" }
        val normalized = canonical.trim('/')
        require(normalized.isNotBlank()) { "MPKG entry path is empty" }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "MPKG entry path escapes its package: $rawPath"
        }
        return segments.joinToString("/")
    }
}

internal fun writeAtomically(
    outputFile: File,
    write: (File) -> Unit,
) {
    val parent = outputFile.absoluteFile.parentFile ?: error("Output file has no parent directory")
    check(parent.exists() || parent.mkdirs()) { "Unable to create output directory: $parent" }
    val temporaryFile = File.createTempFile(".${outputFile.name}.", ".tmp", parent)
    try {
        write(temporaryFile)
        Files.move(
            temporaryFile.toPath(),
            outputFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        temporaryFile.delete()
    }
}

private const val COPY_BUFFER_SIZE = 1024 * 1024
