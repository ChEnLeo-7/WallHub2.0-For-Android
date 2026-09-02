package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloaderCapability
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HybridDepotDownloaderTest {
    private class RecordingDiagnostics : DiagnosticRepository {
        val events = mutableListOf<DiagnosticEvent>()

        override suspend fun record(event: DiagnosticEvent) {
            events += event
        }

        override suspend fun readRecent(limit: Int): List<DiagnosticEvent> = events.takeLast(limit)

        override suspend fun exportRedactedText(): String = ""

        override suspend fun clear() = events.clear()
    }

    private class FakeEngine(
        override val capabilities: Set<DepotDownloaderCapability>,
    ) : com.wallhub.android.core.model.DepotDownloader {
        var verifyResult: Boolean = true
        var verifyError: Throwable? = null
        var decodeResult: Result<ByteArray> = Result.success(ByteArray(0))
        var calls: Int = 0
            private set

        override suspend fun verifyChunk(
            data: ByteArray,
            expectedChecksum: Int,
        ): Boolean {
            calls += 1
            verifyError?.let { throw it }
            return verifyResult
        }

        override suspend fun decodeChunk(
            chunk: DepotChunkSpec,
            encrypted: ByteArray,
            depotKey: ByteArray,
        ): Result<ByteArray> {
            calls += 1
            return decodeResult
        }
    }

    @Test
    fun `rust engine is preferred while healthy`() =
        runTest {
            val rust = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_DECODE, DepotDownloaderCapability.CHUNK_VERIFICATION))
            val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
            val hybrid = HybridDepotDownloader(kotlin, rust, RecordingDiagnostics()) { 0L }

            assertTrue(hybrid.verifyChunk(ByteArray(8), 1))
            assertEquals(1, rust.calls)
            assertEquals(0, kotlin.calls)
        }

    @Test
    fun `rust failure falls back to the kotlin engine and is diagnosed`() =
        runTest {
            val rust = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
            rust.verifyError = IllegalStateException("native engine panicked")
            val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
            val diagnostics = RecordingDiagnostics()
            val hybrid = HybridDepotDownloader(kotlin, rust, diagnostics) { 0L }

            assertTrue(hybrid.verifyChunk(ByteArray(8), 1))

            assertEquals(1, rust.calls)
            assertEquals(1, kotlin.calls)
            assertEquals(DiagnosticLevel.WARNING, diagnostics.events.single().level)
        }

    @Test
    fun `unavailable rust engine routes everything to kotlin`() =
        runTest {
            val rust = FakeEngine(emptySet())
            val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION, DepotDownloaderCapability.CHUNK_DECODE))
            val hybrid = HybridDepotDownloader(kotlin, rust, RecordingDiagnostics()) { 0L }

            assertTrue(hybrid.verifyChunk(ByteArray(8), 1))
            assertEquals(0, rust.calls)
            assertEquals(1, kotlin.calls)
            assertFalse(DepotDownloaderCapability.CHUNK_DOWNLOAD in hybrid.capabilities)
        }

    @Test
    fun `capabilities are the union of both engines`() {
        val rust = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_DOWNLOAD, DepotDownloaderCapability.CHUNK_DECODE))
        val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
        val hybrid = HybridDepotDownloader(kotlin, rust, RecordingDiagnostics()) { 0L }

        assertEquals(
            setOf(
                DepotDownloaderCapability.CHUNK_DOWNLOAD,
                DepotDownloaderCapability.CHUNK_DECODE,
                DepotDownloaderCapability.CHUNK_VERIFICATION,
            ),
            hybrid.capabilities,
        )
    }

    @Test
    fun `repeated rust failures temporarily disable the rust path`() =
        runTest {
            val rust = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
            rust.verifyError = IllegalStateException("native engine panicked")
            val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_VERIFICATION))
            var nowMs = 0L
            val hybrid = HybridDepotDownloader(kotlin, rust, RecordingDiagnostics()) { nowMs }

            repeat(HybridDepotDownloader.MAX_RUST_CONSECUTIVE_FAILURES) {
                hybrid.verifyChunk(ByteArray(8), 1)
            }
            val rustCallsAfterWarmup = rust.calls
            val kotlinCallsAfterWarmup = kotlin.calls

            // Within the reprobe window the Rust engine stays disabled.
            nowMs += HybridDepotDownloader.RUST_REPROBE_MS / 2
            hybrid.verifyChunk(ByteArray(8), 1)
            assertEquals(rustCallsAfterWarmup, rust.calls)
            assertEquals(kotlinCallsAfterWarmup + 1, kotlin.calls)

            // After the reprobe window elapses the Rust engine is retried first.
            nowMs += HybridDepotDownloader.RUST_REPROBE_MS
            hybrid.verifyChunk(ByteArray(8), 1)
            assertEquals(rustCallsAfterWarmup + 1, rust.calls)
            assertEquals(kotlinCallsAfterWarmup + 1, kotlin.calls)
        }

    @Test
    fun `decode result passes through without a kotlin retry on success`() =
        runTest {
            val payload = ByteArray(16) { 7 }
            val rust = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_DECODE))
            rust.decodeResult = Result.success(payload)
            val kotlin = FakeEngine(setOf(DepotDownloaderCapability.CHUNK_DECODE))
            val hybrid = HybridDepotDownloader(kotlin, rust, RecordingDiagnostics()) { 0L }
            val spec = DepotChunkSpec(checksum = 3, offset = 0L, compressedLength = 8, uncompressedLength = 16)

            val result = hybrid.decodeChunk(spec, ByteArray(8), ByteArray(32))

            assertTrue(result.isSuccess)
            assertEquals(payload, result.getOrThrow())
            assertEquals(0, kotlin.calls)
        }
}
