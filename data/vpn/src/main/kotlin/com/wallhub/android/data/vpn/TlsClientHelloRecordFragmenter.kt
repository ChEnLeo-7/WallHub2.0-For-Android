package com.wallhub.android.data.vpn

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

class TlsClientHelloRecordFragmenter(
    private val hostPolicy: (String) -> Boolean = SteamCommunityHostPolicy::matches,
    private val maxBufferedBytes: Int = MAX_BUFFERED_BYTES,
) {
    sealed interface Result {
        data object NeedMore : Result

        data class Passthrough(
            val reason: Reason,
        ) : Result

        data class Fragmented(
            val host: String,
            val bytes: ByteArray,
            val recordCount: Int,
        ) : Result
    }

    enum class Reason {
        NOT_TLS,
        NOT_CLIENT_HELLO,
        NO_SERVER_NAME,
        HOST_NOT_TARGETED,
        ALREADY_FRAGMENTED,
        MALFORMED,
        BUFFER_LIMIT,
    }

    fun inspect(bytes: ByteArray): Result {
        if (bytes.size > maxBufferedBytes) return Result.Passthrough(Reason.BUFFER_LIMIT)
        if (bytes.size < TLS_RECORD_HEADER_SIZE) return Result.NeedMore

        val firstType = bytes.u8(0)
        if (firstType != TLS_HANDSHAKE_CONTENT_TYPE) return Result.Passthrough(Reason.NOT_TLS)
        if (!isSupportedRecordVersion(bytes.u16(1))) return Result.Passthrough(Reason.NOT_TLS)

        val handshakePayload = ByteArrayOutputStream()
        var offset = 0
        var recordsRead = 0
        var clientHelloSize: Int? = null
        var consumedInputBytes = 0
        val legacyVersion = bytes.copyOfRange(1, 3)

        while (true) {
            if (bytes.size - offset < TLS_RECORD_HEADER_SIZE) return Result.NeedMore
            if (bytes.u8(offset) != TLS_HANDSHAKE_CONTENT_TYPE) {
                return if (recordsRead == 0) {
                    Result.Passthrough(Reason.NOT_TLS)
                } else {
                    Result.Passthrough(Reason.MALFORMED)
                }
            }
            if (!isSupportedRecordVersion(bytes.u16(offset + 1))) {
                return Result.Passthrough(Reason.MALFORMED)
            }

            val recordLength = bytes.u16(offset + 3)
            if (recordLength == 0 || recordLength > MAX_TLS_PLAINTEXT_BYTES) {
                return Result.Passthrough(Reason.MALFORMED)
            }
            val recordEnd = offset + TLS_RECORD_HEADER_SIZE + recordLength
            if (recordEnd > maxBufferedBytes) return Result.Passthrough(Reason.BUFFER_LIMIT)
            if (bytes.size < recordEnd) return Result.NeedMore

            handshakePayload.write(bytes, offset + TLS_RECORD_HEADER_SIZE, recordLength)
            recordsRead += 1
            consumedInputBytes = recordEnd
            val payload = handshakePayload.toByteArray()

            if (payload.isNotEmpty() && payload.u8(0) != CLIENT_HELLO_HANDSHAKE_TYPE) {
                return Result.Passthrough(Reason.NOT_CLIENT_HELLO)
            }
            if (clientHelloSize == null && payload.size >= HANDSHAKE_HEADER_SIZE) {
                val bodyLength = payload.u24(1)
                if (bodyLength <= 0 || bodyLength + HANDSHAKE_HEADER_SIZE > maxBufferedBytes) {
                    return Result.Passthrough(Reason.BUFFER_LIMIT)
                }
                clientHelloSize = bodyLength + HANDSHAKE_HEADER_SIZE
            }

            val expectedSize = clientHelloSize
            if (expectedSize != null && payload.size >= expectedSize) {
                if (payload.size != expectedSize) return Result.Passthrough(Reason.MALFORMED)
                val location = findServerName(payload) ?: return Result.Passthrough(Reason.NO_SERVER_NAME)
                if (!hostPolicy(location.host)) return Result.Passthrough(Reason.HOST_NOT_TARGETED)
                if (recordsRead > 1) return Result.Passthrough(Reason.ALREADY_FRAGMENTED)

                val splitAt = location.start + (location.length / 2).coerceAtLeast(1)
                if (splitAt !in 1 until payload.size) return Result.Passthrough(Reason.MALFORMED)
                val records = fragmentHandshake(payload, legacyVersion, splitAt)
                val output = ByteArrayOutputStream(bytes.size + (records.size - 1) * TLS_RECORD_HEADER_SIZE)
                records.forEach(output::write)
                output.write(bytes, consumedInputBytes, bytes.size - consumedInputBytes)
                return Result.Fragmented(
                    host = location.host,
                    bytes = output.toByteArray(),
                    recordCount = records.size,
                )
            }

            offset = recordEnd
            if (offset == bytes.size) return Result.NeedMore
        }
    }

    private fun findServerName(handshake: ByteArray): ServerNameLocation? {
        var cursor = HANDSHAKE_HEADER_SIZE
        if (handshake.size - cursor < 2 + 32 + 1) return null
        cursor += 2 + 32

        val sessionIdLength = handshake.u8(cursor)
        cursor += 1
        if (!handshake.has(cursor, sessionIdLength + 2)) return null
        cursor += sessionIdLength

        val cipherSuitesLength = handshake.u16(cursor)
        cursor += 2
        if (cipherSuitesLength == 0 || cipherSuitesLength % 2 != 0 || !handshake.has(cursor, cipherSuitesLength + 1)) {
            return null
        }
        cursor += cipherSuitesLength

        val compressionMethodsLength = handshake.u8(cursor)
        cursor += 1
        if (compressionMethodsLength == 0 || !handshake.has(cursor, compressionMethodsLength + 2)) return null
        cursor += compressionMethodsLength

        val extensionsLength = handshake.u16(cursor)
        cursor += 2
        val extensionsEnd = cursor + extensionsLength
        if (extensionsEnd != handshake.size || !handshake.has(cursor, extensionsLength)) return null

        while (cursor < extensionsEnd) {
            if (!handshake.has(cursor, 4)) return null
            val type = handshake.u16(cursor)
            val length = handshake.u16(cursor + 2)
            cursor += 4
            if (!handshake.has(cursor, length) || cursor + length > extensionsEnd) return null
            if (type == SERVER_NAME_EXTENSION_TYPE) {
                return parseServerNameExtension(handshake, cursor, length)
            }
            cursor += length
        }
        return null
    }

    private fun parseServerNameExtension(
        handshake: ByteArray,
        extensionStart: Int,
        extensionLength: Int,
    ): ServerNameLocation? {
        if (extensionLength < 5 || !handshake.has(extensionStart, extensionLength)) return null
        val listLength = handshake.u16(extensionStart)
        if (listLength != extensionLength - 2) return null
        var cursor = extensionStart + 2
        val listEnd = cursor + listLength
        while (cursor < listEnd) {
            if (!handshake.has(cursor, 3)) return null
            val nameType = handshake.u8(cursor)
            val nameLength = handshake.u16(cursor + 1)
            cursor += 3
            if (nameLength == 0 || !handshake.has(cursor, nameLength) || cursor + nameLength > listEnd) return null
            if (nameType == HOST_NAME_TYPE) {
                val host = String(handshake, cursor, nameLength, StandardCharsets.US_ASCII)
                    .lowercase(Locale.ROOT)
                    .trimEnd('.')
                if (!isValidHost(host)) return null
                return ServerNameLocation(host, cursor, nameLength)
            }
            cursor += nameLength
        }
        return null
    }

    private fun fragmentHandshake(
        handshake: ByteArray,
        legacyVersion: ByteArray,
        preferredSplit: Int,
    ): List<ByteArray> {
        val boundaries = mutableListOf(0)
        var cursor = 0
        while (cursor < preferredSplit) {
            cursor = minOf(cursor + MAX_TLS_PLAINTEXT_BYTES, preferredSplit)
            boundaries += cursor
        }
        while (cursor < handshake.size) {
            cursor = minOf(cursor + MAX_TLS_PLAINTEXT_BYTES, handshake.size)
            boundaries += cursor
        }

        return boundaries.zipWithNext { start, end ->
            val payloadLength = end - start
            ByteArray(TLS_RECORD_HEADER_SIZE + payloadLength).also { record ->
                record[0] = TLS_HANDSHAKE_CONTENT_TYPE.toByte()
                record[1] = legacyVersion[0]
                record[2] = legacyVersion[1]
                record[3] = (payloadLength ushr 8).toByte()
                record[4] = payloadLength.toByte()
                handshake.copyInto(record, destinationOffset = TLS_RECORD_HEADER_SIZE, startIndex = start, endIndex = end)
            }
        }
    }

    private fun isSupportedRecordVersion(version: Int): Boolean = version in TLS_1_0..TLS_1_3

    private fun isValidHost(host: String): Boolean =
        host.length in 1..253 && host.all { character ->
            character.isLetterOrDigit() || character == '.' || character == '-'
        }

    private data class ServerNameLocation(
        val host: String,
        val start: Int,
        val length: Int,
    )

    companion object {
        const val MAX_BUFFERED_BYTES = 64 * 1024
        const val MAX_TLS_PLAINTEXT_BYTES = 1 shl 14
        private const val TLS_RECORD_HEADER_SIZE = 5
        private const val HANDSHAKE_HEADER_SIZE = 4
        private const val TLS_HANDSHAKE_CONTENT_TYPE = 22
        private const val CLIENT_HELLO_HANDSHAKE_TYPE = 1
        private const val SERVER_NAME_EXTENSION_TYPE = 0
        private const val HOST_NAME_TYPE = 0
        private const val TLS_1_0 = 0x0301
        private const val TLS_1_3 = 0x0304
    }
}

object SteamCommunityHostPolicy {
    fun matches(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        return normalized == "steamcommunity.com" || normalized.endsWith(".steamcommunity.com")
    }
}

private fun ByteArray.has(offset: Int, length: Int): Boolean =
    offset >= 0 && length >= 0 && offset <= size && length <= size - offset

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

private fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

private fun ByteArray.u24(offset: Int): Int =
    (u8(offset) shl 16) or (u8(offset + 1) shl 8) or u8(offset + 2)
