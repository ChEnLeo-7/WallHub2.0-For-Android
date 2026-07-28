package com.wallhub.android.data.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

class SteamVpnSocksServerTest {
    @Test
    fun `server accepts Firestack IPv4 loopback connections`() {
        val server = SteamVpnSocksServer(
            socketFactory = object : ProtectedSocketFactory {
                override fun connect(target: SocksTarget): Socket =
                    error("SOCKS negotiation is not part of this test")
            },
        )

        try {
            server.start()
            Socket().use { client ->
                client.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port),
                    1_000,
                )
                assertTrue(client.isConnected)
            }
        } finally {
            server.close()
        }
    }
}
