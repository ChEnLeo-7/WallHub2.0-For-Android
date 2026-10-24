package com.wallhub.android.feature.setup

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupWizardFlowTest {
    @Test
    fun `network settings advance to connectivity before storage`() {
        assertEquals(WallHubSetupPage.Connectivity, nextSetupPage(WallHubSetupPage.Network))
        assertEquals(WallHubSetupPage.Storage, nextSetupPage(WallHubSetupPage.Connectivity))
    }

    @Test
    fun `connectivity back returns to network settings`() {
        assertEquals(WallHubSetupPage.Network, previousSetupPage(WallHubSetupPage.Connectivity))
    }
}
