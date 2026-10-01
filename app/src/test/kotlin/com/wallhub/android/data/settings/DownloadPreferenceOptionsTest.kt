package com.wallhub.android.data.settings

import com.wallhub.android.core.model.DEFAULT_MAX_CONCURRENT_DOWNLOADS
import com.wallhub.android.core.model.MAX_CONCURRENT_DOWNLOAD_OPTIONS
import com.wallhub.android.core.model.MAX_MAX_CONCURRENT_DOWNLOADS
import com.wallhub.android.core.model.MIN_MAX_CONCURRENT_DOWNLOADS
import com.wallhub.android.core.model.safeMaxConcurrentDownloads
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPreferenceOptionsTest {
    @Test
    fun `download limit offers every supported global concurrency`() {
        assertEquals((1..8).toList(), MAX_CONCURRENT_DOWNLOAD_OPTIONS)
        assertEquals(1, MIN_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(8, MAX_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(1, DEFAULT_MAX_CONCURRENT_DOWNLOADS)
    }

    @Test
    fun `stored download limit preserves every offered value and clamps invalid values`() {
        MAX_CONCURRENT_DOWNLOAD_OPTIONS.forEach { value ->
            assertEquals(value, safeMaxConcurrentDownloads(value))
        }
        assertEquals(1, safeMaxConcurrentDownloads(0))
        assertEquals(8, safeMaxConcurrentDownloads(9))
    }
}
