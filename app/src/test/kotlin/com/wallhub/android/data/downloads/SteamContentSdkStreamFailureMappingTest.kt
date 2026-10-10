package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopVideoStreamFailure
import com.wallhub.android.core.model.WorkshopVideoStreamFailureException
import io.steamcontent.sdk.CdnProvider
import io.steamcontent.sdk.StreamReadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamContentSdkStreamFailureMappingTest {
    @Test
    fun `session generation loss maps to app-owned recoverable failure and preserves raw SDK fields`() {
        val mapped =
            StreamReadFailure.SESSION_GENERATION_LOST.toAppStreamReadException(
                generation = 17L,
                cdnProvider = CdnProvider.VALVE,
            )

        assertTrue(mapped is WorkshopVideoStreamFailureException)
        mapped as WorkshopVideoStreamFailureException
        assertEquals(WorkshopVideoStreamFailure.SESSION_INVALIDATED, mapped.failure)
        assertTrue(mapped.cause?.message.orEmpty().contains("SESSION_GENERATION_LOST"))
        assertTrue(mapped.cause?.message.orEmpty().contains("generation=17"))
        assertTrue(mapped.cause?.message.orEmpty().contains("provider=VALVE"))
    }

    @Test
    fun `candidate exhaustion maps to the route replacement failure`() {
        val mapped =
            StreamReadFailure.CDN_CANDIDATES_EXHAUSTED.toAppStreamReadException(
                generation = 3L,
                cdnProvider = CdnProvider.STEAMCHINA,
            ) as WorkshopVideoStreamFailureException

        assertEquals(WorkshopVideoStreamFailure.CDN_ROUTE_EXHAUSTED, mapped.failure)
    }

    @Test
    fun `ordinary typed transport and content failures do not become automatic recovery failures`() {
        val transport =
            StreamReadFailure.TRANSPORT_TLS.toAppStreamReadException(
                generation = 2L,
                cdnProvider = CdnProvider.FASTLY,
            )
        val corrupt =
            StreamReadFailure.CONTENT_CORRUPTED.toAppStreamReadException(
                generation = 2L,
                cdnProvider = CdnProvider.FASTLY,
            )

        assertFalse(transport is WorkshopVideoStreamFailureException)
        assertFalse(corrupt is WorkshopVideoStreamFailureException)
        assertTrue(transport.message.orEmpty().contains("TRANSPORT_TLS"))
        assertTrue(corrupt.message.orEmpty().contains("CONTENT_CORRUPTED"))
    }
}
