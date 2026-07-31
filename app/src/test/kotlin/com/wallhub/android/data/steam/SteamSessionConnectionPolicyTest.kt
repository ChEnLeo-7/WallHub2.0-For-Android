package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamSessionConnectionPolicyTest {
    @Test
    fun `production configuration only permits websocket CM transport`() {
        val directoryClient = createSteamDirectoryClient()
        val configuration =
            createSteamConfiguration(
                directoryClient = directoryClient,
                serverListProvider = SteamWebSocketServerListProvider(),
            )

        assertEquals(setOf(ProtocolTypes.WEB_SOCKET), configuration.protocolTypes.toSet())
        assertEquals(STEAM_DIRECTORY_CALL_TIMEOUT_MS.toInt(), directoryClient.callTimeoutMillis)
    }

    @Test
    fun `shared server list discards blocking transports`() {
        val provider = SteamWebSocketServerListProvider()
        provider.updateServerList(
            listOf(
                ServerRecord.createWebSocketServer("127.0.0.1:443"),
                ServerRecord.createServer("127.0.0.1", 27017, ProtocolTypes.TCP),
            ),
        )

        val servers = provider.fetchServerList()
        assertEquals(1, servers.size)
        assertTrue(servers.single().protocolTypes.contains(ProtocolTypes.WEB_SOCKET))
        assertFalse(servers.single().protocolTypes.contains(ProtocolTypes.TCP))
    }

    @Test
    fun `only explicit authentication failures expire persisted credentials`() {
        assertTrue(EResult.InvalidPassword.isCredentialRejection())
        assertTrue(EResult.Expired.isCredentialRejection())
        assertTrue(EResult.Revoked.isCredentialRejection())
        assertFalse(EResult.Timeout.isCredentialRejection())
        assertFalse(EResult.ServiceUnavailable.isCredentialRejection())
        assertFalse(EResult.RateLimitExceeded.isCredentialRejection())
    }
}
