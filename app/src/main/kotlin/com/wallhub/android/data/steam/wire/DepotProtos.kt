package com.wallhub.android.data.steam.wire

import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import okio.Buffer
import okio.ByteString

/**
 * Hand-written Wire adapters for the Steam depot protocol messages.
 *
 * The Wire Gradle plugin cannot run against the app's AGP 9 (it still casts to the removed
 * `BaseExtension`), and kSteam's runtime only needs `ProtoAdapter` implementations. Field
 * numbers follow SteamDatabase's `steammessages_clientserver_2.proto` and
 * `content_manifest.proto`.
 */
class CMsgClientGetDepotDecryptionKey(
    val depot_id: Int? = null,
    val app_id: Int? = null,
) {
    companion object {
        val ADAPTER: ProtoAdapter<CMsgClientGetDepotDecryptionKey> =
            object : ProtoAdapter<CMsgClientGetDepotDecryptionKey>(FieldEncoding.LENGTH_DELIMITED, CMsgClientGetDepotDecryptionKey::class) {
                override fun encodedSize(value: CMsgClientGetDepotDecryptionKey): Int {
                    val buffer = Buffer()
                    encode(buffer, value)
                    return buffer.size.toInt()
                }

                override fun encode(
                    writer: ProtoWriter,
                    value: CMsgClientGetDepotDecryptionKey,
                ) {
                    value.depot_id?.let {
                        writer.writeTag(1, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.app_id?.let {
                        writer.writeTag(2, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                }

                override fun decode(reader: ProtoReader): CMsgClientGetDepotDecryptionKey {
                    var depotId: Int? = null
                    var appId: Int? = null
                    reader.forEachTag { tag ->
                        when (tag) {
                            1 -> depotId = reader.readVarint32()
                            2 -> appId = reader.readVarint32()
                            else -> reader.skip()
                        }
                    }
                    return CMsgClientGetDepotDecryptionKey(depot_id = depotId, app_id = appId)
                }
            }
                override fun redact(value: CMsgClientGetDepotDecryptionKey): CMsgClientGetDepotDecryptionKey = value
    }
}

class CMsgClientGetDepotDecryptionKeyResponse(
    val eresult: Int? = null,
    val depot_id: Int? = null,
    val depot_encryption_key: ByteString? = null,
) {
    companion object {
        val ADAPTER: ProtoAdapter<CMsgClientGetDepotDecryptionKeyResponse> =
            object : ProtoAdapter<CMsgClientGetDepotDecryptionKeyResponse>(FieldEncoding.LENGTH_DELIMITED, CMsgClientGetDepotDecryptionKeyResponse::class) {
                override fun encodedSize(value: CMsgClientGetDepotDecryptionKeyResponse): Int {
                    val buffer = Buffer()
                    encode(buffer, value)
                    return buffer.size.toInt()
                }

                override fun encode(
                    writer: ProtoWriter,
                    value: CMsgClientGetDepotDecryptionKeyResponse,
                ) {
                    value.eresult?.let {
                        writer.writeTag(1, FieldEncoding.VARINT)
                        writer.writeVarint64(it.toLong())
                    }
                    value.depot_id?.let {
                        writer.writeTag(2, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.depot_encryption_key?.let {
                        writer.writeTag(3, FieldEncoding.LENGTH_DELIMITED)
                        writer.writeBytes(it)
                    }
                }

                override fun decode(reader: ProtoReader): CMsgClientGetDepotDecryptionKeyResponse {
                    var eresult: Int? = null
                    var depotId: Int? = null
                    var depotKey: ByteString? = null
                    reader.forEachTag { tag ->
                        when (tag) {
                            1 -> eresult = reader.readVarint32()
                            2 -> depotId = reader.readVarint32()
                            3 -> depotKey = reader.readBytes()
                            else -> reader.skip()
                        }
                    }
                    return CMsgClientGetDepotDecryptionKeyResponse(
                        eresult = eresult,
                        depot_id = depotId,
                        depot_encryption_key = depotKey,
                    )
                }
            }
                override fun redact(value: CMsgClientGetDepotDecryptionKeyResponse): CMsgClientGetDepotDecryptionKeyResponse = value
    }
}

class ContentManifestPayload(
    val mappings: List<FileMapping> = emptyList(),
) {
    class ChunkData(
        val sha: ByteString? = null,
        val crc: Int? = null,
        val offset: Long? = null,
        val cb_original: Int? = null,
        val cb_compressed: Int? = null,
    )

    class FileMapping(
        val filename: String? = null,
        val size: Long? = null,
        val flags: Int? = null,
        val sha_filename: ByteString? = null,
        val sha_content: ByteString? = null,
        val chunks: List<ChunkData> = emptyList(),
        val linktarget: String? = null,
    )

    companion object {
        val ADAPTER: ProtoAdapter<ContentManifestPayload> =
            object : ProtoAdapter<ContentManifestPayload>(FieldEncoding.LENGTH_DELIMITED, ContentManifestPayload::class) {
                override fun encodedSize(value: ContentManifestPayload): Int {
                    val buffer = Buffer()
                    encode(buffer, value)
                    return buffer.size.toInt()
                }

                override fun encode(
                    writer: ProtoWriter,
                    value: ContentManifestPayload,
                ) {
                    value.mappings.forEach { mapping ->
                        writer.writeTag(1, FieldEncoding.LENGTH_DELIMITED)
                        writer.writeBytes(FileMappingAdapter.encodeByteString(mapping))
                    }
                }

                override fun decode(reader: ProtoReader): ContentManifestPayload {
                    val mappings = mutableListOf<FileMapping>()
                    reader.forEachTag { tag ->
                        when (tag) {
                            1 -> mappings += FileMappingAdapter.decode(reader)
                            else -> reader.skip()
                        }
                    }
                    return ContentManifestPayload(mappings)
                }
            }
                override fun redact(value: ContentManifestPayload): ContentManifestPayload = value
    }

    private object FileMappingAdapter : ProtoAdapter<FileMapping>(FieldEncoding.LENGTH_DELIMITED, FileMapping::class) {
        override fun encodedSize(value: FileMapping): Int {
            val buffer = Buffer()
            encode(buffer, value)
            return buffer.size.toInt()
        }

        override fun encode(
            writer: ProtoWriter,
            value: FileMapping,
        ) {
            value.filename?.let {
                writer.writeTag(1, FieldEncoding.LENGTH_DELIMITED)
                writer.writeString(it)
            }
            value.size?.let {
                writer.writeTag(2, FieldEncoding.VARINT)
                writer.writeVarint64(it)
            }
            value.flags?.let {
                writer.writeTag(3, FieldEncoding.VARINT)
                writer.writeVarint32(it)
            }
            value.sha_filename?.let {
                writer.writeTag(4, FieldEncoding.LENGTH_DELIMITED)
                writer.writeBytes(it)
            }
            value.sha_content?.let {
                writer.writeTag(5, FieldEncoding.LENGTH_DELIMITED)
                writer.writeBytes(it)
            }
            value.chunks.forEach { chunk ->
                writer.writeTag(6, FieldEncoding.LENGTH_DELIMITED)
                writer.writeBytes(ChunkDataAdapter.encodeByteString(chunk))
            }
            value.linktarget?.let {
                writer.writeTag(7, FieldEncoding.LENGTH_DELIMITED)
                writer.writeString(it)
            }
        }

        override fun decode(reader: ProtoReader): FileMapping {
            var filename: String? = null
            var size: Long? = null
            var flags: Int? = null
            var shaFilename: ByteString? = null
            var shaContent: ByteString? = null
            val chunks = mutableListOf<ChunkData>()
            var linktarget: String? = null
            reader.forEachTag { tag ->
                when (tag) {
                    1 -> filename = reader.readString()
                    2 -> size = reader.readVarint64()
                    3 -> flags = reader.readVarint32()
                    4 -> shaFilename = reader.readBytes()
                    5 -> shaContent = reader.readBytes()
                    6 -> chunks += ChunkDataAdapter.decode(reader)
                    7 -> linktarget = reader.readString()
                    else -> reader.skip()
                }
            }
            return FileMapping(
                filename = filename,
                size = size,
                flags = flags,
                sha_filename = shaFilename,
                sha_content = shaContent,
                chunks = chunks,
                linktarget = linktarget,
            )
        }

        override fun redact(value: FileMapping): FileMapping = value
    }

    private object ChunkDataAdapter : ProtoAdapter<ChunkData>(FieldEncoding.LENGTH_DELIMITED, ChunkData::class) {
        override fun encodedSize(value: ChunkData): Int {
            val buffer = Buffer()
            encode(buffer, value)
            return buffer.size.toInt()
        }

        override fun encode(
            writer: ProtoWriter,
            value: ChunkData,
        ) {
            value.sha?.let {
                writer.writeTag(1, FieldEncoding.LENGTH_DELIMITED)
                writer.writeBytes(it)
            }
            value.crc?.let {
                writer.writeTag(2, FieldEncoding.FIXED32)
                writer.writeFixed32(it)
            }
            value.offset?.let {
                writer.writeTag(3, FieldEncoding.VARINT)
                writer.writeVarint64(it)
            }
            value.cb_original?.let {
                writer.writeTag(4, FieldEncoding.VARINT)
                writer.writeVarint32(it)
            }
            value.cb_compressed?.let {
                writer.writeTag(5, FieldEncoding.VARINT)
                writer.writeVarint32(it)
            }
        }

        override fun decode(reader: ProtoReader): ChunkData {
            var sha: ByteString? = null
            var crc: Int? = null
            var offset: Long? = null
            var cbOriginal: Int? = null
            var cbCompressed: Int? = null
            reader.forEachTag { tag ->
                when (tag) {
                    1 -> sha = reader.readBytes()
                    2 -> crc = reader.readFixed32()
                    3 -> offset = reader.readVarint64()
                    4 -> cbOriginal = reader.readVarint32()
                    5 -> cbCompressed = reader.readVarint32()
                    else -> reader.skip()
                }
            }
            return ChunkData(
                sha = sha,
                crc = crc,
                offset = offset,
                cb_original = cbOriginal,
                cb_compressed = cbCompressed,
            )
        }

        override fun redact(value: ChunkData): ChunkData = value
    }
}

class ContentManifestMetadata(
    val depot_id: Int? = null,
    val gid_manifest: Long? = null,
    val creation_time: Int? = null,
    val filenames_encrypted: Boolean? = null,
    val cb_disk_original: Long? = null,
    val cb_disk_compressed: Long? = null,
    val unique_chunks: Int? = null,
    val crc_encrypted: Int? = null,
    val crc_clear: Int? = null,
) {
    companion object {
        val ADAPTER: ProtoAdapter<ContentManifestMetadata> =
            object : ProtoAdapter<ContentManifestMetadata>(FieldEncoding.LENGTH_DELIMITED, ContentManifestMetadata::class) {
                override fun encodedSize(value: ContentManifestMetadata): Int {
                    val buffer = Buffer()
                    encode(buffer, value)
                    return buffer.size.toInt()
                }

                override fun encode(
                    writer: ProtoWriter,
                    value: ContentManifestMetadata,
                ) {
                    value.depot_id?.let {
                        writer.writeTag(1, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.gid_manifest?.let {
                        writer.writeTag(2, FieldEncoding.VARINT)
                        writer.writeVarint64(it)
                    }
                    value.creation_time?.let {
                        writer.writeTag(3, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.filenames_encrypted?.let {
                        writer.writeTag(4, FieldEncoding.VARINT)
                        writer.writeVarint32(if (it) 1 else 0)
                    }
                    value.cb_disk_original?.let {
                        writer.writeTag(5, FieldEncoding.VARINT)
                        writer.writeVarint64(it)
                    }
                    value.cb_disk_compressed?.let {
                        writer.writeTag(6, FieldEncoding.VARINT)
                        writer.writeVarint64(it)
                    }
                    value.unique_chunks?.let {
                        writer.writeTag(7, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.crc_encrypted?.let {
                        writer.writeTag(8, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                    value.crc_clear?.let {
                        writer.writeTag(9, FieldEncoding.VARINT)
                        writer.writeVarint32(it)
                    }
                }

                override fun decode(reader: ProtoReader): ContentManifestMetadata {
                    var depotId: Int? = null
                    var gidManifest: Long? = null
                    var creationTime: Int? = null
                    var filenamesEncrypted: Boolean? = null
                    var cbDiskOriginal: Long? = null
                    var cbDiskCompressed: Long? = null
                    var uniqueChunks: Int? = null
                    var crcEncrypted: Int? = null
                    var crcClear: Int? = null
                    reader.forEachTag { tag ->
                        when (tag) {
                            1 -> depotId = reader.readVarint32()
                            2 -> gidManifest = reader.readVarint64()
                            3 -> creationTime = reader.readVarint32()
                            4 -> filenamesEncrypted = reader.readVarint32() != 0
                            5 -> cbDiskOriginal = reader.readVarint64()
                            6 -> cbDiskCompressed = reader.readVarint64()
                            7 -> uniqueChunks = reader.readVarint32()
                            8 -> crcEncrypted = reader.readVarint32()
                            9 -> crcClear = reader.readVarint32()
                            else -> reader.skip()
                        }
                    }
                    return ContentManifestMetadata(
                        depot_id = depotId,
                        gid_manifest = gidManifest,
                        creation_time = creationTime,
                        filenames_encrypted = filenamesEncrypted,
                        cb_disk_original = cbDiskOriginal,
                        cb_disk_compressed = cbDiskCompressed,
                        unique_chunks = uniqueChunks,
                        crc_encrypted = crcEncrypted,
                        crc_clear = crcClear,
                    )
                }

                override fun redact(value: ContentManifestMetadata): ContentManifestMetadata = value
            }
    }
}
