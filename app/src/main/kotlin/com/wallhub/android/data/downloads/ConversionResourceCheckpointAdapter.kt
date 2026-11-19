package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionCheckpointRequest
import com.wallhub.prototype.mpkg.ConversionResourceCheckpoint
import com.wallhub.prototype.mpkg.MpkgPhase
import com.wallhub.prototype.mpkg.MpkgTimingSession
import com.wallhub.prototype.mpkg.measureIfEnabled
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest

internal class ConversionResourceCheckpointAdapter(
    private val identity: ConversionCheckpointIdentity,
    private val store: ConversionCheckpointStore,
    private val timing: MpkgTimingSession? = null,
    private val onVerifiedOutputFullRead: (File) -> Unit = {},
    private val onVerifiedOutputBytesRead: (Int) -> Unit = {},
    private val singlePass: Boolean = com.wallhub.android.BuildConfig.MPKG_SINGLE_PASS_CHECKPOINT,
) : ConversionResourceCheckpoint {
    init {
        store.bind(identity)
    }

    override fun reuse(request: ConversionCheckpointRequest): File? =
        timing.measureIfEnabled(MpkgPhase.CHECKPOINT) {
            val resource = store.read(identity, request.path) ?: return@measureIfEnabled null
            if (
                resource.sourceOffset != request.sourceOffset ||
                resource.sourceLength != request.sourceLength ||
                resource.sourceSha256 != request.sourceSha256 ||
                resource.strategy != request.strategy.name
            ) {
                return@measureIfEnabled null
            }
            resource.file
        }

    override fun record(
        request: ConversionCheckpointRequest,
        verifiedOutput: File,
        checkCancellation: () -> Unit,
    ): File =
        timing.measureIfEnabled(MpkgPhase.CHECKPOINT) {
            store.write(
                identity = identity,
                normalizedPath = request.path,
                sourceOffset = request.sourceOffset,
                sourceLength = request.sourceLength,
                sourceSha256 = request.sourceSha256,
                strategy = request.strategy.name,
                expectedOutputBytes = verifiedOutput.length(),
            ) { output ->
                copyAndVerify(verifiedOutput, output, checkCancellation)
            }.file
        }

    private fun copyAndVerify(
        source: File,
        output: OutputStream,
        checkCancellation: () -> Unit,
    ): String {
        if (!singlePass) {
            val expectedLength = source.length()
            val expectedDigest = digest(source, checkCancellation)
            onVerifiedOutputFullRead(source)
            copy(source, output, checkCancellation)
            check(source.length() == expectedLength && digest(source, checkCancellation) == expectedDigest) {
                "Verified conversion product changed while checkpointing"
            }
            return expectedDigest
        }
        val expectedLength = source.length()
        val expectedModified = source.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        onVerifiedOutputFullRead(source)
        FileInputStream(source).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancellation()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                onVerifiedOutputBytesRead(read)
                digest.update(buffer, 0, read)
                copied += read
            }
        }
        check(copied == expectedLength && source.length() == expectedLength && source.lastModified() == expectedModified) {
            "Verified conversion product changed while checkpointing"
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copy(
        source: File,
        output: OutputStream,
        checkCancellation: () -> Unit,
    ) {
        FileInputStream(source).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancellation()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
    }

    private fun digest(
        file: File,
        checkCancellation: () -> Unit,
    ): String =
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancellation()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
}
