package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.MpkgConversionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalWallpaperConversionMetadataTest {
    @Test
    fun `exact WallHub output URI exposes its persisted MPKG conversion preset`() {
        assertEquals(
            MpkgConversionPreset.EXTREME_QUALITY,
            wallHubConversionPresetFor(
                contentUri = OUTPUT_URI,
                tasks = listOf(task(outputUri = OUTPUT_URI, preset = "extreme_quality")),
            ),
        )
    }

    @Test
    fun `published report tier wins over a newer failed reconversion request`() {
        assertEquals(
            MpkgConversionPreset.BALANCED,
            wallHubConversionPresetFor(
                contentUri = OUTPUT_URI,
                tasks =
                    listOf(
                        task(
                            outputUri = OUTPUT_URI,
                            preset = "extreme_quality",
                            status = "FAILED",
                            successfulReport = """{"version":2,"presetId":"balanced"}""",
                        ),
                    ),
            ),
        )
    }

    @Test
    fun `unrecognized preset on an exact task remains unknown rather than inventing a tier`() {
        assertNull(
            wallHubConversionPresetFor(
                contentUri = OUTPUT_URI,
                tasks = listOf(task(outputUri = OUTPUT_URI, preset = "future_tier")),
            ),
        )
    }

    @Test
    fun `matching Workshop ID without the exact output URI remains external and unknown`() {
        assertNull(
            wallHubConversionPresetFor(
                contentUri = "content://external/copied.mpkg",
                tasks = listOf(task(outputUri = OUTPUT_URI, preset = "balanced")),
            ),
        )
    }

    private fun task(
        outputUri: String,
        preset: String,
        status: String = "COMPLETED",
        successfulReport: String? = null,
    ): FormalTaskRecordEntity =
        FormalTaskRecordEntity(
            taskId = "task-1",
            workshopId = 2902406982L,
            title = "Wallpaper",
            type = "SCENE",
            status = status,
            outputUri = outputUri,
            conversionPreset = preset,
            successfulConversionReportJson = successfulReport,
            createdAt = 1L,
            updatedAt = 2L,
        )

    private companion object {
        const val OUTPUT_URI = "content://downloads/wallhub-output.mpkg"
    }
}
