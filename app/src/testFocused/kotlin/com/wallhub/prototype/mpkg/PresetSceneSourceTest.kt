package com.wallhub.prototype.mpkg

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetSceneSourceTest {
    @Test
    fun `current scene package resolves through preset dependency`() {
        val root = Files.createTempDirectory("wallhub-preset-source").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"3122339805","preset":{}}""")
            val dependency = root.resolve(".wallhub-dependencies/3122339805").apply { mkdirs() }
            dependency.resolve("project.json").writeText("""{"file":"actual.json","type":"scene"}""")
            val expected = dependency.resolve("actual.pkg").apply { writeBytes(byteArrayOf(1)) }

            assertEquals(expected.canonicalFile, WorkshopConverter.resolveCurrentScenePackage(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
