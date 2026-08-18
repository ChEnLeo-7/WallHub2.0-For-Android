package com.wallhub.android.data.downloads

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCdnHttpsTest {
    @Test
    fun httpCdnUrlIsUpgradedToHttps() {
        val upgraded = "http://cdn.example.test/content".toHttpUrl().upgradeSteamCdnUrl()

        assertEquals("https", upgraded.scheme)
        assertEquals(443, upgraded.port)
        assertEquals("/content", upgraded.encodedPath)
    }

    @Test
    fun existingHttpsCdnUrlIsUnchanged() {
        val secure = "https://cdn.example.test:8443/content".toHttpUrl()

        assertEquals(secure, secure.upgradeSteamCdnUrl())
    }
}
