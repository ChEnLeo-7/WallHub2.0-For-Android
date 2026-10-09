package com.wallhub.android.feature.detail

import androidx.media3.common.PlaybackException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineVideoRecoveryPolicyTest {
    @Test
    fun `native session replacement is the only automatic stream reopen case`() {
        val invalidHandle =
            PlaybackException(
                "source",
                IOException("Steam video chunk read failed", IllegalStateException("invalid stream handle")),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val closedStream =
            PlaybackException(
                "source",
                IOException("stream is closed"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val staleSessionGeneration =
            PlaybackException(
                "source",
                IOException(
                    "Steam video chunk read failed",
                    IllegalStateException("content resolution failed at session_unavailable"),
                ),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val transportFailure =
            PlaybackException(
                "source",
                IOException("CDN candidate set exhausted"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        val corruptContent =
            PlaybackException(
                "source",
                IOException("chunk checksum mismatch"),
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )

        assertTrue(invalidHandle.isRecoverableSessionReplacement())
        assertTrue(closedStream.isRecoverableSessionReplacement())
        assertTrue(staleSessionGeneration.isRecoverableSessionReplacement())
        assertFalse(transportFailure.isRecoverableSessionReplacement())
        assertFalse(corruptContent.isRecoverableSessionReplacement())
    }
}
