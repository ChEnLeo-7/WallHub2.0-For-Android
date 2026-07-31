package com.wallhub.android.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadProxyUrlTest {
    @Test
    fun `supported proxy URLs require a scheme and host`() {
        assertTrue(isSupportedDownloadProxyUrl("http://127.0.0.1:8080"))
        assertTrue(isSupportedDownloadProxyUrl("socks5://proxy.example:1080"))
        assertFalse(isSupportedDownloadProxyUrl("localhost:8080"))
        assertFalse(isSupportedDownloadProxyUrl("ftp://proxy.example:21"))
        assertFalse(isSupportedDownloadProxyUrl("https://proxy.example:99999"))
    }
}
