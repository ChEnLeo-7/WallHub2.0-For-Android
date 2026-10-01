package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ByteArrayPayload
import com.wallhub.prototype.mpkg.MAX_PRESET_DEPENDENCY_DEPTH
import com.wallhub.prototype.mpkg.MpkgInputEntry
import com.wallhub.prototype.mpkg.MpkgWriter
import com.wallhub.prototype.mpkg.PresetDependencyResolver
import com.wallhub.prototype.mpkg.SCENE_MPKG_MAGIC
import com.wallhub.prototype.mpkg.ShaderCompatibility
import com.wallhub.prototype.mpkg.WorkshopConverter
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ScenePackageResolutionTest {
    @Test
    fun projectJsonNameResolvesMatchingScenePackage() {
        val root = Files.createTempDirectory("wallhub-scene-package").toFile()
        try {
            val expected = root.resolve("gifscene.pkg").apply { writeBytes(byteArrayOf(1)) }

            assertEquals(expected.canonicalFile, WorkshopConverter.resolveScenePackage(root, "gifscene.json"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDeclarationUsesLegacyScenePackage() {
        val root = Files.createTempDirectory("wallhub-legacy-package").toFile()
        try {
            val expected = root.resolve("scene.pkg").apply { writeBytes(byteArrayOf(1)) }

            assertEquals(expected.canonicalFile, WorkshopConverter.resolveScenePackage(root, null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectPackageCannotEscapeWorkshopDirectory() {
        val root = Files.createTempDirectory("wallhub-scene-root").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                WorkshopConverter.resolveScenePackage(root, "../outside.json")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun presetResolvesDownloadedSceneDependency() {
        val root = Files.createTempDirectory("wallhub-scene-preset").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"3122339805","preset":{}}""")
            val dependency = root.resolve(".wallhub-dependencies/3122339805").apply { mkdirs() }
            dependency.resolve("project.json").writeText("""{"file":"scene.json","type":"scene"}""")

            assertEquals(dependency.canonicalFile, WorkshopConverter.resolvePresetDependency(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun presetRequiresDownloadedDependency() {
        val root = Files.createTempDirectory("wallhub-missing-preset").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"3122339805","preset":{}}""")

            assertThrows(IllegalArgumentException::class.java) {
                WorkshopConverter.resolvePresetDependency(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completePresetDownloadRequiresItsDependencyDirectory() {
        val root = Files.createTempDirectory("wallhub-preset-retry").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"3122339805","preset":{}}""")
            assertFalse(hasCompletePresetDependency(root))

            val dependency = root.resolve(".wallhub-dependencies/3122339805").apply { mkdirs() }
            dependency.resolve("project.json").writeText("""{"file":"scene.json","type":"scene"}""")
            assertTrue(hasCompletePresetDependency(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completePresetDownloadRejectsDependenciesBeyondSupportedDepth() {
        val root = Files.createTempDirectory("wallhub-preset-depth").toFile()
        try {
            var current = root
            repeat(5) { index ->
                val dependencyId = index + 1L
                current.resolve("project.json").writeText(
                    """{"dependency":"$dependencyId","preset":{}}""",
                )
                current = current.resolve(".wallhub-dependencies/$dependencyId").apply { mkdirs() }
            }
            current.resolve("project.json").writeText("""{"file":"scene.json","type":"scene"}""")

            assertFalse(hasCompletePresetDependency(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryPreflightAndConverterAgreeOnValidPresetChain() {
        val root = Files.createTempDirectory("wallhub-preset-equivalence").toFile()
        try {
            val scene = createPresetChain(root, MAX_PRESET_DEPENDENCY_DEPTH)
            scene.resolve("project.json").writeText("""{"file":"scene.json","type":"scene"}""")

            assertTrue(hasCompletePresetDependency(root))
            assertEquals(scene.canonicalFile, WorkshopConverter.resolvePresetDependency(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryPreflightAndConverterAgreeOnMalformedPresetDependency() {
        val root = Files.createTempDirectory("wallhub-malformed-preset").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"not-an-id","preset":{}}""")

            assertFalse(hasCompletePresetDependency(root))
            assertThrows(IllegalStateException::class.java) {
                WorkshopConverter.resolvePresetDependency(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryPreflightAndConverterAgreeOnPresetCycle() {
        val root = Files.createTempDirectory("wallhub-cyclic-preset").toFile()
        try {
            root.resolve("project.json").writeText("""{"dependency":"1","preset":{}}""")
            val dependency = root.resolve(".wallhub-dependencies/1").apply { mkdirs() }
            dependency.resolve("project.json").writeText("""{"dependency":"2","preset":{}}""")
            val loop = dependency.resolve(".wallhub-dependencies/2")
            loop.parentFile.mkdirs()
            Files.createSymbolicLink(loop.toPath(), root.toPath())

            assertFalse(hasCompletePresetDependency(root))
            assertThrows(IllegalStateException::class.java) {
                WorkshopConverter.resolvePresetDependency(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolverRejectsPresetObjectWithMissingDependency() {
        val root = Files.createTempDirectory("wallhub-invalid-preset").toFile()
        try {
            root.resolve("project.json").writeText("""{"preset":{}}""")

            val failure = PresetDependencyResolver.resolve(root).exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("Workshop preset has no valid dependency ID", failure?.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedPresetDependenciesAreStoredUnderTheirCurrentLayer() {
        val root = Files.createTempDirectory("wallhub-nested-preset").toFile()
        try {
            val first = resolvePresetDependencyDirectory(root, 100L)
            val second = resolvePresetDependencyDirectory(first, 200L)

            assertEquals(
                first.resolve(".wallhub-dependencies/200").canonicalFile,
                second,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data object RewriteCancelled : RuntimeException()

    private fun createPresetChain(
        root: java.io.File,
        dependencyCount: Int,
    ): java.io.File {
        var current = root
        repeat(dependencyCount) { index ->
            val dependencyId = index + 1L
            current.resolve("project.json").writeText(
                """{"dependency":"$dependencyId","preset":{}}""",
            )
            current = PresetDependencyResolver.dependencyDirectory(current, dependencyId).apply { mkdirs() }
        }
        return current
    }

    @Test
    fun mpkgWriterRejectsEntryCountBeyondMobileIndexBudgetBeforeWritingOutput() {
        val root = Files.createTempDirectory("wallhub-mpkg-entry-budget").toFile()
        try {
            val output = root.resolve("too-many.mpkg")
            val payload = ByteArrayPayload(byteArrayOf())
            val entries = List(MpkgWriter.MAX_ENTRY_COUNT + 1) { index -> MpkgInputEntry("$index", payload) }

            val failure = runCatching { MpkgWriter.write(entries, output, SCENE_MPKG_MAGIC) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("entry count"))
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mpkgWriterRejectsOversizedIndexPathBeforeWritingOutput() {
        val root = Files.createTempDirectory("wallhub-mpkg-index-budget").toFile()
        try {
            val output = root.resolve("large-index.mpkg")
            val oversizedPath = "a".repeat(MpkgWriter.MAX_PATH_BYTES + 1)

            val failure =
                runCatching {
                    MpkgWriter.write(
                        listOf(MpkgInputEntry(oversizedPath, ByteArrayPayload(byteArrayOf()))),
                        output,
                        SCENE_MPKG_MAGIC,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("path"))
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shaderRewritePreservesVectorCoercionsAndHonorsCancellation() {
        assertEquals(
            "varying vec2 uv; void main() { vec2 x = uv * 2.0 + 1.0; }",
            ShaderCompatibility.rewrite("varying vec2 uv; void main() { vec2 x = uv * 2 + 1; }"),
        )
        var checks = 0
        val failure =
            runCatching {
                ShaderCompatibility.rewrite("varying vec2 uv; void main() { vec2 x = uv * 2; }") {
                    checks += 1
                    if (checks == 2) throw RewriteCancelled
                }
            }.exceptionOrNull()
        assertTrue(failure === RewriteCancelled)
    }

    @Test
    fun nestedPresetParametersMergeFromInnerToOuter() {
        val root = Files.createTempDirectory("wallhub-preset-merge").toFile()
        try {
            val inner = root.resolve("inner").apply { mkdirs() }
            val scene = root.resolve("scene").apply { mkdirs() }
            root.resolve("project.json").writeText(
                """{"title":"Outer","preset":{"shared":"outer","outerOnly":1}}""",
            )
            inner.resolve("project.json").writeText(
                """{"title":"Inner","preset":{"shared":"inner","innerOnly":2}}""",
            )
            scene.resolve("project.json").writeText("""{"type":"scene","title":"Base"}""")

            val merged =
                JSONObject(
                    WorkshopConverter
                        .mergePresetProject(scene, listOf(root, inner), "preview.jpg")
                        .toString(Charsets.UTF_8),
                )
            val preset = merged.getJSONObject("preset")
            assertEquals("outer", preset.getString("shared"))
            assertEquals(1, preset.getInt("outerOnly"))
            assertEquals(2, preset.getInt("innerOnly"))
            assertEquals("Outer", merged.getString("title"))
            assertEquals("preview.jpg", merged.getString("preview"))
        } finally {
            root.deleteRecursively()
        }
    }
}
