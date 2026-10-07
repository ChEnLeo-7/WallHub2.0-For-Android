package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ByteArrayPayload
import com.wallhub.prototype.mpkg.MAX_PRESET_DEPENDENCY_DEPTH
import com.wallhub.prototype.mpkg.MpkgInputEntry
import com.wallhub.prototype.mpkg.MpkgWriter
import com.wallhub.prototype.mpkg.PresetDependencyResolver
import com.wallhub.prototype.mpkg.SCENE_MPKG_MAGIC
import com.wallhub.prototype.mpkg.ShaderCompatibility
import com.wallhub.prototype.mpkg.WorkshopConverter
import com.wallhub.prototype.mpkg.WorkshopKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `scene converter accepts valid project JSON larger than 256 KiB`() {
        val root = Files.createTempDirectory("wallhub-large-scene-project").toFile()
        try {
            val projectPrefix = "{\"type\":\"scene\",\"file\":\"scene.json\",\"padding\":\""
            val projectSuffix = "\"}"
            val projectJson = projectPrefix + "a".repeat(336_997 - projectPrefix.length - projectSuffix.length) + projectSuffix
            assertEquals(336_997, projectJson.toByteArray(Charsets.UTF_8).size)
            root.resolve("project.json").writeText(projectJson)
            root.resolve("preview.jpg").writeBytes(byteArrayOf(1))
            writeEmptyPkg(root.resolve("scene.pkg"))
            val output = root.resolve("converted.mpkg")

            val report = WorkshopConverter.convert(root, output)

            assertEquals(WorkshopKind.SCENE, report.kind)
            assertTrue(output.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scene converter rejects project JSON larger than sane limit`() {
        val root = Files.createTempDirectory("wallhub-oversized-scene-project").toFile()
        try {
            root.resolve("project.json").writeBytes(ByteArray(MAX_PROJECT_JSON_BYTES + 1) { 'a'.code.toByte() })
            root.resolve("preview.jpg").writeBytes(byteArrayOf(1))
            writeEmptyPkg(root.resolve("scene.pkg"))
            val output = root.resolve("converted.mpkg")

            val failure = runCatching { WorkshopConverter.convert(root, output) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("read limit"))
            assertFalse(output.exists())
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
            requireNotNull(loop.parentFile).mkdirs()
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

    private fun writeEmptyPkg(file: java.io.File) {
        val magic = "PKGV0001".toByteArray(Charsets.UTF_8)
        val bytes =
            ByteArrayOutputStream().use { output ->
                output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(magic.size).array())
                output.write(magic)
                output.write(ByteArray(Int.SIZE_BYTES))
                output.toByteArray()
            }
        file.writeBytes(bytes)
    }

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
    fun shaderRewritePreservesCompleteDecimalAndScientificOperands() {
        val source =
            """
            varying vec2 uv;
            varying vec2 texCoordMotion;
            varying vec2 sines;
            vec3 drawSence(vec2 inputUv) {
                uv = uv - 0.5;
                texCoordMotion = texCoordMotion - 0.5;
                sines = sines * 0.498 + 0.5;
                uv = uv + -0.25;
                uv = uv * 1e-3;
                uv = 1e-3 * uv;
                uv = uv * 0.5 + 2;
                uv = uv * 1e-3 + 2;
                uv = uv * 2 + 1;
                float spectrum = g_AudioSpectrum64Left[barID / 4][barID % 4];
                vec4 col = vec4(0.,0.,0.,0.);
                col = vec4(mix(u_userNewColor, col, step(0., sence)), 1.);
                return col;
            }
            """.trimIndent()
        val expected =
            """
            varying vec2 uv;
            varying vec2 texCoordMotion;
            varying vec2 sines;
            vec3 drawSence(vec2 inputUv) {
                uv = uv - 0.5;
                texCoordMotion = texCoordMotion - 0.5;
                sines = sines * 0.498 + 0.5;
                uv = uv + -0.25;
                uv = uv * 1e-3;
                uv = 1e-3 * uv;
                uv = uv * 0.5 + 2.0;
                uv = uv * 1e-3 + 2.0;
                uv = uv * 2.0 + 1.0;
                float spectrum = g_AudioSpectrum64Left[int(barID)];
                vec3 col = vec3(0.,0.,0.);
                col = mix(u_userNewColor, col, step(0., sence));
                return col;
            }
            """.trimIndent()

        assertEquals(expected, ShaderCompatibility.rewrite(source))
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
