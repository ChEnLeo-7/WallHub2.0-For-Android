package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionCheckpointRequest
import com.wallhub.prototype.mpkg.ConversionResourceCheckpoint
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest

internal class ConversionResourceCheckpointAdapter(
    private val identity: ConversionCheckpointIdentity,
    private val store: ConversionCheckpointStore,
) : ConversionResourceCheckpoint {
    init {
        store.bind(identity)
    }

    override fun reuse(request: ConversionCheckpointRequest): File? {
        val resource = store.read(identity, request.path) ?: return null
        if (
            resource.sourceOffset != request.sourceOffset ||
            resource.sourceLength != request.sourceLength ||
            resource.sourceSha256 != request.sourceSha256 ||
            resource.strategy != request.strategy.name
        ) {
            return null
        }
        return resource.file
    }

    override fun record(request: ConversionCheckpointRequest, verifiedOutput: File): File =
        store.write(
            identity = identity,
            normalizedPath = request.path,
            sourceOffset = request.sourceOffset,
            sourceLength = request.sourceLength,
            sourceSha256 = request.sourceSha256,
            strategy = request.strategy.name,
        ) { output ->
            copyAndVerify(verifiedOutput, output)
        }.file

    private fun copyAndVerify(source: File, output: OutputStream) {
        val expectedLength = source.length()
        val expectedDigest = digest(source)
        FileInputStream(source).use { input -> input.copyTo(output) }
        check(source.length() == expectedLength && digest(source) == expectedDigest) {
            "Verified conversion product changed while checkpointing"
        }
    }

    private fun digest(file: File): String =
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
}
