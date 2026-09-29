package com.wallhub.android.data.steam

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether reopening a session keeps the live engine.
 *
 * It matters because the engine owns the download tasks and the SDK releases them with it: reusing the engine
 * across a reconnect is what keeps a running transfer alive, and a route change must still be a restart
 * because every setting the engine carries - transport, candidates, proxies, trust roots - is fixed at
 * creation.
 */
class SteamContentSdkEngineReuseTest {
    @Test
    fun `the same route reuses the live engine`() {
        assertTrue(canReuseEngine(SteamContentSdkEngine.Route(), SteamContentSdkEngine.Route()))
    }

    @Test
    fun `an engine that is not open has nothing to reuse`() {
        assertFalse(canReuseEngine(openRoute = null, requested = SteamContentSdkEngine.Route()))
    }

    @Test
    fun `a different cdn proxy is a restart`() {
        assertFalse(
            canReuseEngine(
                SteamContentSdkEngine.Route(),
                SteamContentSdkEngine.Route(cdnProxy = "socks5://127.0.0.1:1080"),
            ),
        )
    }

    @Test
    fun `a different transport or trust root is a restart`() {
        assertFalse(
            canReuseEngine(
                SteamContentSdkEngine.Route(transport = 1),
                SteamContentSdkEngine.Route(transport = 2),
            ),
        )
        assertFalse(
            canReuseEngine(
                SteamContentSdkEngine.Route(),
                SteamContentSdkEngine.Route(extraTrustRootPem = "-----BEGIN CERTIFICATE-----\n"),
            ),
        )
    }
}
