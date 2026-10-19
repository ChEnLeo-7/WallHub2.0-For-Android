package com.wallhub.android.data.downloads

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
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
            "https://cdn.steamusercontent.com/clip.mp4?orig=1",
            remoteVideoUrlOrNull(target("https://cdn.steamusercontent.com/clip.mp4?orig=1")),
        )
        assertEquals("https://cdn.steamusercontent.com/clip.webm", remoteVideoUrlOrNull(target("https://cdn.steamusercontent.com/clip.webm")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.example/preview.png")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.steamusercontent.com/clip.EXE")))
        assertEquals(null, remoteVideoUrlOrNull(target("https://cdn.steamusercontent.com/clip")))
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
             "title":"Clip","file_size":123,"file_url":"https://cdn.steamusercontent.com/clip.mp4",
            "filename":"clip.mp4","preview_url":"https://cdn.example/preview.jpg",
            "hcontent_file":"999","tags":[{"tag":"video"}]}]}}
            """.trimIndent()
        val target = SteamWorkshopContentApi(OkHttpClient.Builder()).parseTarget(body, 42L)
        assertEquals("https://cdn.steamusercontent.com/clip.mp4", target.fileUrl)
        assertEquals("clip.mp4", target.rawFileName)
        assertEquals("https://cdn.example/preview.jpg", target.previewUrl)
        assertEquals(999L, target.contentManifestId)
        assertEquals("video", target.contentTypeHint)
        assertEquals(WorkshopDelivery.DirectFile::class, workshopDelivery(target)::class)
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
        assertEquals(WorkshopDelivery.DepotContent::class, workshopDelivery(parsed)::class)
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

    @Test
    fun contentTargetCacheCoalescesConcurrentRequestsAndExpires() = runBlocking {
        var now = 1_000L
        val gateway = CountingWorkshopContentGateway(target(""))
        val client = SteamWorkshopContentClient(gateway) { now }

        val first = List(8) { async { client.fetchContentTarget(42L, "") } }.awaitAll()
        assertEquals(1, gateway.fetches.get())
        assertEquals(first.first(), first.last())

        now += 5 * 60 * 1_000L + 1
        client.fetchContentTarget(42L, "")
        assertEquals(2, gateway.fetches.get())

        client.invalidateContentTarget(42L)
        client.fetchContentTarget(42L, "")
        assertEquals(3, gateway.fetches.get())
    }

}

private class CountingWorkshopContentGateway(
    private val target: WorkshopContentTarget,
) : WorkshopContentGateway {
    val fetches = AtomicInteger()

    override suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget {
        fetches.incrementAndGet()
        delay(20)
        return target
    }

    override suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult = error("Not used")

}
