package com.wallhub.android.data.steamaccess

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamNetworkDiagnosticsTest {
    @Test
    fun `public Steam requests receive stable diagnostic operation names`() {
        assertEquals(
            "public_details",
            steamNetworkOperation("api.steampowered.com", "/ISteamRemoteStorage/GetPublishedFileDetails/v1/"),
        )
        assertEquals(
            "community_comments",
            steamNetworkOperation("steamcommunity.com", "/comment/PublishedFile_Public/render/123/-1/"),
        )
        assertEquals(
            "workshop_image",
            steamNetworkOperation("images.steamusercontent.com", "/ugc/123/ABC/"),
        )
        assertEquals("steam_http", steamNetworkOperation("api.steampowered.com", "/unknown"))
    }

    @Test
    fun `comment diagnostics retain only a validated Workshop id`() {
        assertEquals(
            "3811980360",
            steamNetworkResourceId(
                "steamcommunity.com",
                "/comment/PublishedFile_Public/render/76561198000000000/3811980360/",
            ),
        )
        assertEquals(
            "3811980360",
            steamNetworkResourceId(
                "steamcommunity.com",
                "/comment/PublishedFile_Public/render/3811980360/-1/",
            ),
        )
        assertNull(
            steamNetworkResourceId(
                "steamcommunity.com",
                "/comment/PublishedFile_Public/render/not-a-number/-1/",
            ),
        )
    }

    @Test
    fun `failure chain reports types without exception messages`() {
        val error = IOException("https://example.invalid/?token=secret", SocketTimeoutException("secret"))
        error.cause?.initCause(EOFException("more secret"))

        assertEquals(
            "IOException<-SocketTimeoutException<-EOFException",
            diagnosticFailureChain(error),
        )
    }
}
