package com.wallhub.android.data.downloads

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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
            previewUrl = "https://cdn.example/preview.jpg",
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
            "filename":"clip.mp4","preview_url":"https://cdn.example/preview.jpg",
            "hcontent_file":"999","tags":[{"tag":"video"}]}]}}
            """.trimIndent()
        val target = SteamWorkshopContentApi(OkHttpClient.Builder()).parseTarget(body, 42L)
        assertEquals("https://cdn.example/clip.mp4", target.fileUrl)
        assertEquals("clip.mp4", target.rawFileName)
        assertEquals("https://cdn.example/preview.jpg", target.previewUrl)
        assertEquals(999L, target.contentManifestId)
        assertEquals("video", target.contentTypeHint)
        assertEquals(WorkshopDownloadSource.DIRECT_FILE, workshopDownloadSource(target))
    }

    @Test
    fun hcontentFileAboveLongMaxIsRecoveredFromRawBody() {
        val body =
            """
            {"response":{"publishedfiledetails":[{"result":1,"consumer_app_id":431960,
             "title":"Clip","file_size":123,"file_url":"",
             "hcontent_file":16566827351488351196,"tags":[{"tag":"video"}]}]}}
            """.trimIndent()
        val parsed = SteamWorkshopContentApi(OkHttpClient.Builder()).parseTarget(body, 42L)
        assertEquals(16566827351488351196UL.toLong(), parsed.contentManifestId)
        assertEquals("16566827351488351196", parsed.contentManifestId.toULong().toString())
        assertEquals(WorkshopDownloadSource.STEAM_PIPE, workshopDownloadSource(parsed))
    }

    @Test
    fun directVideoFileNamesAreSanitizedForStaging() {
        assertEquals("clip.mp4", directVideoFileName(target("https://cdn.example/video.mp4"), "https://cdn.example/video.mp4"))
        assertEquals(
            "wallpaper.mp4",
            directVideoFileName(
                target("https://cdn.example/video.mp4").copy(rawFileName = "../escape.mp4"),
                "https://cdn.example/video.mp4",
            ),
        )
        assertEquals(
            "my_bad_name.mp4",
            directVideoFileName(
                target("https://cdn.example/video.mp4").copy(rawFileName = "my:bad?name.mp4"),
                "https://cdn.example/video.mp4",
            ),
        )
        assertEquals("preview.webp", directPreviewFileName("https://cdn.example/image.webp?size=large"))
        assertEquals("preview.jpg", directPreviewFileName("https://cdn.example/image"))
    }

    @Test
    fun directContentRangeRequiresACompleteValidRange() {
        assertEquals(DirectContentRange(start = 10L, total = 100L), parseDirectContentRange("bytes 10-99/100"))
        assertNull(parseDirectContentRange("bytes 10-9/100"))
        assertNull(parseDirectContentRange("bytes 10-100/100"))
        assertNull(parseDirectContentRange("bytes */100"))
        assertNull(parseDirectContentRange(null))
    }

    @Test
    fun directPreviewValidationUsesFileSignature() {
        val directory = createTempDirectory("wallhub-preview-").toFile()
        try {
            val disguisedPng = File(directory, "preview.jpg")
            disguisedPng.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            assertEquals("png", validatedPreviewExtension(disguisedPng))

            disguisedPng.writeText("not an image")
            assertNull(validatedPreviewExtension(disguisedPng))
        } finally {
            directory.deleteRecursively()
        }
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
