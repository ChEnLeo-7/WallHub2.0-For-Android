package com.wallhub.android.core.model

/**
 * Engine-neutral depot content seam for the kSteam + Rust hybrid migration.
 *
 * The Kotlin engine (`KotlinDepotDownloader`) implements verification and full chunk decode
 * on top of JavaSteam today; the planned Rust engine fills in chunk download and the
 * performance-critical verification/decompression paths. Engines declare their
 * [capabilities][DepotDownloader.capabilities] so routing and fallback logic can select a
 * supported engine without hard failure.
 */
interface DepotDownloader {
    val capabilities: Set<DepotDownloaderCapability>

    /** Returns true when the payload matches the manifest's expected Adler-32 checksum. */
    suspend fun verifyChunk(
        data: ByteArray,
        expectedChecksum: Int,
    ): Boolean

    /**
     * Decrypts and decompresses one encrypted depot chunk, verifying the embedded checksum.
     * Fails with a [Result] instead of throwing so hybrid engines can fall back uniformly.
     */
    suspend fun decodeChunk(
        chunk: DepotChunkSpec,
        encrypted: ByteArray,
        depotKey: ByteArray,
    ): Result<ByteArray>
}

/** Manifest chunk metadata, engine-neutral equivalent of the JavaSteam chunk record. */
class DepotChunkSpec(
    val chunkId: ByteArray? = null,
    val checksum: Int,
    val offset: Long,
    val compressedLength: Int,
    val uncompressedLength: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is DepotChunkSpec &&
            (chunkId?.contentEquals(other.chunkId) ?: (other.chunkId == null)) &&
            checksum == other.checksum &&
            offset == other.offset &&
            compressedLength == other.compressedLength &&
            uncompressedLength == other.uncompressedLength

    override fun hashCode(): Int {
        var result = chunkId?.contentHashCode() ?: 0
        result = 31 * result + checksum
        result = 31 * result + offset.hashCode()
        result = 31 * result + compressedLength
        result = 31 * result + uncompressedLength
        return result
    }
}

/** Depot pipeline stages an engine can own. */
enum class DepotDownloaderCapability {
    /** Network download of depot chunks from Steam CDN hosts. */
    CHUNK_DOWNLOAD,

    /** Adler-32 payload verification. */
    CHUNK_VERIFICATION,

    /** Full decrypt + decompress + verify of an encrypted depot chunk. */
    CHUNK_DECODE,

    /** Depot manifest resolution and parsing. */
    MANIFEST_RESOLUTION,
}
