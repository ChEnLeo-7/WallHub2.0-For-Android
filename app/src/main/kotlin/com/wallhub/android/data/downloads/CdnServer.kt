package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotFileFlag
import com.wallhub.android.core.model.DepotFileSpec
import com.wallhub.android.core.model.DepotManifestSpec
import com.wallhub.android.data.steam.wire.ContentManifestMetadata
import com.wallhub.android.data.steam.wire.ContentManifestPayload

/** Engine-neutral Steam CDN content server replacing JavaSteam's steam.cdn.Server. */
internal class CdnServer(
    val host: String?,
    val vHost: String?,
    val port: Int,
    val https: Boolean,
    val type: String? = null,
    val sourceId: Int = 0,
    val cellId: Int = 0,
    val load: Int = 0,
    val weightedLoad: Float = 0f,
    val numEntries: Int = 0,
    val steamChinaOnly: Boolean = false,
    val useAsProxy: Boolean = false,
    val proxyRequestPathTemplate: String? = null,
    val allowedAppIds: IntArray = intArrayOf(),
) {
    override fun equals(other: Any?): Boolean = other is CdnServer && other.host == host && other.vHost == vHost
    override fun hashCode(): Int = 31 * (host?.hashCode() ?: 0) + (vHost?.hashCode() ?: 0)
}

internal fun CdnServer.depotChunkUrl(
    depotId: Int,
    chunk: DepotChunkSpec,
): String {
    val chunkId = requireNotNull(chunk.chunkId) { "Chunk must have a ChunkID." }
    val chunkIdHex = chunkId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "depot/$depotId/chunk/$chunkIdHex"
}

internal fun CdnServer.depotManifestUrl(
    depotId: Int,
    manifestId: Long,
    manifestRequestCode: Long,
): String =
    if (manifestRequestCode > 0L) {
        "depot/$depotId/manifest/$manifestId/5/$manifestRequestCode"
    } else {
        "depot/$depotId/manifest/$manifestId/5"
    }

/** Magic-delimited manifest container constants (SteamDatabase depot manifest format). */
internal object DepotManifestContainer {
    const val PAYLOAD_MAGIC = 0x71F617D0
    const val METADATA_MAGIC = 0x1F4812BE
    const val SIGNATURE_MAGIC = 0x1B81B817
    const val END_MAGIC = 0x32C415AB
}

internal fun parseDepotManifest(bytes: ByteArray): DepotManifestSpec {
    var payload = ContentManifestPayload()
    var metadata = ContentManifestMetadata()
    val buffer = bytes
    var offset = 0

    fun readInt(): Int {
        val value =
            ((buffer[offset].toInt() and 0xff) shl 24) or
                ((buffer[offset + 1].toInt() and 0xff) shl 16) or
                ((buffer[offset + 2].toInt() and 0xff) shl 8) or
                (buffer[offset + 3].toInt() and 0xff)
        offset += 4
        return value
    }

    var sawPayload = false
    var sawMetadata = false
    loop@ while (offset + 4 <= buffer.size) {
        when (val magic = readInt()) {
            DepotManifestContainer.END_MAGIC -> break@loop
            DepotManifestContainer.PAYLOAD_MAGIC -> {
                val length = readInt()
                payload = ContentManifestPayload.ADAPTER.decode(buffer.copyOfRange(offset, offset + length))
                offset += length
                sawPayload = true
            }

            DepotManifestContainer.METADATA_MAGIC -> {
                val length = readInt()
                metadata = ContentManifestMetadata.ADAPTER.decode(buffer.copyOfRange(offset, offset + length))
                offset += length
                sawMetadata = true
            }

            DepotManifestContainer.SIGNATURE_MAGIC -> {
                val length = readInt()
                offset += length
            }

            else -> error("Unrecognized magic value 0x${magic.toUInt().toString(16)} in depot manifest")
        }
    }
    check(sawPayload && sawMetadata) { "Missing ContentManifest sections required for parsing depot manifest" }

    val files =
        payload.mappings.map { mapping ->
            DepotFileSpec(
                fileName = mapping.filename.orEmpty(),
                totalSize = mapping.size ?: 0L,
                fileHash = mapping.sha_content?.toByteArray() ?: ByteArray(0),
                flags = DepotFileFlag.fromMask(mapping.flags ?: 0),
                chunks =
                    mapping.chunks.map { chunk ->
                        DepotChunkSpec(
                            chunkId = chunk.sha?.toByteArray(),
                            checksum = chunk.crc ?: 0,
                            offset = chunk.offset ?: 0L,
                            compressedLength = chunk.cb_compressed ?: 0,
                            uncompressedLength = chunk.cb_original ?: 0,
                        )
                    },
                linkTarget = mapping.linktarget.orEmpty(),
            )
        }
    return DepotManifestSpec(
        filenamesEncrypted = metadata.filenames_encrypted == true,
        depotId = metadata.depot_id ?: 0,
        manifestGid = metadata.gid_manifest ?: 0L,
        totalUncompressedSize = metadata.cb_disk_original ?: 0L,
        files = files,
    )
}
