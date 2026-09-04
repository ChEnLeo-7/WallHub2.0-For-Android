package com.wallhub.android.data.downloads

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCdnHttpsTest {
    @Test
    fun optionalCdnRouteUsesSecureFallback() {
        val url =
            buildSteamCdnCommand(
                server = CdnServer("cdn.example.test", "cdn.example.test", 80, false),
                command = "depot/1/manifest/2/5",
                query = null,
            )

        assertEquals("https", url.scheme)
        assertEquals(443, url.port)
    }

    @Test
    fun cdnRequestUsesVirtualHostForItsUrl() {
        val url =
            buildSteamCdnCommand(
                server =
                    CdnServer(
                        host = "cdn-identity.example.test",
                        vHost = "cdn.example.test",
                        port = 443,
                        https = true,
                    ),
                command = "depot/1/manifest/2/5",
                query = "token=a%2Fb%3D&expires=123",
            )

        assertEquals("https", url.scheme)
        assertEquals("cdn.example.test", url.host)
        assertEquals("/depot/1/manifest/2/5", url.encodedPath)
        assertEquals("token=a/b=&expires=123", url.query)
        assertEquals("token=a%2Fb%3D&expires=123", url.encodedQuery)
    }

    @Test
    fun cdnProxyUsesOriginHostAndPathInProxyTemplate() {
        val url =
            buildSteamCdnCommand(
                server = CdnServer("origin.example.test", "origin-vhost.example.test", 443, true),
                command = "depot/1/chunk/abcd",
                query = "auth=xyz",
                proxyServer =
                    CdnServer(
                        host = "proxy.example.test",
                        vHost = "proxy-vhost.example.test",
                        port = 443,
                        https = true,
                        useAsProxy = true,
                        proxyRequestPathTemplate = "/proxy/%host%%path%",
                    ),
            )

        assertEquals("https", url.scheme)
        assertEquals("proxy-vhost.example.test", url.host)
        assertEquals("/proxy/origin-vhost.example.test/depot/1/chunk/abcd", url.encodedPath)
        assertEquals("auth=xyz", url.query)
    }

    @Test
    fun cdnAuthUsesServerHostBeforeVirtualHost() {
        assertEquals(
            "cdn-identity.example.test",
            resolveCdnAuthHost("cdn-identity.example.test", "cdn.example.test"),
        )
        assertEquals(
            "cdn.example.test",
            resolveCdnAuthHost(null, "cdn.example.test"),
        )
    }

    @Test
    fun depotManifestContainerUsesLittleEndianHeaders() {
        val payload =
            byteArrayOf(
                0x0A,
                0x15,
                0x0A,
                0x09,
                'v'.code.toByte(),
                'i'.code.toByte(),
                'd'.code.toByte(),
                'e'.code.toByte(),
                'o'.code.toByte(),
                '.'.code.toByte(),
                'm'.code.toByte(),
                'p'.code.toByte(),
                '4'.code.toByte(),
                0x10,
                0x04,
                0x32,
                0x06,
                0x18,
                0x00,
                0x20,
                0x04,
                0x28,
                0x04,
            )
        val metadata = byteArrayOf(0x08, 0xD8.toByte(), 0xAE.toByte(), 0x1A, 0x10, 0x07, 0x28, 0x04)
        val container =
            ByteBuffer
                .allocate(20 + payload.size + metadata.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(DepotManifestContainer.PAYLOAD_MAGIC)
                .putInt(payload.size)
                .put(payload)
                .putInt(DepotManifestContainer.METADATA_MAGIC)
                .putInt(metadata.size)
                .put(metadata)
                .putInt(DepotManifestContainer.END_MAGIC)
                .array()

        val manifest = parseDepotManifest(container)

        assertEquals(431960, manifest.depotId)
        assertEquals("video.mp4", manifest.files.single().fileName)
        assertEquals(4, manifest.files.single().chunks.single().uncompressedLength)
    }
}
