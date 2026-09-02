package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotDownloaderCapability
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32

class KotlinDepotDownloaderTest {
    @Test
    fun `verify chunk accepts payloads matching the manifest checksum`() =
        runTest {
            val downloader = KotlinDepotDownloader()
            val payload = "123456789".toByteArray(Charsets.US_ASCII)

            assertTrue(downloader.verifyChunk(payload, SteamAdler32.calculate(payload)))
        }

    @Test
    fun `verify chunk rejects corrupted payloads`() =
        runTest {
            val downloader = KotlinDepotDownloader()
            val payload = "123456789".toByteArray(Charsets.US_ASCII)
            val corrupted =
                payload.copyOf().also { bytes -> bytes[0] = (bytes[0] + 1).toByte() }

            assertFalse(downloader.verifyChunk(payload, SteamAdler32.calculate(payload) + 1))
            assertFalse(downloader.verifyChunk(corrupted, SteamAdler32.calculate(payload)))
        }

    @Test
    fun `decode chunk fails closed on invalid depot payloads`() =
        runTest {
            val downloader = KotlinDepotDownloader()
            val spec =
                DepotChunkSpec(
                    checksum = 1,
                    offset = 0L,
                    compressedLength = 64,
                    uncompressedLength = 128,
                )

            val result = downloader.decodeChunk(spec, ByteArray(64), ByteArray(32))

            assertTrue(result.isFailure)
        }

    @Test
    fun `capabilities exclude depot phases the kotlin engine does not own`() {
        val capabilities = KotlinDepotDownloader().capabilities

        assertTrue(DepotDownloaderCapability.CHUNK_VERIFICATION in capabilities)
        assertTrue(DepotDownloaderCapability.CHUNK_DECODE in capabilities)
        assertFalse(DepotDownloaderCapability.CHUNK_DOWNLOAD in capabilities)
        assertFalse(DepotDownloaderCapability.MANIFEST_RESOLUTION in capabilities)
        assertEquals(2, capabilities.size)
    }
}
