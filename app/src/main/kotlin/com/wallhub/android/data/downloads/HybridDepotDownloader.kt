package com.wallhub.android.data.downloads

import android.os.SystemClock
import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DepotDownloaderCapability
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid migration Phase 3 routing engine: prefers the Rust depot core for every capability
 * it declares and falls back to the Kotlin engine on any failure. A failing Rust engine is
 * re-probed after [RUST_REPROBE_MS] so transient native issues self-heal while the Kotlin
 * path keeps verification and decoding moving.
 */
@Singleton
class HybridDepotDownloader internal constructor(
    private val kotlinEngine: DepotDownloader,
    private val rustEngine: DepotDownloader,
    private val diagnostics: DiagnosticRepository,
    private val elapsedRealtimeMillis: () -> Long,
) : DepotDownloader {
    @Inject
    constructor(
        kotlinEngine: KotlinDepotDownloader,
        rustEngine: RustDepotDownloader,
        diagnostics: DiagnosticRepository,
    ) : this(kotlinEngine, rustEngine, diagnostics, SystemClock::elapsedRealtime)

    override val capabilities: Set<DepotDownloaderCapability> =
        rustEngine.capabilities + kotlinEngine.capabilities

    @Volatile
    private var consecutiveRustFailures: Int = 0

    @Volatile
    private var lastRustFailureElapsedRealtime: Long = 0L

    override suspend fun verifyChunk(
        data: ByteArray,
        expectedChecksum: Int,
    ): Boolean {
        if (rustUsable()) {
            runCatching { rustEngine.verifyChunk(data, expectedChecksum) }
                .onSuccess { verified ->
                    if (verified) recordRustSuccess()
                    return verified
                }
                .onFailure { error -> recordRustFailure("verifyChunk", error) }
        }
        return kotlinEngine.verifyChunk(data, expectedChecksum)
    }

    override suspend fun decodeChunk(
        chunk: DepotChunkSpec,
        encrypted: ByteArray,
        depotKey: ByteArray,
    ): Result<ByteArray> {
        if (rustUsable()) {
            runCatching { rustEngine.decodeChunk(chunk, encrypted, depotKey) }
                .onSuccess { decoded ->
                    if (decoded.isSuccess) recordRustSuccess()
                    return decoded
                }
                .onFailure { error -> recordRustFailure("decodeChunk", error) }
        }
        return kotlinEngine.decodeChunk(chunk, encrypted, depotKey)
    }

    /** One-shot CDN chunk download + decode; only the Rust engine owns network chunk fetch. */
    override suspend fun downloadAndDecodeChunk(
        url: String,
        depotKey: ByteArray,
        chunk: DepotChunkSpec,
    ): Result<ByteArray> {
        if (rustUsable()) {
            runCatching { rustEngine.downloadAndDecodeChunk(url, depotKey, chunk) }
                .onSuccess { decoded ->
                    if (decoded.isSuccess) recordRustSuccess()
                    return decoded
                }
                .onFailure { error -> recordRustFailure("downloadAndDecodeChunk", error) }
        }
        return Result.failure(
            IllegalStateException("Rust depot engine is not usable for chunk download: $url"),
        )
    }

    private fun rustUsable(): Boolean =
        DepotDownloaderCapability.CHUNK_DECODE in rustEngine.capabilities &&
            (consecutiveRustFailures < MAX_RUST_CONSECUTIVE_FAILURES ||
                elapsedRealtimeMillis() - lastRustFailureElapsedRealtime >= RUST_REPROBE_MS)

    private fun recordRustSuccess() {
        consecutiveRustFailures = 0
    }

    private suspend fun recordRustFailure(
        operation: String,
        error: Throwable,
    ) {
        consecutiveRustFailures += 1
        lastRustFailureElapsedRealtime = elapsedRealtimeMillis()
        diagnostics.record(
            DiagnosticEvent(
                source = "HybridDepotDownloader",
                level = DiagnosticLevel.WARNING,
                message = "Rust depot engine failure ($operation, consecutive=$consecutiveRustFailures); using Kotlin engine",
                attributes = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
            ),
        )
    }

    companion object {
        const val MAX_RUST_CONSECUTIVE_FAILURES: Int = 3
        const val RUST_REPROBE_MS: Long = 5 * 60_000L
    }
}
