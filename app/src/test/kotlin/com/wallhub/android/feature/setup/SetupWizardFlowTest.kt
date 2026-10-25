package com.wallhub.android.feature.setup

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupWizardFlowTest {
    @Test
    fun `network settings advance through warmup and connectivity before storage`() {
        assertEquals(WallHubSetupPage.Warmup, nextSetupPage(WallHubSetupPage.Network))
        assertEquals(WallHubSetupPage.Connectivity, nextSetupPage(WallHubSetupPage.Warmup))
        assertEquals(WallHubSetupPage.Storage, nextSetupPage(WallHubSetupPage.Connectivity))
    }

    @Test
    fun `transient network pages return to editable network settings`() {
        assertEquals(WallHubSetupPage.Network, previousSetupPage(WallHubSetupPage.Warmup))
        assertEquals(WallHubSetupPage.Network, previousSetupPage(WallHubSetupPage.Connectivity))
    }

    @Test
    fun `process restoration never revives a transient network page without its job`() {
        assertEquals(WallHubSetupPage.Network, durableSetupPage(WallHubSetupPage.Warmup))
        assertEquals(WallHubSetupPage.Network, durableSetupPage(WallHubSetupPage.Connectivity))
        assertEquals(WallHubSetupPage.Storage, durableSetupPage(WallHubSetupPage.Storage))
    }
}
