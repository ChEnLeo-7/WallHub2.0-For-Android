package com.wallhub.android.data.vpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Socks5ProtocolTest {
    @Test
    fun `IPv4 CONNECT is parsed and acknowledged`() {
        val request = byteArrayOf(
            5, 1, 0,
            5, 1, 0, 1,
            23, 44, 248.toByte(), 222.toByte(),
            1, 0xbb.toByte(),
        )
        val output = ByteArrayOutputStream()

        val target = Socks5Protocol.negotiate(ByteArrayInputStream(request), output)
        Socks5Protocol.sendSuccess(output)

        assertEquals(InetAddress.getByName("23.44.248.222"), target.address)
        assertEquals(443, target.port)
        assertContentEquals(
            byteArrayOf(5, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0),
            output.toByteArray(),
        )
    }

    @Test
    fun `domain CONNECT remains unresolved`() {
        val host = "steamcommunity.com".toByteArray(Charsets.US_ASCII)
        val request = byteArrayOf(5, 1, 0, 5, 1, 0, 3, host.size.toByte()) + host +
            byteArrayOf(1, 0xbb.toByte())

        val target = Socks5Protocol.negotiate(
            ByteArrayInputStream(request),
            ByteArrayOutputStream(),
        )

        assertEquals("steamcommunity.com", target.host)
        assertEquals(null, target.address)
        assertEquals(443, target.port)
    }

    @Test
    fun `authentication-only client is rejected`() {
        val output = ByteArrayOutputStream()

        assertFailsWith<Socks5Protocol.Socks5Exception> {
            Socks5Protocol.negotiate(
                ByteArrayInputStream(byteArrayOf(5, 1, 2)),
                output,
            )
        }

        assertContentEquals(byteArrayOf(5, 0xff.toByte()), output.toByteArray())
    }
}
