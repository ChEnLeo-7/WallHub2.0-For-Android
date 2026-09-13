package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class SteamDownloadCheckpoint(
    private val destinationDirectory: File,
    private val manifestId: Long,
) {
    private val metadataDirectory = File(destinationDirectory, METADATA_DIRECTORY)

    fun load(fileName: String, chunks: List<DepotChunkSpec>): Set<Long>? {
        val destination = File(destinationDirectory, fileName)
        val partial = File(destination.parentFile ?: destinationDirectory, "${destination.name}.wallhub.part")
        if (!destination.isFile && !partial.isFile) {
            return null
        }
        val file = journalFile(fileName)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            check(lines.firstOrNull() == header(fileName, chunks))
            lines.drop(1).mapTo(mutableSetOf()) { it.toLong() }
                .also { offsets ->
                    val expected = chunks.mapTo(hashSetOf(), DepotChunkSpec::offset)
                    check(offsets.all(expected::contains))
                }
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun save(fileName: String, chunks: List<DepotChunkSpec>, committedOffsets: Set<Long>) {
        metadataDirectory.mkdirs()
        check(metadataDirectory.isDirectory) { "Failed to create Steam checkpoint directory" }
        val target = journalFile(fileName)
        val temporary = File(metadataDirectory, "${target.name}.${System.nanoTime()}.part")
        try {
            FileOutputStream(temporary).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(header(fileName, chunks))
                committedOffsets.sorted().forEach { writer.appendLine(it.toString()) }
                writer.flush()
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun manifestFile(depotId: Int): File {
        metadataDirectory.mkdirs()
        return File(metadataDirectory, "manifest-$depotId-${manifestId.toULong()}.zip")
    }

    fun clear() {
        metadataDirectory.deleteRecursively()
    }

    private fun journalFile(fileName: String): File = File(metadataDirectory, "chunks-${sha256(fileName)}.journal")

    private fun header(fileName: String, chunks: List<DepotChunkSpec>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(manifestId.toString().toByteArray(Charsets.UTF_8))
        digest.update(fileName.toByteArray(Charsets.UTF_8))
        chunks.forEach { chunk ->
            digest.update(chunk.chunkId ?: ByteArray(0))
            digest.update("${chunk.offset}:${chunk.compressedLength}:${chunk.uncompressedLength}:${chunk.checksum}".toByteArray())
        }
        return "v1:${manifestId.toULong()}:${java.util.Base64.getEncoder().encodeToString(digest.digest())}"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val METADATA_DIRECTORY = ".wallhub-download-state"
    }
}
