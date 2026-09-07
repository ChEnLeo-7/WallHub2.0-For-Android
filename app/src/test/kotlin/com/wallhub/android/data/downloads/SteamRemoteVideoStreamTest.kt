package com.wallhub.android.data.downloads

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamRemoteVideoStreamTest {
    private fun target(fileUrl: String) =
        WorkshopContentTarget(
            publishedFileId = 42L,
            title = "Clip",
            appId = 431960,
            contentManifestId = 0L,
            expectedSize = 10L,
            contentTypeHint = "video",
            fileUrl = fileUrl,
            rawFileName = "clip.mp4",
        )

    @Test
    fun remoteVideoUrlRequiresStreamableVideoExtension() {
        assertEquals(
            "https://cdn.example/clip.mp4?orig=1",
            remoteVideoUrlOrNull(target("https://cdn.example/clip.mp4?orig=1")),
        )
        assertEquals("https://cdn.example/clip.webm", remoteVideoUrlOrNull(target("https://cdn.example/clip.webm")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.example/preview.png")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.example/clip.EXE")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.example/clip")))
        assertEquals(null, remoteVideoUrlOrNull(target("")))
    }

    @Test
    fun contentRangeTotalParsesDeclaredSize() {
        assertEquals(12345L, parseContentRangeTotal("bytes 0-1/12345"))
        assertEquals(90L, parseContentRangeTotal("bytes 5-9/90"))
        assertEquals(null, parseContentRangeTotal("bytes 0-1/*"))
        assertEquals(null, parseContentRangeTotal("bytes 0-1/"))
        assertEquals(null, parseContentRangeTotal(null))
    }

    @Test
    fun parseTargetAcceptsDirectFileUrlWithoutManifest() {
        val body =
            """
            {"response":{"publishedfiledetails":[{"result":1,"consumer_app_id":431960,
            "title":"Clip","file_size":123,"file_url":"https://cdn.example/clip.mp4",
            "filename":"clip.mp4","tags":[{"tag":"video"}]}]}}
            """.trimIndent()
        val target = SteamWorkshopContentApi(OkHttpClient.Builder()).parseTarget(body, 42L)
        assertEquals("https://cdn.example/clip.mp4", target.fileUrl)
        assertEquals("clip.mp4", target.rawFileName)
        assertEquals(0L, target.contentManifestId)
        assertEquals("video", target.contentTypeHint)
    }

    @Test
    fun parseTargetRejectsItemsWithoutManifestAndFileUrl() {
        val body =
            """
            {"response":{"publishedfiledetails":[{"result":1,"consumer_app_id":431960,
            "title":"Clip","file_size":123,"tags":[{"tag":"video"}]}]}}
            """.trimIndent()
        assertThrows(IllegalStateException::class.java) {
            SteamWorkshopContentApi(OkHttpClient.Builder()).parseTarget(body, 42L)
        }
    }
}
