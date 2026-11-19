package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionCheckpointRequest
import com.wallhub.prototype.mpkg.ResourceStrategy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionCheckpointIoTest {
    @Test
    fun `new checkpoint reads verified output once and defers artifact verification until recovery`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-io").toFile()
        var verifiedOutputReads = 0
        var verifiedOutputBytesRead = 0L
        var persistedArtifactReads = 0
        var persistedArtifactBytesRead = 0L
        try {
            val store =
                ConversionCheckpointStore(
                    filesDir = root,
                    onArtifactValidationRead = { persistedArtifactReads += 1 },
                    onArtifactValidationBytesRead = { persistedArtifactBytesRead += it },
                )
            val adapter =
                ConversionResourceCheckpointAdapter(
                    identity = identity(),
                    store = store,
                    onVerifiedOutputFullRead = { verifiedOutputReads += 1 },
                    onVerifiedOutputBytesRead = { verifiedOutputBytesRead += it },
                )
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(1024 * 1024) { it.toByte() }) }

            val recorded = adapter.record(request(), output)

            assertEquals(1, verifiedOutputReads)
            assertEquals(output.length(), verifiedOutputBytesRead)
            assertEquals(0, persistedArtifactReads)
            assertEquals(0L, persistedArtifactBytesRead)
            assertTrue(recorded.isFile)
            assertEquals(recorded, adapter.reuse(request()))
            assertEquals(1, persistedArtifactReads)
            assertEquals(recorded.length(), persistedArtifactBytesRead)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation during checkpoint copy publishes no reusable artifact`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-cancel").toFile()
        try {
            val adapter = ConversionResourceCheckpointAdapter(identity(), ConversionCheckpointStore(root))
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(1024 * 1024)) }
            var checks = 0

            assertFailsWith<CancellationException> {
                adapter.record(request(), output) {
                    checks += 1
                    if (checks >= 3) throw CancellationException("cancel checkpoint copy")
                }
            }

            assertNull(adapter.reuse(request()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `recovered checkpoint rejects same-length tampering and stale source identity`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-tamper").toFile()
        try {
            val adapter = ConversionResourceCheckpointAdapter(identity(), ConversionCheckpointStore(root))
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
            val recorded = adapter.record(request(), output)
            val staleRequest = request().copy(sourceSha256 = "c".repeat(64))
            assertNull(adapter.reuse(staleRequest))

            val tampered = recorded.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }
            recorded.writeBytes(tampered)
            assertNull(adapter.reuse(request()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unreadable attempt identity invalidates old artifacts before rebinding`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-identity").toFile()
        try {
            val original = ConversionResourceCheckpointAdapter(identity(), ConversionCheckpointStore(root))
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
            val recorded = original.record(request(), output)
            val identityFile = root.walkTopDown().single { it.isFile && it.name == ".identity" }
            identityFile.writeText("truncated")

            val changedIdentity = identity().copy(planSha256 = "d".repeat(64), converterVersion = "test-v2")
            val rebound = ConversionResourceCheckpointAdapter(changedIdentity, ConversionCheckpointStore(root))

            assertNull(rebound.reuse(request()))
            assertTrue(!recorded.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `binding an attempt removes orphan temporary files without deleting artifacts`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-orphan").toFile()
        try {
            val store = ConversionCheckpointStore(root)
            val adapter = ConversionResourceCheckpointAdapter(identity(), store)
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
            val recorded = adapter.record(request(), output)
            val attemptDirectory = store.attemptDirectory(identity())
            val orphan = attemptDirectory.resolve(".orphan.tmp").apply { writeBytes(ByteArray(1024)) }

            ConversionResourceCheckpointAdapter(identity(), store)

            assertTrue(!orphan.exists())
            assertTrue(recorded.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `recovered checkpoint rejects a truncated artifact`() {
        val root = Files.createTempDirectory("wallhub-checkpoint-truncated").toFile()
        try {
            val adapter = ConversionResourceCheckpointAdapter(identity(), ConversionCheckpointStore(root))
            val output = root.resolve("converted.tex").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
            val recorded = adapter.record(request(), output)
            recorded.writeBytes(recorded.readBytes().copyOf(128))

            assertNull(adapter.reuse(request()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun identity() =
        ConversionCheckpointIdentity(
            taskId = "checkpoint-io",
            attempt = 1,
            planSha256 = "a".repeat(64),
            sourceIdentity = "source-v1",
            presetId = "high_quality",
            preserveAudio = true,
            converterVersion = "test",
        )

    private fun request() =
        ConversionCheckpointRequest(
            path = "materials/test.tex",
            sourceOffset = 10,
            sourceLength = 100,
            sourceSha256 = "b".repeat(64),
            strategy = ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
        )
}
