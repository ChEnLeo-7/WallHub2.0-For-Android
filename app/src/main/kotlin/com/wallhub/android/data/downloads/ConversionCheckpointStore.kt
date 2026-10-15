package com.wallhub.android.data.downloads

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

internal data class ConversionCheckpointIdentity(
    val taskId: String,
    val attempt: Long,
    val planSha256: String,
    val sourceIdentity: String,
    val presetId: String,
    val preserveAudio: Boolean,
    val converterVersion: String,
) {
    init {
        require(taskId.isNotBlank() && taskId.toByteArray().size <= 512) { "Invalid checkpoint task ID" }
        require(attempt >= 0) { "Invalid checkpoint attempt" }
        require(planSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid plan digest" }
        require(sourceIdentity.isNotBlank()) { "Invalid source identity" }
        require(presetId.isNotBlank()) { "Invalid preset ID" }
        require(converterVersion.isNotBlank()) { "Invalid converter version" }
    }
}

internal data class ConversionCheckpointResource(
    val normalizedPath: String,
    val sourceOffset: Long,
    val sourceLength: Long,
    val sourceSha256: String,
    val outputSize: Long,
    val outputSha256: String,
    val strategy: String,
    val file: File,
)

/** App-private, file-backed resource checkpoints. Invalid or stale entries are cache misses. */
internal class ConversionCheckpointStore(
    filesDir: File,
    private val move: (File, File) -> Unit = ::atomicReplace,
) {
    private val root = File(filesDir, "conversion-checkpoints")

    fun write(
        identity: ConversionCheckpointIdentity,
        normalizedPath: String,
        sourceOffset: Long,
        sourceLength: Long,
        sourceSha256: String,
        strategy: String,
        writeOutput: (OutputStream) -> Unit,
    ): ConversionCheckpointResource {
        validateResource(normalizedPath, sourceOffset, sourceLength, sourceSha256, strategy)
        val directory = attemptDirectory(identity)
        check(directory.exists() || directory.mkdirs()) { "Unable to create checkpoint directory" }
        ensureIdentity(directory, identity)
        val key = sha256(normalizedPath.toByteArray())
        val artifact = File(directory, "$key.bin")
        val temporary = File(directory, ".$key.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                writeOutput(output)
                output.fd.sync()
            }
            move(temporary, artifact)
        } finally {
            temporary.delete()
        }
        val metadata =
            Properties().apply {
                setProperty("normalizedPath", normalizedPath)
                setProperty("sourceOffset", sourceOffset.toString())
                setProperty("sourceLength", sourceLength.toString())
                setProperty("sourceSha256", sourceSha256)
                setProperty("outputSize", artifact.length().toString())
                setProperty("outputSha256", digest(artifact))
                setProperty("strategy", strategy)
            }
        writePropertiesAtomically(directory, ".$key.meta", metadata)
        return requireNotNull(read(identity, normalizedPath))
    }

    fun read(identity: ConversionCheckpointIdentity, normalizedPath: String): ConversionCheckpointResource? {
        if (!isNormalizedPath(normalizedPath)) return null
        val directory = attemptDirectory(identity)
        if (readIdentity(directory) != identity) return null
        val key = sha256(normalizedPath.toByteArray())
        val artifact = File(directory, "$key.bin")
        val metadataFile = File(directory, ".$key.meta")
        if (!artifact.isFile || !metadataFile.isFile) return null
        return runCatching {
            val properties = Properties().also { FileInputStream(metadataFile).use(it::load) }
            val resource =
                ConversionCheckpointResource(
                    normalizedPath = properties.required("normalizedPath"),
                    sourceOffset = properties.required("sourceOffset").toLong(),
                    sourceLength = properties.required("sourceLength").toLong(),
                    sourceSha256 = properties.required("sourceSha256"),
                    outputSize = properties.required("outputSize").toLong(),
                    outputSha256 = properties.required("outputSha256"),
                    strategy = properties.required("strategy"),
                    file = artifact,
                )
            require(resource.normalizedPath == normalizedPath)
            require(resource.sourceOffset >= 0 && resource.sourceLength >= 0)
            require(resource.sourceSha256.isSha256() && resource.outputSha256.isSha256())
            require(resource.outputSize == artifact.length())
            require(resource.outputSha256 == digest(artifact))
            resource
        }.getOrNull()
    }

    fun deleteAttempt(identity: ConversionCheckpointIdentity): Boolean = attemptDirectory(identity).deleteRecursively()

    fun deleteTask(taskId: String): Boolean {
        require(taskId.isNotBlank() && taskId.toByteArray().size <= 512) { "Invalid checkpoint task ID" }
        val directory = File(root, safeTask(taskId))
        return !directory.exists() || directory.deleteRecursively()
    }

    fun cleanupTemporaryFiles(): Int {
        if (!root.isDirectory) return 0
        var deleted = 0
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach {
            if (it.delete()) deleted++
        }
        return deleted
    }

    internal fun attemptDirectory(identity: ConversionCheckpointIdentity): File =
        File(File(root, safeTask(identity.taskId)), identity.attempt.toString())

    private fun ensureIdentity(directory: File, identity: ConversionCheckpointIdentity) {
        val existing = readIdentity(directory)
        check(existing == null || existing == identity) { "Checkpoint attempt identity mismatch" }
        if (existing == null) writePropertiesAtomically(directory, IDENTITY_FILE, identity.toProperties())
    }

    private fun readIdentity(directory: File): ConversionCheckpointIdentity? =
        runCatching {
            val properties = Properties().also { FileInputStream(File(directory, IDENTITY_FILE)).use(it::load) }
            ConversionCheckpointIdentity(
                taskId = properties.required("taskId"),
                attempt = properties.required("attempt").toLong(),
                planSha256 = properties.required("planSha256"),
                sourceIdentity = properties.required("sourceIdentity"),
                presetId = properties.required("presetId"),
                preserveAudio = properties.required("preserveAudio").toBooleanStrict(),
                converterVersion = properties.required("converterVersion"),
            )
        }.getOrNull()

    private fun writePropertiesAtomically(directory: File, name: String, properties: Properties) {
        val destination = File(directory, name)
        val temporary = File(directory, ".$name.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            move(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun ConversionCheckpointIdentity.toProperties() =
        Properties().also {
            it.setProperty("taskId", taskId)
            it.setProperty("attempt", attempt.toString())
            it.setProperty("planSha256", planSha256)
            it.setProperty("sourceIdentity", sourceIdentity)
            it.setProperty("presetId", presetId)
            it.setProperty("preserveAudio", preserveAudio.toString())
            it.setProperty("converterVersion", converterVersion)
        }

    private fun validateResource(path: String, offset: Long, length: Long, hash: String, strategy: String) {
        require(isNormalizedPath(path)) { "Unsafe or non-normalized resource path" }
        require(offset >= 0 && length >= 0) { "Invalid source range" }
        require(hash.isSha256()) { "Invalid source digest" }
        require(strategy.isNotBlank()) { "Invalid resource strategy" }
    }

    companion object {
        private const val IDENTITY_FILE = ".identity"

        private fun safeTask(taskId: String): String {
            val prefix =
                taskId
                    .map { character ->
                        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_') {
                            character
                        } else {
                            '_'
                        }
                    }.joinToString("")
                    .take(24)
                    .ifBlank { "task" }
            return "$prefix-${sha256(taskId.toByteArray()).take(32)}"
        }

        private fun isNormalizedPath(path: String): Boolean =
            path.isNotBlank() && path.length <= 4096 && !path.startsWith('/') && '\\' !in path &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." }

        private fun Properties.required(key: String): String = requireNotNull(getProperty(key))
        private fun String.isSha256() = matches(Regex("[0-9a-f]{64}"))
        private fun digest(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun atomicReplace(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
