package com.wallhub.android.core.designsystem

import com.wallhub.android.R
import com.wallhub.android.core.model.MpkgConversionPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class MpkgConversionOptionsTest {
    @Test
    fun `shared options expose every conversion tier in product order`() {
        assertEquals(
            listOf(
                MpkgConversionPreset.BALANCED,
                MpkgConversionPreset.COMPATIBILITY,
                MpkgConversionPreset.HIGH_QUALITY,
                MpkgConversionPreset.EXTREME_QUALITY,
            ),
            mpkgConversionOptions().map { it.preset },
        )
    }

    @Test
    fun `shared options keep the download labels and policy descriptions`() {
        assertEquals(
            listOf(
                Triple(
                    R.string.mpkg_preset_standard,
                    R.string.mpkg_preset_compact_balanced,
                    R.string.mpkg_preset_standard_preview,
                ),
                Triple(
                    R.string.mpkg_preset_compatibility,
                    R.string.mpkg_preset_compact_compatibility,
                    R.string.mpkg_preset_compatibility_preview,
                ),
                Triple(
                    R.string.mpkg_preset_high_quality,
                    R.string.mpkg_preset_compact_high_quality,
                    R.string.mpkg_preset_high_quality_preview,
                ),
                Triple(
                    R.string.mpkg_preset_extreme_quality,
                    R.string.mpkg_preset_compact_extreme_quality,
                    R.string.mpkg_preset_extreme_quality_preview,
                ),
            ),
            mpkgConversionOptions().map { Triple(it.labelRes, it.compactDescriptionRes, it.policyPreviewRes) },
        )
    }
}
