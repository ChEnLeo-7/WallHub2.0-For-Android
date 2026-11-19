package com.wallhub.android.data.downloads

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
