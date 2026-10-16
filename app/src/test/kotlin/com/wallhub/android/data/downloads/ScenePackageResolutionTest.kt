package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.ByteArrayPayload
import com.wallhub.prototype.mpkg.ConversionPlanJsonCodec
import com.wallhub.prototype.mpkg.ConversionResourceOutcome
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.MAX_PRESET_DEPENDENCY_DEPTH
import com.wallhub.prototype.mpkg.MpkgInputEntry
import com.wallhub.prototype.mpkg.MpkgWriter
import com.wallhub.prototype.mpkg.PresetDependencyResolver
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.SCENE_MPKG_MAGIC
import com.wallhub.prototype.mpkg.ShaderCompatibility
import com.wallhub.prototype.mpkg.StaleConversionSourceException
import com.wallhub.prototype.mpkg.WorkshopConverter
import com.wallhub.prototype.mpkg.policy
import com.wallhub.prototype.mpkg.WorkshopKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun conversionPresetPoliciesMatchOfficialV3Golden() {
        assertEquals(
            listOf(
                "balanced|v3|static=official_better_performance|unknown=passthrough_with_warning|texture_reduction=2",
                "compatibility|v3|static=official_high_performance|unknown=passthrough_with_warning|texture_reduction=4",
                "high_quality|v3|static=official_high_quality|unknown=passthrough_with_warning|texture_reduction=1",
            ),
            MpkgConversionPreset.entries.map { it.policy().stableDescription() },
        )
    }

    @Test
    fun conversionPlanIsDeterministicAndContainsNoAbsolutePath() {
        val first = Files.createTempDirectory("wallhub-plan-first").toFile()
        val second = Files.createTempDirectory("wallhub-plan-second").toFile()
        try {
            createPlannedScene(first)
            createPlannedScene(second)
            val device = DeviceCapabilityProfile(256L * 1024L * 1024L)

            val firstPlan = WorkshopConverter.plan(first, MpkgConversionPreset.BALANCED, device)
            val secondPlan = WorkshopConverter.plan(second, MpkgConversionPreset.BALANCED, device)
            val encoded = ConversionPlanJsonCodec.encode(firstPlan)

            assertEquals(firstPlan, secondPlan)
            assertEquals(ResourceStrategy.COPY, firstPlan.resources.single().strategy)
            assertEquals("copy_non_texture", firstPlan.resources.single().reason)
            assertFalse(encoded.contains(first.absolutePath))
            assertEquals(firstPlan, ConversionPlanJsonCodec.decode(encoded))
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun executingPlanReportsOutcomesAndRejectsStaleSourceIdentity() {
        val root = Files.createTempDirectory("wallhub-plan-execute").toFile()
        try {
            createPlannedScene(root)
            val plan = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DeviceCapabilityProfile(256L * 1024L * 1024L))
            val output = root.resolve("converted.mpkg")

            val report = WorkshopConverter.execute(plan, root, output)

            assertEquals(plan.sourceIdentity, report.sourceIdentity)
            assertEquals(
                listOf(ConversionResourceOutcome("scripts/demo.txt", ResourceStrategy.COPY, "copied")),
                report.outcomes,
            )
            val packageFile = root.resolve("scene.pkg")
            val mutated = packageFile.readBytes()
            mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()
            packageFile.writeBytes(mutated)
            val staleOutput = root.resolve("stale.mpkg")
            val failure = assertThrows(StaleConversionSourceException::class.java) {
                WorkshopConverter.execute(plan, root, staleOutput)
            }
            assertEquals("scene_package_identity_changed", failure.reason)
            assertFalse(staleOutput.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strategyChangedPlanIsTypedAsStaleForOneTimeRegeneration() {
        val root = Files.createTempDirectory("wallhub-plan-strategy-stale").toFile()
        try {
            createPlannedScene(root)
            val current = WorkshopConverter.plan(root, MpkgConversionPreset.BALANCED, DeviceCapabilityProfile(256L * 1024L * 1024L))
            val obsolete = current.copy(
                resources = current.resources.map { it.copy(reason = "obsolete_policy_reason") },
            )

            val failure = assertThrows(StaleConversionSourceException::class.java) {
                WorkshopConverter.execute(obsolete, root, root.resolve("obsolete.mpkg"))
            }

            assertEquals("resource_strategy_changed", failure.reason)
            assertFalse(root.resolve("obsolete.mpkg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stalePersistedPlanIsRegeneratedOnceAndReplacementExecutes() {
        val persisted = conversionPlan("0".repeat(64))
        val replacement = conversionPlan("1".repeat(64))
        var generated = 0
        var executions = 0

        val result =
            executeRegeneratingStalePlan(
                initialPlan = persisted,
                generate = {
                    generated += 1
                    replacement
                },
                execute = { current ->
                    executions += 1
                    if (current === persisted) throw StaleConversionSourceException("changed")
                    current
                },
            )

        assertSame(replacement, result)
        assertEquals(1, generated)
        assertEquals(2, executions)
    }

    @Test
    fun conversionPlanStoreRoundTripsMoreThan32768ResourcesAnd4MiBThroughAppPrivateFile() {
        withConversionPlanStore { store, root ->
            val plan = storedConversionPlan(resourceCount = 32_769, reasonLength = 128)

            val reference = store.write("task-large-plan", plan)

            assertTrue(reference.sizeBytes > 4L * 1024L * 1024L)
            assertEquals(plan, store.read("task-large-plan", reference))
            assertTrue(root.resolve("conversion-plans/task-large-plan.json").isFile)
            assertFalse(reference.toJson().contains(root.absolutePath))
        }
    }

    @Test
    fun streamedConversionPlanDigestMatchesCanonicalJsonDigest() {
        val plan = storedConversionPlan(resourceCount = 3)
        val expected =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(ConversionPlanJsonCodec.encode(plan).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals(expected, conversionPlanDigest(plan))
    }

    @Test
    fun conversionDiskEstimateUsesCheckedArithmeticWithoutArbitrary4GiBLimit() {
        assertEquals(12L * 1024L * 1024L * 1024L, checkedConversionDiskEstimate(sequenceOf(3L shl 30, 3L shl 30)))
        assertThrows(IllegalArgumentException::class.java) {
            checkedConversionDiskEstimate(sequenceOf(Long.MAX_VALUE, 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkedConversionDiskEstimate(sequenceOf(Long.MAX_VALUE / 2L + 1L))
        }
    }

    @Test
    fun conversionPlanStoreAtomicFailurePreservesCommittedPlan() {
        val root = Files.createTempDirectory("wallhub-plan-store").toFile()
        try {
            val initialStore = ConversionPlanStore(root)
            val initial = storedConversionPlan(resourceCount = 1)
            val reference = initialStore.write("atomic-task", initial)
            val failingStore = ConversionPlanStore(root) { _, _ -> error("move failed") }

            assertThrows(IllegalStateException::class.java) {
                failingStore.write("atomic-task", storedConversionPlan(resourceCount = 2))
            }

            assertEquals(initial, initialStore.read("atomic-task", reference))
            assertEquals(listOf("atomic-task.json"), root.resolve("conversion-plans").list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conversionPlanStoreRejectsTamperedDigest() {
        withConversionPlanStore { store, root ->
            val reference = store.write("tamper-task", storedConversionPlan(resourceCount = 2))
            root.resolve("conversion-plans/tamper-task.json").appendText(" ")

            assertThrows(IllegalArgumentException::class.java) {
                store.read("tamper-task", reference)
            }
        }
    }

    @Test
    fun conversionPlanStoreDeletesPlanAndRejectsUnsafeTaskId() {
        withConversionPlanStore { store, root ->
            store.write("deleted-task", storedConversionPlan(resourceCount = 2))

            assertTrue(store.delete("deleted-task"))
            assertFalse(root.resolve("conversion-plans/deleted-task.json").exists())
            assertThrows(IllegalArgumentException::class.java) {
                store.write("../escaped", storedConversionPlan(resourceCount = 1))
            }
        }
    }

    @Test
    fun invalidStoredPlanIsRegeneratedForRetry() {
        withConversionPlanStore { store, root ->
            val originalReference = store.write("retry-task", storedConversionPlan(resourceCount = 1))
            root.resolve("conversion-plans/retry-task.json").writeText("tampered")
            var generated = 0

            val result =
                executeRegeneratingStalePlan(
                    initialPlan = runCatching { store.read("retry-task", originalReference) }.getOrNull(),
                    generate = {
                        generated += 1
                        storedConversionPlan(resourceCount = 2).also { store.write("retry-task", it) }
                    },
                    execute = { it },
                )

            assertEquals(1, generated)
            assertEquals(2, result.resources.size)
        }
    }

    private fun withConversionPlanStore(block: (ConversionPlanStore, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("wallhub-plan-store").toFile()
        try {
            block(ConversionPlanStore(root), root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun storedConversionPlan(
        resourceCount: Int,
        reasonLength: Int = "copy_non_texture".length,
    ) =
        com.wallhub.prototype.mpkg.ConversionPlan(
            presetId = MpkgConversionPreset.BALANCED.stableId,
            policyVersion = 3,
            device = DeviceCapabilityProfile(256L * 1024L * 1024L),
            sourceIdentity = com.wallhub.prototype.mpkg.ScenePackageIdentity(packageLength = 123L, indexDigest = "a".repeat(64)),
            resources =
                List(resourceCount) { index ->
                    com.wallhub.prototype.mpkg.ConversionResourcePlan(
                        path = "resources/$index.bin",
                        sourceLength = index.toLong(),
                        strategy = ResourceStrategy.COPY,
                        reason = "r".repeat(reasonLength),
                    )
                },
        )

    private fun conversionPlan(digest: String) =
        com.wallhub.prototype.mpkg.ConversionPlan(
            presetId = MpkgConversionPreset.BALANCED.stableId,
            policyVersion = 3,
            device = DeviceCapabilityProfile(256L * 1024L * 1024L),
            sourceIdentity = com.wallhub.prototype.mpkg.ScenePackageIdentity(packageLength = 1L, indexDigest = digest),
            resources = emptyList(),
        )

    private fun createPlannedScene(root: java.io.File) {
        root.resolve("project.json").writeText("""{"type":"scene","file":"scene.json"}""")
        root.resolve("preview.png").writeBytes(byteArrayOf(1, 2, 3))
        val payload = "hello".toByteArray()
        val magic = "PKGV0001".toByteArray(Charsets.US_ASCII)
        val path = "scripts/demo.txt".toByteArray(Charsets.UTF_8)
        root.resolve("scene.pkg").outputStream().use { output ->
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(magic.size).array())
            output.write(magic)
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(path.size).array())
            output.write(path)
            output.write(ByteArray(Int.SIZE_BYTES))
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
            output.write(payload)
        }
    }
}
