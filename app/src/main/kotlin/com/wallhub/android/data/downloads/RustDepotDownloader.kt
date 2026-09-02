package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DepotDownloaderCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rust-backed depot engine (`wallhub-rust` JNI bridge). Owns the CPU- and network-bound
 * chunk path: verification, full chunk decode, and CDN chunk download. When the native
 * library is unavailable the engine reports no capabilities so hybrid routing falls back.
 */
@Singleton
class RustDepotDownloader
    @Inject
    constructor() : DepotDownloader {
        private val nativeAvailable = WallHubRust.available

        override val capabilities: Set<DepotDownloaderCapability> =
            if (nativeAvailable) {
                setOf(
                    DepotDownloaderCapability.CHUNK_DOWNLOAD,
                    DepotDownloaderCapability.CHUNK_VERIFICATION,
                    DepotDownloaderCapability.CHUNK_DECODE,
                )
            } else {
                emptySet()
            }

        override suspend fun verifyChunk(
            data: ByteArray,
            expectedChecksum: Int,
        ): Boolean =
            withContext(Dispatchers.Default) {
                require(nativeAvailable) { "wallhub-rust native engine is unavailable" }
                WallHubRust.verifyChunk(data, expectedChecksum)
            }

        override suspend fun decodeChunk(
            chunk: DepotChunkSpec,
            encrypted: ByteArray,
            depotKey: ByteArray,
        ): Result<ByteArray> =
            withContext(Dispatchers.Default) {
                runCatching {
                    require(nativeAvailable) { "wallhub-rust native engine is unavailable" }
                    WallHubRust.decodeChunk(
                        encrypted = encrypted,
                        depotKey = depotKey,
                        expectedChecksum = chunk.checksum,
                        uncompressedLength = chunk.uncompressedLength,
                    )
                }
            }

        override suspend fun downloadAndDecodeChunk(
            url: String,
            depotKey: ByteArray,
            chunk: DepotChunkSpec,
        ): Result<ByteArray> =
            withContext(Dispatchers.IO) {
                runCatching {
                    require(nativeAvailable) { "wallhub-rust native engine is unavailable" }
                    WallHubRust.downloadAndDecodeChunk(
                        url = url,
                        depotKey = depotKey,
                        expectedChecksum = chunk.checksum,
                        uncompressedLength = chunk.uncompressedLength,
                        timeoutMs = DEFAULT_CHUNK_TIMEOUT_MS,
                    )
                }
            }

        companion object {
            const val DEFAULT_CHUNK_TIMEOUT_MS: Int = 45_000
        }
    }
