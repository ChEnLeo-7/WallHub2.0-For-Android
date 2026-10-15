package com.wallhub.android.data.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionCheckpointStoreTest {
    @Test
    fun `resource is published by temporary file and atomic move with complete metadata`() = withStore { root, _ ->
        val moves = mutableListOf<Pair<File, File>>()
        val recording = ConversionCheckpointStore(root) { source, destination ->
            assertTrue(source.name.endsWith(".tmp"))
            assertTrue(source.isFile)
            moves += source to destination
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val resource = recording.write(identity(), "materials/wall.tex", 17, 3, sha("src"), "COPY") { it.write("out".toByteArray()) }

        assertEquals("materials/wall.tex", resource.normalizedPath)
        assertEquals(17L, resource.sourceOffset)
        assertEquals(3L, resource.sourceLength)
        assertEquals(sha("src"), resource.sourceSha256)
        assertEquals(3L, resource.outputSize)
        assertEquals(sha("out"), resource.outputSha256)
        assertEquals("COPY", resource.strategy)
        assertEquals("out", resource.file.readText())
        assertTrue(moves.size >= 3) // identity, artifact, metadata
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `identity mismatches are cache misses`() = withStore { _, store ->
        val original = identity()
        store.put(original)
        assertNull(store.read(original.copy(attempt = 2), PATH))
        assertNull(store.read(original.copy(planSha256 = sha("other-plan")), PATH))
        assertNull(store.read(original.copy(sourceIdentity = "other-source"), PATH))
        assertNull(store.read(original.copy(presetId = "compatibility"), PATH))
        assertNull(store.read(original.copy(preserveAudio = false), PATH))
        assertNull(store.read(original.copy(converterVersion = "v2"), PATH))
        assertNotNull(store.read(original, PATH))
    }

    @Test
    fun `changed size hash or missing artifact is a cache miss`() = withStore { _, store ->
        val identity = identity()
        val first = store.put(identity)
        first.file.appendText("tamper")
        assertNull(store.read(identity, PATH))

        store.put(identity).file.writeText("xxxxxx")
        assertNull(store.read(identity, PATH))

        store.put(identity).file.delete()
        assertNull(store.read(identity, PATH))
    }

    @Test
    fun `delete attempt removes only that attempt`() = withStore { _, store ->
        val first = identity(attempt = 1)
        val second = identity(attempt = 2)
        store.put(first)
        store.put(second)

        assertTrue(store.deleteAttempt(first))
        assertNull(store.read(first, PATH))
        assertNotNull(store.read(second, PATH))
    }

    @Test
    fun `temporary cleanup removes orphan files recursively`() = withStore { root, store ->
        val orphan = File(root, "conversion-checkpoints/task/9/.orphan.tmp")
        orphan.parentFile.mkdirs()
        orphan.writeText("partial")
        val retained = File(orphan.parentFile, "retained.bin").apply { writeText("ok") }

        assertEquals(1, store.cleanupTemporaryFiles())
        assertFalse(orphan.exists())
        assertTrue(retained.exists())
    }

    @Test
    fun `unsafe task IDs are mapped and unsafe resource paths are rejected`() = withStore { root, store ->
        val identity = identity(taskId = "../../task with spaces/雪")
        store.put(identity)
        val attempt = root.walkTopDown().first { it.isDirectory && it.name == "1" }
        assertTrue(attempt.canonicalPath.startsWith(File(root, "conversion-checkpoints").canonicalPath + File.separator))
        assertTrue(runCatching { store.write(identity, "../escape", 0, 1, sha("src"), "COPY") {} }.isFailure)
        assertNull(store.read(identity, "../escape"))
    }

    private fun ConversionCheckpointStore.put(identity: ConversionCheckpointIdentity) =
        write(identity, PATH, 10, 6, sha("source"), "COPY") { it.write("output".toByteArray()) }

    private fun identity(taskId: String = "task-1", attempt: Long = 1) =
        ConversionCheckpointIdentity(taskId, attempt, sha("plan"), "pkg:12:index", "balanced", true, "v1")

    private fun sha(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun withStore(block: (File, ConversionCheckpointStore) -> Unit) {
        val root = Files.createTempDirectory("checkpoint-test").toFile()
        try {
            block(root, ConversionCheckpointStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PATH = "materials/wall.tex"
    }
}
