package com.wallhub.android.data.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHostRunResultTest {
    @Test
    fun `retry keeps the durable command pending for another wake`() {
        assertFalse(DownloadHostRunResult.RETRY.settlesCommand)
    }

    @Test
    fun `settled runner outcomes may publish command receipts`() {
        assertTrue(DownloadHostRunResult.SUCCESS.settlesCommand)
        assertTrue(DownloadHostRunResult.FAILURE.settlesCommand)
    }
}
