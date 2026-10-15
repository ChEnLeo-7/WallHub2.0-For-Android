package com.wallhub.prototype.mpkg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

const val SCENE_MPKG_MAGIC = "PKGM0020"
const val VIDEO_MPKG_MAGIC = "PKGM0014"

/** A requested package cannot be represented by the MPKG on-disk format. */
class MpkgFormatLimitException(
    message: String,
) : IllegalArgumentException(message)

/** The destination volume cannot satisfy the package plus its safety reserve. */
class MpkgInsufficientSpaceException(
    message: String,
) : IOException(message)

sealed interface MpkgPayload {
    val length: Long

    fun writeTo(
        output: BufferedOutputStream,
        copyBuffer: ByteArray,
        checkCancellation: () -> Unit = {},
    )
}

data class ByteArrayPayload(
    val bytes: ByteArray,
) : MpkgPayload {
    override val length: Long get() = bytes.size.toLong()

    override fun writeTo(
        output: BufferedOutputStream,
        copyBuffer: ByteArray,
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
        copyBuffer: ByteArray,
        checkCancellation: () -> Unit,
    ) {
        FileInputStream(file).use { input ->
            var remaining = length
            while (remaining > 0L) {
                checkCancellation()
                val read = input.read(copyBuffer, 0, minOf(copyBuffer.size.toLong(), remaining).toInt())
                if (read <= 0) throw IOException("Unexpected end of MPKG source file: $file")
                output.write(copyBuffer, 0, read)
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
        copyBuffer: ByteArray,
        checkCancellation: () -> Unit,
    ) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var remaining = length
            while (remaining > 0L) {
                checkCancellation()
                val read = input.read(copyBuffer, 0, minOf(copyBuffer.size.toLong(), remaining).toInt())
                if (read <= 0) throw IOException("Unexpected end of MPKG source file: $file")
                output.write(copyBuffer, 0, read)
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
    internal const val MAX_PATH_BYTES = 4 * 1024
    internal const val MAX_U32 = 0xffff_ffffL

    fun write(
        entries: List<MpkgInputEntry>,
        outputFile: File,
        magic: String,
        checkCancellation: () -> Unit = {},
    ): MpkgManifest {
        require(magic.startsWith("PKGM")) { "Unsupported MPKG magic: $magic" }
        val normalized = HashMap<String, MpkgInputEntry>()
        entries.forEach { entry ->
            checkCancellation()
            val path = normalizePath(entry.path)
            require(normalized.put(path, entry.copy(path = path)) == null) {
                "Duplicate MPKG entry path: $path"
            }
        }
        val ordered =
            normalized.values.sortedWith(
                compareBy<MpkgInputEntry> { it.path.lowercase(Locale.ROOT) }.thenBy { it.path },
            )
        var payloadBytes = 0L
        var indexBytes = 0L
        val manifestEntries = ArrayList<MpkgEntryInfo>(ordered.size)
        ordered.forEach { entry ->
            checkCancellation()
            val pathBytes = entry.path.toByteArray(Charsets.UTF_8).size
            if (pathBytes > MAX_PATH_BYTES) {
                throw MpkgFormatLimitException("MPKG entry path exceeds the $MAX_PATH_BYTES-byte format limit: ${entry.path}")
            }
            indexBytes = checkedFormatAdd(indexBytes, INDEX_ENTRY_FIXED_BYTES + pathBytes.toLong(), "MPKG index")
            if (entry.payload.length !in 0..MAX_U32) {
                throw MpkgFormatLimitException("MPKG entry length exceeds the 32-bit format limit: ${entry.path}")
            }
            manifestEntries += MpkgEntryInfo(entry.path, entry.payload.length, payloadBytes)
            payloadBytes = checkedFormatAdd(payloadBytes, entry.payload.length, "MPKG payload")
            if (payloadBytes > MAX_U32) {
                throw MpkgFormatLimitException("MPKG payload exceeds the 32-bit format limit")
            }
        }
        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        if (magicBytes.size > MAX_PATH_BYTES) throw MpkgFormatLimitException("MPKG magic is too large")
        val headerBytes = checkedFormatAdd(HEADER_FIXED_BYTES, magicBytes.size.toLong(), "MPKG header")
        val outputBytes = checkedFormatAdd(checkedFormatAdd(headerBytes, indexBytes, "MPKG output"), payloadBytes, "MPKG output")
        if (outputBytes > MAX_U32) {
            throw MpkgFormatLimitException("MPKG output exceeds the 32-bit format limit")
        }

        val parent = outputFile.absoluteFile.parentFile ?: error("Output file has no parent directory")
        check(parent.exists() || parent.mkdirs()) { "Unable to create MPKG output directory: $parent" }
        val peakNewBytes = checkedFormatAdd(outputBytes, indexBytes, "MPKG peak disk use")
        requireAvailableSpace(parent, peakNewBytes)

        val indexSpool = File.createTempFile(".${outputFile.name}.index.", ".tmp", parent)
        try {
            BufferedOutputStream(FileOutputStream(indexSpool)).use { spool ->
                manifestEntries.forEach { entry ->
                    checkCancellation()
                    val path = entry.path.toByteArray(Charsets.UTF_8)
                    spool.writeIntLe(path.size)
                    spool.write(path)
                    spool.writeIntLe(entry.offset.toInt())
                    spool.writeIntLe(entry.length.toInt())
                }
            }
            check(indexSpool.length() == indexBytes) { "MPKG index spool length changed unexpectedly" }
            writeAtomically(outputFile) { temporaryFile ->
                BufferedOutputStream(FileOutputStream(temporaryFile)).use { output ->
                    output.writeIntLe(magicBytes.size)
                    output.write(magicBytes)
                    output.writeIntLe(manifestEntries.size)
                    val copyBuffer = ByteArray(COPY_BUFFER_SIZE)
                    BufferedInputStream(FileInputStream(indexSpool)).use { input ->
                        while (true) {
                            checkCancellation()
                            val read = input.read(copyBuffer)
                            if (read < 0) break
                            output.write(copyBuffer, 0, read)
                        }
                    }
                    ordered.forEach { entry ->
                        checkCancellation()
                        entry.payload.writeTo(output, copyBuffer, checkCancellation)
                    }
                }
            }
        } finally {
            indexSpool.delete()
        }
        return MpkgManifest(magic, manifestEntries)
    }

    private fun requireAvailableSpace(parent: File, peakNewBytes: Long) {
        val capacityReserve = parent.totalSpace / 20L
        val reserve = maxOf(MIN_MPKG_FREE_SPACE_BYTES, capacityReserve)
        val required =
            try {
                Math.addExact(peakNewBytes, reserve)
            } catch (_: ArithmeticException) {
                throw MpkgFormatLimitException("MPKG output and disk reserve exceed the supported size")
            }
        val usable = parent.usableSpace
        if (usable < required) {
            throw MpkgInsufficientSpaceException(
                "Insufficient space for MPKG output: need $peakNewBytes bytes of peak staging plus $reserve bytes reserve, " +
                    "but only $usable bytes are usable",
            )
        }
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

private fun checkedFormatAdd(
    left: Long,
    right: Long,
    subject: String,
): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw MpkgFormatLimitException("$subject size overflows")
    }

private const val INDEX_ENTRY_FIXED_BYTES = Integer.BYTES.toLong() * 3L
private const val HEADER_FIXED_BYTES = Integer.BYTES.toLong() * 2L
private const val COPY_BUFFER_SIZE = 1024 * 1024
private const val MIN_MPKG_FREE_SPACE_BYTES = 512L * 1024L * 1024L
