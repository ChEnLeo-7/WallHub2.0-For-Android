package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.SteamAccessMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAccessEnvironmentPolicyTest {
    @Test
    fun `automatic anti blocking bypasses routes when open internet is reachable`() {
        assertFalse(
            shouldApplySteamAccess(
                configured = true,
                mode = SteamAccessMode.SMART_DOH,
                environment = SteamNetworkEnvironment.OPEN_INTERNET,
            ),
        )
    }

    @Test
    fun `automatic anti blocking applies routes when open internet is unavailable`() {
        assertTrue(
            shouldApplySteamAccess(
                configured = true,
                mode = SteamAccessMode.SMART_DOH,
                environment = SteamNetworkEnvironment.RESTRICTED,
            ),
        )
    }

    @Test
    fun `unknown environment conservatively keeps anti blocking until classification completes`() {
        assertTrue(
            shouldApplySteamAccess(
                configured = true,
                mode = SteamAccessMode.SMART_DOH,
                environment = SteamNetworkEnvironment.UNKNOWN,
            ),
        )
    }

    @Test
    fun `manual hosts mode is not bypassed by open internet probe`() {
        assertTrue(
            shouldApplySteamAccess(
                configured = true,
                mode = SteamAccessMode.HOSTS,
                environment = SteamNetworkEnvironment.OPEN_INTERNET,
            ),
        )
    }

    @Test
    fun `disabled setting always stays direct`() {
        SteamNetworkEnvironment.entries.forEach { environment ->
            assertFalse(shouldApplySteamAccess(false, SteamAccessMode.SMART_DOH, environment))
        }
    }
}
