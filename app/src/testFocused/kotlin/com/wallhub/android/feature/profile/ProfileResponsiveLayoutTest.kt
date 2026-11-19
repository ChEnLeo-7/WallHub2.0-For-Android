package com.wallhub.android.feature.profile

import com.wallhub.android.core.designsystem.WallHubWindowLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileResponsiveLayoutTest {
    @Test
    fun `only expanded windows use profile two pane layout`() {
        assertFalse(useExpandedProfileLayout(WallHubWindowLayout.COMPACT))
        assertFalse(useExpandedProfileLayout(WallHubWindowLayout.MEDIUM))
        assertTrue(useExpandedProfileLayout(WallHubWindowLayout.EXPANDED))
    }
}
