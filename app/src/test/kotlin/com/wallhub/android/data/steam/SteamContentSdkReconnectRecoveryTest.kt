package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertEquals

class SteamContentSdkReconnectRecoveryTest {
    @Test
    fun `a session failure keeps the engine and reauthenticates`() {
        assertEquals(
            SteamContentSdkReconnectRecovery.REAUTHENTICATE,
            steamContentSdkReconnectRecovery(SteamContentSdkReconnectFailureFamily.SESSION),
        )
    }

    @Test
    fun `a transport failure keeps the engine and opens a fresh live route`() {
        assertEquals(
            SteamContentSdkReconnectRecovery.REOPEN_WITH_LIVE_CANDIDATES,
            steamContentSdkReconnectRecovery(SteamContentSdkReconnectFailureFamily.TRANSPORT),
        )
    }

    @Test
    fun `a protocol adapter failure rebuilds the engine`() {
        assertEquals(
            SteamContentSdkReconnectRecovery.REBUILD_ENGINE,
            steamContentSdkReconnectRecovery(SteamContentSdkReconnectFailureFamily.PROTOCOL_ADAPTER),
        )
    }

    @Test
    fun `an internal failure rebuilds the engine`() {
        assertEquals(
            SteamContentSdkReconnectRecovery.REBUILD_ENGINE,
            steamContentSdkReconnectRecovery(SteamContentSdkReconnectFailureFamily.INTERNAL),
        )
    }
}
