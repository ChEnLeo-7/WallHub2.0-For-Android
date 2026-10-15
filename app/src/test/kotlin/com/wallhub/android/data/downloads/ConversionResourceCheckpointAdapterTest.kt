package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionCheckpointRequest
import com.wallhub.prototype.mpkg.ResourceStrategy
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConversionResourceCheckpointAdapterTest {
    @Test
    fun `source range hash and strategy mismatches are not reused`() = withAdapter { root, adapter ->
        val product = root.resolve("product.bin").apply { writeText("verified") }
        val request = request()
        adapter.record(request, product)

        assertNotNull(adapter.reuse(request))
        assertNull(adapter.reuse(request.copy(sourceOffset = 8)))
        assertNull(adapter.reuse(request.copy(sourceLength = 9)))
        assertNull(adapter.reuse(request.copy(sourceSha256 = sha("changed"))))
        assertNull(adapter.reuse(request.copy(strategy = ResourceStrategy.CONVERT_TO_RGBA8888_LZ4)))
    }

    @Test
    fun `plan preset audio source and converter identity mismatches invalidate the attempt`() {
        val root = Files.createTempDirectory("checkpoint-adapter-identity").toFile()
        try {
            val store = ConversionCheckpointStore(root)
            val original = identity()
            val product = root.resolve("product.bin").apply { writeText("verified") }
            ConversionResourceCheckpointAdapter(original, store).record(request(), product)
            listOf(
                original.copy(planSha256 = sha("other-plan")),
                original.copy(presetId = "compatibility"),
                original.copy(preserveAudio = false),
                original.copy(sourceIdentity = "pkg:9:other"),
                original.copy(converterVersion = "v3"),
            ).forEach { changed ->
                val changedAdapter = ConversionResourceCheckpointAdapter(changed, store)
                assertNull(changedAdapter.reuse(request()))
                changedAdapter.record(request(), product)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `damaged checkpoint product is not reused`() = withAdapter { root, adapter ->
        val stored = adapter.record(request(), root.resolve("product.bin").apply { writeText("verified") })
        assertNotNull(adapter.reuse(request()))
        stored.writeText("damaged")
        assertNull(adapter.reuse(request()))
        val replacement = adapter.record(request(), root.resolve("replacement.bin").apply { writeText("redone") })
        assertEquals("redone", replacement.readText())
    }

    private fun withAdapter(block: (File, ConversionResourceCheckpointAdapter) -> Unit) {
        val root = Files.createTempDirectory("checkpoint-adapter").toFile()
        try {
            block(root, ConversionResourceCheckpointAdapter(identity(), ConversionCheckpointStore(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun identity() =
        ConversionCheckpointIdentity("task", 4, sha("plan"), "pkg:8:index", "balanced", true, "v2")

    private fun request() =
        ConversionCheckpointRequest("materials/a.tex", 7, 8, sha("source"), ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4)

    private fun sha(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
