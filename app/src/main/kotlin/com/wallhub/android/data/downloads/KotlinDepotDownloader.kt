package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DepotDownloaderCapability
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.util.Adler32 as JavaSteamAdler32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JavaSteam-backed depot engine. Baseline implementation of [DepotDownloader]: chunk decode
 * reuses the hardened transfer path, while the Rust engine is expected to take over the
 * verification and decompression hot paths and add network chunk download.
 */
@Singleton
class KotlinDepotDownloader
    @Inject
    constructor() : DepotDownloader {
        override val capabilities: Set<DepotDownloaderCapability> =
            setOf(
                DepotDownloaderCapability.CHUNK_VERIFICATION,
                DepotDownloaderCapability.CHUNK_DECODE,
            )

        override suspend fun verifyChunk(
            data: ByteArray,
            expectedChecksum: Int,
        ): Boolean =
            withContext(Dispatchers.Default) {
                JavaSteamAdler32.calculate(data) == expectedChecksum
            }

        override suspend fun decodeChunk(
            chunk: DepotChunkSpec,
            encrypted: ByteArray,
            depotKey: ByteArray,
        ): Result<ByteArray> =
            withContext(Dispatchers.Default) {
                runCatching {
                    decodeDepotChunk(chunk.toJavaSteamChunk(), encrypted, depotKey)
                }
            }

        private fun DepotChunkSpec.toJavaSteamChunk(): ChunkData =
            ChunkData(
                chunkID = chunkId,
                checksum = checksum,
                offset = offset,
                compressedLength = compressedLength,
                uncompressedLength = uncompressedLength,
            )
    }
