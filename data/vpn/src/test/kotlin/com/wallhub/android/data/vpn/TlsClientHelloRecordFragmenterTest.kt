package com.wallhub.android.data.vpn

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TlsClientHelloRecordFragmenterTest {
    private val fragmenter = TlsClientHelloRecordFragmenter()

    @Test
    fun `target ClientHello is split without changing handshake bytes`() {
        val input = clientHelloRecord("steamcommunity.com")

        val result = assertIs<TlsClientHelloRecordFragmenter.Result.Fragmented>(fragmenter.inspect(input))

        assertEquals("steamcommunity.com", result.host)
        assertEquals(2, result.recordCount)
        assertContentEquals(recordPayload(input), reassembledHandshake(result.bytes, result.recordCount))
    }

    @Test
    fun `subdomain is targeted while unrelated host passes through`() {
        assertIs<TlsClientHelloRecordFragmenter.Result.Fragmented>(
            fragmenter.inspect(clientHelloRecord("www.steamcommunity.com")),
        )
        val unrelated = assertIs<TlsClientHelloRecordFragmenter.Result.Passthrough>(
            fragmenter.inspect(clientHelloRecord("store.steampowered.com")),
        )
        assertEquals(TlsClientHelloRecordFragmenter.Reason.HOST_NOT_TARGETED, unrelated.reason)
    }

    @Test
    fun `partial record requests more bytes at every boundary`() {
        val input = clientHelloRecord("steamcommunity.com")

        for (length in 0 until input.size) {
            assertIs<TlsClientHelloRecordFragmenter.Result.NeedMore>(
                fragmenter.inspect(input.copyOf(length)),
                "length=$length",
            )
        }
    }

    @Test
    fun `complete trailing records remain byte identical`() {
        val hello = clientHelloRecord("steamcommunity.com")
        val changeCipherSpec = byteArrayOf(20, 3, 3, 0, 1, 1)
        val input = hello + changeCipherSpec

        val result = assertIs<TlsClientHelloRecordFragmenter.Result.Fragmented>(fragmenter.inspect(input))

        assertContentEquals(
            changeCipherSpec,
            result.bytes.copyOfRange(result.bytes.size - changeCipherSpec.size, result.bytes.size),
        )
    }

    @Test
    fun `already fragmented ClientHello is not rewritten`() {
        val input = clientHelloRecord("steamcommunity.com")
        val handshake = recordPayload(input)
        val firstLength = 12
        val alreadyFragmented = tlsRecord(handshake.copyOfRange(0, firstLength)) +
            tlsRecord(handshake.copyOfRange(firstLength, handshake.size))

        val result = assertIs<TlsClientHelloRecordFragmenter.Result.Passthrough>(
            fragmenter.inspect(alreadyFragmented),
        )

        assertEquals(TlsClientHelloRecordFragmenter.Reason.ALREADY_FRAGMENTED, result.reason)
    }

    @Test
    fun `non TLS and malformed lengths pass through`() {
        val nonTls = assertIs<TlsClientHelloRecordFragmenter.Result.Passthrough>(
            fragmenter.inspect(byteArrayOf(1, 2, 3, 4, 5)),
        )
        assertEquals(TlsClientHelloRecordFragmenter.Reason.NOT_TLS, nonTls.reason)

        val malformed = byteArrayOf(22, 3, 3, 0x40, 0x01)
        val malformedResult = assertIs<TlsClientHelloRecordFragmenter.Result.Passthrough>(
            fragmenter.inspect(malformed),
        )
        assertEquals(TlsClientHelloRecordFragmenter.Reason.MALFORMED, malformedResult.reason)
    }

    private fun clientHelloRecord(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val serverNameEntry = byteArrayOf(0, (hostBytes.size ushr 8).toByte(), hostBytes.size.toByte()) + hostBytes
        val serverNameData = byteArrayOf(
            (serverNameEntry.size ushr 8).toByte(),
            serverNameEntry.size.toByte(),
        ) + serverNameEntry
        val serverNameExtension = byteArrayOf(
            0,
            0,
            (serverNameData.size ushr 8).toByte(),
            serverNameData.size.toByte(),
        ) + serverNameData
        val supportedVersions = byteArrayOf(0, 43, 0, 3, 2, 3, 4)
        val extensions = serverNameExtension + supportedVersions
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3))
            write(ByteArray(32) { index -> index.toByte() })
            write(0)
            write(byteArrayOf(0, 2, 0x13, 0x01))
            write(byteArrayOf(1, 0))
            write(byteArrayOf((extensions.size ushr 8).toByte(), extensions.size.toByte()))
            write(extensions)
        }.toByteArray()
        val handshake = byteArrayOf(
            1,
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        ) + body
        return tlsRecord(handshake)
    }

    private fun tlsRecord(payload: ByteArray): ByteArray = byteArrayOf(
        22,
        3,
        3,
        (payload.size ushr 8).toByte(),
        payload.size.toByte(),
    ) + payload

    private fun recordPayload(record: ByteArray): ByteArray = record.copyOfRange(5, record.size)

    private fun reassembledHandshake(records: ByteArray, count: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        repeat(count) {
            assertEquals(22, records[offset].toInt() and 0xff)
            val length = ((records[offset + 3].toInt() and 0xff) shl 8) or
                (records[offset + 4].toInt() and 0xff)
            output.write(records, offset + 5, length)
            offset += 5 + length
        }
        return output.toByteArray()
    }
}
