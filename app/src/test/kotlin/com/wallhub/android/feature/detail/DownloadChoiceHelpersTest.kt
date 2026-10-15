package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadChoiceHelpersTest {
    @Test
    fun `scene with MPKG exposes conversion-only controls`() {
        assertTrue(shouldShowMpkgPreset(WorkshopType.SCENE, listOf(ExportFormat.MPKG, ExportFormat.ZIP)))
    }

    @Test
    fun `video web and scene without MPKG hide conversion-only controls`() {
        assertFalse(shouldShowMpkgPreset(WorkshopType.VIDEO, listOf(ExportFormat.MPKG)))
        assertFalse(shouldShowMpkgPreset(WorkshopType.WEB, listOf(ExportFormat.ZIP)))
        assertFalse(shouldShowMpkgPreset(WorkshopType.SCENE, listOf(ExportFormat.ZIP)))
    }
}
