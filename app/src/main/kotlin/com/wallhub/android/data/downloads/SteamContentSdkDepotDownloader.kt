package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DepotDownloaderCapability
import io.steamcontent.sdk.SteamChunkCodec
import io.steamcontent.sdk.SteamContentEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Steam Content SDK depot engine: this app's only chunk verifier and decoder.
 *
 * The SDK owns the Steam chunk rule - Steam's Adler-32 variant, the depot AES-256 envelope, and the
 * VZstd/LZMA/PKZIP/stored containers - while this adapter only bridges the existing [DepotDownloader]
 * contract onto it. HTTP transport and CDN authorization stay with the application, so the engine declares
 * verification and decode but not [DepotDownloaderCapability.CHUNK_DOWNLOAD]:
 * [SteamContentSdkWorkshopTransfer] already downloads the encrypted chunk itself and calls [decodeChunk] with it.
 *
 * When the native library cannot be loaded (for example an ABI the preview AAR does not ship yet) the
 * engine reports no capabilities, so the hybrid router treats it as unusable instead of failing silently.
 */
@Singleton
class SteamContentSdkDepotDownloader
    @Inject
    constructor() : DepotDownloader {
        private val nativeAvailable: Boolean =
            runCatching { SteamContentEngine.nativeAbiMajor() }
                .getOrNull() == SteamContentEngine.REQUIRED_ABI_MAJOR

        override val capabilities: Set<DepotDownloaderCapability> =
            if (nativeAvailable) {
                setOf(
                    DepotDownloaderCapability.CHUNK_VERIFICATION,
                    DepotDownloaderCapability.CHUNK_DECODE,
                )
            } else {
                emptySet()
            }

        init {
            // One availability line per process: enough to tell a packaged-but-unusable engine apart
            // from a working one on a device whose ABIs the preview AAR does not all ship.
            Log.i(TAG, "Steam Content SDK depot engine available=$nativeAvailable capabilities=$capabilities")
        }

        override suspend fun verifyChunk(
            data: ByteArray,
            expectedChecksum: Int,
        ): Boolean =
            withContext(Dispatchers.Default) {
                require(nativeAvailable) { "Steam Content SDK native engine is unavailable" }
                SteamChunkCodec.verify(data, expectedChecksum)
            }

        override suspend fun decodeChunk(
            chunk: DepotChunkSpec,
            encrypted: ByteArray,
            depotKey: ByteArray,
        ): Result<ByteArray> =
            withContext(Dispatchers.Default) {
                runCatching {
                    require(nativeAvailable) { "Steam Content SDK native engine is unavailable" }
                    SteamChunkCodec.decode(
                        encrypted = encrypted,
                        key = depotKey,
                        expectedChecksum = chunk.checksum,
                        uncompressedLength = chunk.uncompressedLength,
                    )
                }
            }

        companion object {
            private const val TAG = "WallHubSdkDepot"
        }
    }
