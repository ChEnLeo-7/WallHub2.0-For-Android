package com.wallhub.prototype.mpkg

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StreamingSceneJsonFailureTest {
    @Test
    fun `truncated scene json is reported as package format failure`() {
        val root = Files.createTempDirectory("wallhub-scene-json").toFile()
        try {
            val source = root.resolve("scene.pkg").apply { writeText("{") }
            val output = root.resolve("scene-rewritten.json")

            assertFailsWith<PkgFormatException> {
                StreamingSceneJson.rewriteTextureReduction(
                    source = source,
                    entry = IndexedPkgEntry("scene.json", 0L, source.length()),
                    textureReduction = 2,
                    output = output,
                )
            }
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
