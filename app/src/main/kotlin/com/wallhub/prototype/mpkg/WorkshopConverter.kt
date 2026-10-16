package com.wallhub.prototype.mpkg

import android.util.Log
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.data.downloads.readProjectJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

enum class WorkshopKind(
    val outputExtension: String,
) {
    SCENE("mpkg"),
    VIDEO("mpkg"),
    WEB("zip"),
}

data class ConversionWarningDetail(
    val code: String,
    val path: String,
)

data class WorkshopConversionReport(
    val kind: WorkshopKind,
    val outputFile: File,
    val convertedTextures: Int = 0,
    val copiedTextures: Int = 0,
    val removedAudio: Int = 0,
    val rewrittenShaders: Int = 0,
    val warnings: List<String> = emptyList(),
    val warningDetails: List<ConversionWarningDetail> = emptyList(),
    val warningCounts: Map<String, Int> = emptyMap(),
    val planVersion: Int? = null,
    val presetId: String? = null,
    val sourceIdentity: ScenePackageIdentity? = null,
    val outcomes: List<ConversionResourceOutcome> = emptyList(),
)

object WorkshopConverter {
    private val previewExtensions = listOf("gif", "jpg", "jpeg", "png", "webp")
    private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a")

    fun plan(
        inputDir: File,
        preset: MpkgConversionPreset = MpkgConversionPreset.BALANCED,
        device: DeviceCapabilityProfile = DeviceCapabilityProfile(Runtime.getRuntime().maxMemory()),
        preserveAudio: Boolean = false,
    ): ConversionPlan = WorkshopConversionPlanner.plan(inputDir, preset, device, preserveAudio)

    fun execute(
        plan: ConversionPlan,
        inputDir: File,
        outputFile: File,
        contentTypeHint: String? = null,
        checkCancellation: () -> Unit = {},
        animatedImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
        staticImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
        shaderRewriter: (String, () -> Unit) -> String = ShaderCompatibility::rewrite,
        shaderRewriterWithPath: ((String, String, () -> Unit) -> String)? = null,
        resourceCheckpoint: ConversionResourceCheckpoint? = null,
    ): WorkshopConversionReport {
        val current =
            WorkshopConversionPlanner.plan(
                inputDir,
                MpkgConversionPreset.fromStableId(plan.presetId),
                plan.device,
                plan.preserveAudio,
            )
        if (current.sourceIdentity != plan.sourceIdentity) {
            throw StaleConversionSourceException("scene_package_identity_changed")
        }
        if (current != plan) {
            throw StaleConversionSourceException("resource_strategy_changed")
        }
        val rejected = plan.resources.firstOrNull { it.strategy == ResourceStrategy.REJECT }
        require(rejected == null) { "Conversion plan rejected ${rejected?.path}: ${rejected?.reason}" }
        val report =
            convertScene(
                inputDir,
                outputFile,
                checkCancellation,
                animatedImageDecoder,
                staticImageDecoder,
                plan,
                shaderRewriter,
                resourceCheckpoint,
            )
        val sourceAfterConversion = resolveScenePackage(inputDir, inputDir.resolve("project.json").let { file ->
            JSONObject(file.readText()).optString("file").takeIf(String::isNotBlank)
        })
        if (WorkshopConversionPlanner.identity(sourceAfterConversion) != plan.sourceIdentity) {
            outputFile.delete()
            throw StaleConversionSourceException("scene_package_changed_during_execution")
        }
        val actualOutcomes = report.outcomes.associateBy(ConversionResourceOutcome::path)
        val outcomes =
            plan.resources.map { resource ->
                actualOutcomes[resource.path] ?: run {
                    val reason =
                        when (resource.strategy) {
                            ResourceStrategy.COPY -> "copied"
                            ResourceStrategy.RETAIN_VERIFIED_MOBILE -> "retained_verified_mobile_payload"
                            ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP -> "retained_verified_runtime_desktop_payload"
                            ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 -> "converted_to_etc2_rgba8_lz4"
                            ResourceStrategy.DOWNSAMPLE_TO_ETC2 -> "downsampled_to_etc2_rgba8_lz4"
                            ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 -> "converted_to_rgba8888_lz4"
                            ResourceStrategy.REWRITE_SHADER -> "rewritten_for_mobile_glsl"
                            ResourceStrategy.REMOVE_AUDIO -> "removed"
                            ResourceStrategy.PASSTHROUGH -> "passed_through"
                            ResourceStrategy.PASSTHROUGH_WITH_WARNING -> resource.reason
                            ResourceStrategy.REJECT -> error("Rejected resources cannot execute")
                        }
                    ConversionResourceOutcome(resource.path, resource.strategy, reason)
                }
            }
        return report.copy(
            planVersion = plan.version,
            presetId = plan.presetId,
            sourceIdentity = plan.sourceIdentity,
            outcomes = outcomes,
        )
    }

    fun detect(
        inputDir: File,
        contentTypeHint: String? = null,
    ): WorkshopKind {
        contentTypeHint?.trim()?.lowercase(Locale.ROOT)?.let { hint ->
            when (hint) {
                "video" -> return WorkshopKind.VIDEO
                "web", "website" -> return WorkshopKind.WEB
                "scene" -> return WorkshopKind.SCENE
            }
        }
        val project = readProject(inputDir)
        return when (project.string("type")?.lowercase(Locale.ROOT)) {
            "video" -> WorkshopKind.VIDEO
            "web", "website" -> WorkshopKind.WEB
            else -> WorkshopKind.SCENE
        }
    }

    fun convert(
        inputDir: File,
        outputFile: File,
        contentTypeHint: String? = null,
        checkCancellation: () -> Unit = {},
        animatedImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
        staticImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
    ): WorkshopConversionReport {
        require(inputDir.isDirectory) { "Workshop input directory is missing" }
        checkCancellation()
        return when (detect(inputDir, contentTypeHint)) {
            WorkshopKind.VIDEO -> convertVideo(inputDir, outputFile, checkCancellation)
            WorkshopKind.SCENE -> convertScene(inputDir, outputFile, checkCancellation, animatedImageDecoder, staticImageDecoder)
            WorkshopKind.WEB -> convertWeb(inputDir, outputFile, checkCancellation)
        }
    }

    private fun convertVideo(
        inputDir: File,
        outputFile: File,
        checkCancellation: () -> Unit,
    ): WorkshopConversionReport {
        val project = readProject(inputDir)
        val preview = findPreview(inputDir) ?: error("Video workshop is missing preview.*")
        val rawVideoName = project.string("file") ?: error("Video project.json is missing file")
        val videoFile = resolveLocalProjectFile(inputDir, rawVideoName)
        val normalizedVideoName = normalizeRelativePath(rawVideoName)
        val title = project.string("title").orEmpty().ifBlank { inputDir.name }
        val projectJson = buildVideoProjectJson(title, normalizedVideoName, preview.name)
        MpkgWriter.write(
            entries =
                listOf(
                    MpkgInputEntry(normalizedVideoName, FilePayload(videoFile)),
                    MpkgInputEntry(preview.name, FilePayload(preview)),
                    MpkgInputEntry("project.json", ByteArrayPayload(projectJson)),
                ),
            outputFile = outputFile,
            magic = VIDEO_MPKG_MAGIC,
            checkCancellation = checkCancellation,
        )
        return WorkshopConversionReport(WorkshopKind.VIDEO, outputFile)
    }

    private fun convertScene(
        inputDir: File,
        outputFile: File,
        checkCancellation: () -> Unit,
        animatedImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)?,
        staticImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)?,
        plan: ConversionPlan? = null,
        shaderRewriter: (String, () -> Unit) -> String = ShaderCompatibility::rewrite,
        shaderRewriterWithPath: ((String, String, () -> Unit) -> String)? = null,
        resourceCheckpoint: ConversionResourceCheckpoint? = null,
    ): WorkshopConversionReport {
        val projectFile = File(inputDir, "project.json")
        require(projectFile.isFile) { "Scene workshop is missing project.json" }
        val preview = findPreview(inputDir) ?: error("Scene workshop is missing preview.*")
        val project = readProject(inputDir)
        val presetResolution = PresetDependencyResolver.resolve(inputDir).getOrThrow()
        val presetDependency = presetResolution?.sceneRoot
        val sceneRoot = presetDependency ?: inputDir
        val sceneProject = readProject(sceneRoot)
        val scenePackage = resolveScenePackage(sceneRoot, sceneProject.string("file"))

        var convertedTextures = 0
        var copiedTextures = 0
        var removedAudio = 0
        var rewrittenShaders = 0
        val warningDetails = mutableListOf<ConversionWarningDetail>()
        val actualOutcomes = LinkedHashMap<String, ConversionResourceOutcome>()
        fun warn(code: String, path: String) {
            warningDetails += ConversionWarningDetail(code, path)
        }
        val archive = PkgReader.readIndex(scenePackage)
        fun checkpointRequest(entry: IndexedPkgEntry, strategy: ResourceStrategy): ConversionCheckpointRequest =
            ConversionCheckpointRequest(
                path = entry.path,
                sourceOffset = entry.offset,
                sourceLength = entry.length,
                sourceSha256 = digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation).toHex(),
                strategy = strategy,
            )
        val transformedDirectory = createConversionDirectory(outputFile)
        try {
            val entriesByPath = LinkedHashMap<String, MpkgInputEntry>(archive.entries.size)
            fun putEntry(entry: MpkgInputEntry) {
                entriesByPath[entry.path] = entry
            }
            fun fallbackToSource(entry: IndexedPkgEntry, transformed: File, code: String) {
                transformed.delete()
                digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation)
                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                copiedTextures += 1
                warn(code, entry.path)
                actualOutcomes[entry.path] =
                    ConversionResourceOutcome(entry.path, ResourceStrategy.PASSTHROUGH_WITH_WARNING, code)
            }
            archive.entries.forEachIndexed { index, entry ->
                checkCancellation()
                val extension = entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in audioExtensions) {
                    val strategy = plan?.resources?.get(index)?.strategy ?: ResourceStrategy.REMOVE_AUDIO
                    when (strategy) {
                        ResourceStrategy.COPY ->
                            putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                        ResourceStrategy.REMOVE_AUDIO -> removedAudio += 1
                        else -> error("Planned strategy $strategy is invalid for audio ${entry.path}")
                    }
                    return@forEachIndexed
                }
                when (extension) {
                    "tex" -> {
                        val route =
                            TexMobileConverter.inspectEntryRoute(
                                scenePackage,
                                entry.offset,
                                entry.length,
                                plan?.device?.maxHeapBytes ?: Runtime.getRuntime().maxMemory(),
                            )
                        val strategy =
                            plan?.resources?.get(index)?.also { planned ->
                                require(planned.path == entry.path && planned.sourceLength == entry.length) {
                                    "Conversion plan resource order does not match scene package"
                                }
                            }?.strategy ?: when (route) {
                                TexEntryRoute.RETAIN_VERIFIED_MOBILE,
                                TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE,
                                TexEntryRoute.RETAIN_VERIFIED_VIDEO,
                                -> ResourceStrategy.RETAIN_VERIFIED_MOBILE
                                TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP -> ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP
                                TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4
                                TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA,
                                TexEntryRoute.CONVERT_IN_MEMORY,
                                -> ResourceStrategy.CONVERT_TO_RGBA8888_LZ4
                                else -> ResourceStrategy.REJECT
                            }
                        if (
                            strategy == ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 ||
                            strategy == ResourceStrategy.DOWNSAMPLE_TO_ETC2 ||
                            strategy == ResourceStrategy.CONVERT_TO_RGBA8888_LZ4
                        ) {
                            // Source reads are outside the recoverable transform boundary: a short read,
                            // invalid range or I/O failure must abort rather than masquerade as a codec failure.
                            digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation)
                        }
                        when (strategy) {
                            ResourceStrategy.RETAIN_VERIFIED_MOBILE -> {
                                copiedTextures += 1
                                if (route == TexEntryRoute.RETAIN_VERIFIED_VIDEO) {
                                    warn("validated_video_texture_retained", entry.path)
                                }
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                            }
                            ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP -> {
                                require(route == TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP) {
                                    "Planned runtime desktop retention is incompatible with ${entry.path}"
                                }
                                copiedTextures += 1
                                warn("verified_runtime_desktop_texture_retained", entry.path)
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                            }
                            ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 -> {
                                require(route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2) { "Planned ETC2 strategy is incompatible with ${entry.path}" }
                                val transformed = File(transformedDirectory, "$index-etc2.tex")
                                val request = checkpointRequest(entry, strategy)
                                val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedTexture)
                                val product =
                                    reused ?: run {
                                        val result = TexMobileConverter.convertToEtc2File(scenePackage, entry.offset, entry.length, transformed, checkCancellation)
                                        transformed.takeIf { result.converted && isVerifiedTexture(it) }
                                            ?.let { resourceCheckpoint?.record(request, it) ?: it }
                                    }
                                if (product != null) {
                                    convertedTextures += 1
                                    Log.i("WallhubMpkg", "ETC2 re-encode succeeded for ${entry.path}")
                                    putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                                } else {
                                    transformed.delete()
                                    error("Known visual texture could not be converted to a mobile ETC2 payload: ${entry.path}")
                                }
                            }
                            ResourceStrategy.DOWNSAMPLE_TO_ETC2 -> {
                                require(route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2) { "Planned downsample strategy is incompatible with ${entry.path}" }
                                val transformed = File(transformedDirectory, "$index-downsampled-etc2.tex")
                                val request = checkpointRequest(entry, strategy)
                                val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedTexture)
                                val product =
                                    reused ?: run {
                                        val result =
                                            TexMobileConverter.downsampleToEtc2File(
                                                scenePackage,
                                                entry.offset,
                                                entry.length,
                                                entry.path,
                                                MpkgConversionPreset.fromStableId(requireNotNull(plan).presetId).policy().textureReduction,
                                                transformed,
                                                checkCancellation,
                                            )
                                        transformed.takeIf { result.converted && isVerifiedTexture(it) }
                                            ?.let { resourceCheckpoint?.record(request, it) ?: it }
                                    }
                                if (product != null) {
                                    convertedTextures += 1
                                    putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                                } else {
                                    transformed.delete()
                                    error("Known visual texture could not be reduced to a mobile ETC2 payload: ${entry.path}")
                                }
                            }
                            ResourceStrategy.PASSTHROUGH,
                            ResourceStrategy.PASSTHROUGH_WITH_WARNING,
                            -> {
                                val before = digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation)
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                                val after = digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation)
                                require(before.contentEquals(after)) { "Texture passthrough source changed: ${entry.path}" }
                                copiedTextures += 1
                                if (strategy == ResourceStrategy.PASSTHROUGH_WITH_WARNING) {
                                    warn(plan?.resources?.get(index)?.reason ?: "tex_passthrough_unknown_profile", entry.path)
                                }
                            }
                            ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 -> {
                                val transformed = File(transformedDirectory, "$index-rgba.tex")
                                val request = checkpointRequest(entry, strategy)
                                val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedTexture)
                                val product =
                                    reused ?: run {
                                        val result =
                                            when (route) {
                                                TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA ->
                                                    if (animatedImageDecoder == null) {
                                                        TexMobileConverter.convertAnimatedImageToRgbaFile(scenePackage, entry.offset, entry.length, transformed, checkCancellation)
                                                    } else {
                                                        TexMobileConverter.convertAnimatedImageToRgbaFile(scenePackage, entry.offset, entry.length, transformed, checkCancellation, animatedImageDecoder)
                                                    }
                                                TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> {
                                                    val limit = 64L * 1024L * 1024L
                                                    if (staticImageDecoder == null) {
                                                        TexMobileConverter.convertImageToRgbaFile(scenePackage, entry.offset, entry.length, transformed, limit, checkCancellation)
                                                    } else {
                                                        TexMobileConverter.convertImageToRgbaFile(scenePackage, entry.offset, entry.length, transformed, limit, checkCancellation, staticImageDecoder)
                                                    }
                                                }
                                                TexEntryRoute.CONVERT_IN_MEMORY -> {
                                                    require(entry.length <= texConversionInputLimit()) { "Static texture ${entry.path} exceeds the bounded in-memory conversion limit" }
                                                    TexMobileConverter.convertToFile(archive.readBytes(entry, texConversionInputLimit()), transformed, checkCancellation)
                                                }
                                                else -> error("Planned RGBA strategy is incompatible with ${entry.path}")
                                            }
                                        transformed.takeIf { result.converted && isVerifiedTexture(it) }
                                            ?.let { resourceCheckpoint?.record(request, it) ?: it }
                                    }
                                if (product != null) {
                                    convertedTextures += 1
                                    putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                                } else {
                                    fallbackToSource(entry, transformed, "texture_conversion_failed")
                                }
                            }
                            ResourceStrategy.REJECT -> error("Texture ${entry.path} is rejected by the conversion plan")
                            else -> error("Planned strategy $strategy is invalid for texture ${entry.path}")
                        }
                    }
                    "frag", "vert" -> {
                        checkCancellation()
                        val originalBytes = archive.readBytes(entry, MAX_SHADER_SOURCE_BYTES)
                        val original = originalBytes.toString(Charsets.UTF_8)
                        val strategy = plan?.resources?.get(index)?.strategy ?: ResourceStrategy.REWRITE_SHADER
                        val request = checkpointRequest(entry, strategy)
                        val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedShader)
                        if (reused != null) {
                            if (!reused.readBytes().contentEquals(originalBytes)) rewrittenShaders += 1
                            putEntry(MpkgInputEntry(entry.path, FilePayload(reused)))
                        } else {
                            checkCancellation()
                            val rewritten =
                                try {
                                    shaderRewriterWithPath?.invoke(entry.path, original, checkCancellation)
                                        ?: ShaderCompatibility.rewriteKnownOrConservative(entry.path, original, checkCancellation)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    null
                                }
                            if (rewritten == null) {
                                digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation)
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                                warn("shader_rewrite_failed", entry.path)
                                actualOutcomes[entry.path] =
                                    ConversionResourceOutcome(entry.path, ResourceStrategy.PASSTHROUGH_WITH_WARNING, "shader_rewrite_failed")
                            } else {
                                checkCancellation()
                                if (rewritten != original) rewrittenShaders += 1
                                val transformed = File(transformedDirectory, "$index.$extension")
                                transformed.writeText(rewritten, Charsets.UTF_8)
                                checkCancellation()
                                require(isVerifiedShader(transformed)) { "Rewritten shader verification failed: ${entry.path}" }
                                val product = resourceCheckpoint?.record(request, transformed) ?: transformed
                                putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                            }
                        }
                    }
                    else -> {
                        if (entry.path.equals("scene.json", ignoreCase = true) && plan != null) {
                            val source = archive.readBytes(entry, MAX_SCENE_JSON_BYTES)
                            val reduction = MpkgConversionPreset.fromStableId(plan.presetId).policy().textureReduction
                            putEntry(MpkgInputEntry(entry.path, ByteArrayPayload(rewriteSceneTextureReduction(source, reduction))))
                        } else {
                            putEntry(
                                MpkgInputEntry(
                                    entry.path,
                                    FileSlicePayload(scenePackage, entry.offset, entry.length),
                                ),
                            )
                        }
                    }
                }
            }
            if (presetDependency != null) {
                presetResolution!!.presetRoots.asReversed().forEach { presetRoot ->
                    val dependencyPath = File(presetRoot, PRESET_DEPENDENCY_DIRECTORY_NAME).canonicalFile.toPath()
                    walkFiles(presetRoot, checkCancellation)
                        .filterNot { file ->
                            file.name == "project.json" ||
                                file.nameWithoutExtension.equals("preview", ignoreCase = true) ||
                                file.canonicalFile.toPath().startsWith(dependencyPath)
                        }.forEach { file ->
                            val path = relativePath(presetRoot, file)
                            putEntry(MpkgInputEntry(path, FilePayload(file)))
                        }
                }
            }
            val outputProject =
                if (presetDependency == null) {
                    FilePayload(projectFile)
                } else {
                    ByteArrayPayload(mergePresetProject(sceneRoot, presetResolution!!.presetRoots, preview.name))
                }
            putEntry(MpkgInputEntry("project.json", outputProject))
            putEntry(MpkgInputEntry(preview.name, FilePayload(preview)))
            // Verification is part of the commit boundary: a malformed candidate must never replace an
            // existing good package or remain visible as the requested output.
            writeAtomically(outputFile) { candidate ->
                fun writeCandidate() {
                    MpkgWriter.write(entriesByPath.values.toList(), candidate, SCENE_MPKG_MAGIC, checkCancellation)
                    checkCancellation()
                }
                fun failedConvertedTextures(): List<IndexedPkgEntry> {
                    val verifiedOutput = PkgReader.readIndex(candidate)
                    return verifiedOutput.entries.filter { outputEntry ->
                        val planned = plan?.resources?.singleOrNull { it.path == outputEntry.path }?.strategy
                        val actual = actualOutcomes[outputEntry.path]?.strategy ?: planned
                        outputEntry.path.substringAfterLast('.', "").equals("tex", ignoreCase = true) &&
                            actual in setOf(
                                ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                                ResourceStrategy.DOWNSAMPLE_TO_ETC2,
                                ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
                            ) &&
                            !runCatching {
                                TexMobileConverter.inspectEntryRoute(candidate, outputEntry.offset, outputEntry.length).isVerifiedPassthroughRoute()
                            }.getOrDefault(false)
                    }
                }
                writeCandidate()
                failedConvertedTextures().forEach { failedOutput ->
                    val sourceEntry = archive.entries.single { it.path == failedOutput.path }
                    entriesByPath[failedOutput.path] =
                        MpkgInputEntry(failedOutput.path, FileSlicePayload(scenePackage, sourceEntry.offset, sourceEntry.length))
                    convertedTextures -= 1
                    copiedTextures += 1
                    warn("texture_output_verification_failed", failedOutput.path)
                    actualOutcomes[failedOutput.path] =
                        ConversionResourceOutcome(
                            failedOutput.path,
                            ResourceStrategy.PASSTHROUGH_WITH_WARNING,
                            "texture_output_verification_failed",
                        )
                }
                if (actualOutcomes.values.any { it.reason == "texture_output_verification_failed" }) writeCandidate()

                val verifiedOutput = PkgReader.readIndex(candidate)
                failedConvertedTextures().also { failures ->
                    require(failures.isEmpty()) { "Output texture ${failures.first().path} failed mobile reinspection" }
                }
                verifiedOutput.entries
                    .filter { it.path.substringAfterLast('.', "").equals("tex", ignoreCase = true) }
                    .filterNot { outputEntry ->
                        val planned = plan?.resources?.singleOrNull { it.path == outputEntry.path }?.strategy
                        val actual = actualOutcomes[outputEntry.path]?.strategy ?: planned
                        actual == ResourceStrategy.PASSTHROUGH || actual == ResourceStrategy.PASSTHROUGH_WITH_WARNING
                    }.forEach { outputEntry ->
                        require(
                            TexMobileConverter.inspectEntryRoute(candidate, outputEntry.offset, outputEntry.length).isVerifiedPassthroughRoute(),
                        ) { "Output texture ${outputEntry.path} failed mobile reinspection" }
                    }
                val passthroughPaths =
                    plan
                        ?.resources
                        ?.filter { it.strategy == ResourceStrategy.PASSTHROUGH || it.strategy == ResourceStrategy.PASSTHROUGH_WITH_WARNING }
                        ?.mapTo(mutableSetOf<String>(), ConversionResourcePlan::path)
                        ?: mutableSetOf()
                actualOutcomes.values
                    .filter { it.strategy == ResourceStrategy.PASSTHROUGH || it.strategy == ResourceStrategy.PASSTHROUGH_WITH_WARNING }
                    .mapTo(passthroughPaths, ConversionResourceOutcome::path)
                passthroughPaths.forEach { path ->
                    val outputEntry = verifiedOutput.entries.single { it.path == path }
                    val sourceEntry = archive.entries.single { it.path == path }
                    require(outputEntry.length == sourceEntry.length) { "Passthrough entry length changed: $path" }
                    require(
                        digestFileSlice(candidate, outputEntry.offset, outputEntry.length, checkCancellation).contentEquals(
                            digestFileSlice(scenePackage, sourceEntry.offset, sourceEntry.length, checkCancellation),
                        ),
                    ) { "Passthrough entry digest changed: $path" }
                }
            }
        } finally {
            transformedDirectory.deleteRecursively()
        }
        val warningCounts =
            warningDetails
                .groupingBy(ConversionWarningDetail::code)
                .eachCount()
                .toSortedMap()
        val boundedWarningDetails =
            warningDetails
                .distinct()
                .sortedWith(compareBy(ConversionWarningDetail::path).thenBy(ConversionWarningDetail::code))
                .take(MAX_WARNING_DETAILS)
        return WorkshopConversionReport(
            kind = WorkshopKind.SCENE,
            outputFile = outputFile,
            convertedTextures = convertedTextures,
            copiedTextures = copiedTextures,
            removedAudio = removedAudio,
            rewrittenShaders = rewrittenShaders,
            warnings = boundedWarningDetails.map { "${it.code}: ${it.path}" },
            warningDetails = boundedWarningDetails,
            warningCounts = warningCounts,
            outcomes = actualOutcomes.values.toList(),
        )
    }

    private fun convertWeb(
        inputDir: File,
        outputFile: File,
        checkCancellation: () -> Unit,
    ): WorkshopConversionReport {
        val outputPath = outputFile.canonicalFile.toPath()
        val files =
            walkFiles(inputDir, checkCancellation)
                .filterNot { it.canonicalFile.toPath() == outputPath }
        writeAtomically(outputFile) { temporaryFile ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { zip ->
                files.forEach { file ->
                    checkCancellation()
                    val path = relativePath(inputDir, file)
                    zip.putNextEntry(ZipEntry(path))
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            checkCancellation()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return WorkshopConversionReport(WorkshopKind.WEB, outputFile)
    }

    private fun isVerifiedTexture(file: File): Boolean =
        runCatching {
            file.isFile && TexMobileConverter.inspectEntryRoute(file, 0L, file.length()).isVerifiedPassthroughRoute()
        }.getOrDefault(false)

    private fun isVerifiedShader(file: File): Boolean =
        runCatching {
            file.isFile && file.length() <= MAX_SHADER_SOURCE_BYTES &&
                file.readText(Charsets.UTF_8).toByteArray(Charsets.UTF_8).size.toLong() == file.length()
        }.getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun digestFileSlice(
        source: File,
        offset: Long,
        length: Long,
        checkCancellation: () -> Unit,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            var remaining = length
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (remaining > 0) {
                checkCancellation()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "Unexpected end of passthrough texture" }
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }

    private fun createConversionDirectory(outputFile: File): File {
        val parent = outputFile.absoluteFile.parentFile ?: error("Output file has no parent directory")
        check(parent.exists() || parent.mkdirs()) { "Unable to create MPKG output directory" }
        repeat(10) {
            val candidate = File(parent, ".${outputFile.name}.assets-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        error("Unable to create MPKG conversion directory")
    }

    private fun findPreview(inputDir: File): File? {
        val files =
            inputDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.nameWithoutExtension.equals("preview", ignoreCase = true) }
        return files.minWithOrNull(
            compareBy<File> {
                previewExtensions.indexOf(it.extension.lowercase(Locale.ROOT)).let { index ->
                    if (index >= 0) index else Int.MAX_VALUE
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    private fun resolveLocalProjectFile(
        root: File,
        rawPath: String,
    ): File {
        val normalized = normalizeRelativePath(rawPath)
        val rootPath = root.canonicalFile.toPath()
        val candidate = File(root, normalized).canonicalFile
        require(candidate.toPath().startsWith(rootPath)) { "Video file escapes workshop folder" }
        require(candidate.isFile) { "Video project file is missing: $normalized" }
        return candidate
    }

    internal fun resolveScenePackage(
        root: File,
        projectFile: String?,
    ): File {
        val packagePath =
            projectFile
                ?.takeIf(String::isNotBlank)
                ?.let(::normalizeRelativePath)
                ?.let { path ->
                    val extension = path.substringAfterLast('.', "")
                    require(extension.equals("json", ignoreCase = true) || extension.equals("pkg", ignoreCase = true)) {
                        "Scene project file must reference a JSON or PKG file"
                    }
                    if (extension.equals("json", ignoreCase = true)) {
                        path.dropLast(extension.length) + "pkg"
                    } else {
                        path
                    }
                } ?: "scene.pkg"
        val rootPath = root.canonicalFile.toPath()
        val candidate = File(root, packagePath).canonicalFile
        require(candidate.toPath().startsWith(rootPath)) { "Scene package escapes workshop folder" }
        require(candidate.isFile) { "Scene workshop is missing $packagePath" }
        return candidate
    }

    internal fun resolvePresetDependency(root: File): File? =
        PresetDependencyResolver.resolve(root).getOrThrow()?.sceneRoot

    internal fun mergePresetProject(
        sceneRoot: File,
        presetRoots: List<File>,
        previewName: String,
    ): ByteArray {
        val base = JSONObject(readProjectJson(File(sceneRoot, "project.json")))
        val mergedPreset = JSONObject()
        presetRoots.asReversed().forEach { presetRoot ->
            val project = JSONObject(readProjectJson(File(presetRoot, "project.json")))
            mergeJsonObjects(mergedPreset, project.getJSONObject("preset"))
            project.optString("title").takeIf(String::isNotBlank)?.let { base.put("title", it) }
        }
        base.put("preset", mergedPreset)
        base.put("preview", previewName)
        base.remove("dependency")
        return base.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun mergeJsonObjects(
        target: JSONObject,
        source: JSONObject,
    ) {
        source.keys().forEach { key ->
            val sourceValue = source.get(key)
            val targetValue = target.opt(key)
            if (sourceValue is JSONObject && targetValue is JSONObject) {
                mergeJsonObjects(targetValue, sourceValue)
            } else {
                target.put(key, sourceValue)
            }
        }
    }

    private fun normalizeRelativePath(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.matches(Regex("^[A-Za-z]:.*"))) {
            "Project file must be a relative path"
        }
        return MpkgWriter.normalizePath(normalized)
    }

    private fun relativePath(
        root: File,
        file: File,
    ): String {
        val rootPath = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(rootPath)) { "Website file escapes workshop folder" }
        return MpkgWriter.normalizePath(rootPath.relativize(path).toString())
    }

    private fun walkFiles(
        root: File,
        checkCancellation: () -> Unit,
    ): List<File> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .onEach { checkCancellation() }
            .sortedBy { relativePath(root, it).lowercase(Locale.ROOT) }
            .toList()

    private fun readProject(inputDir: File): WorkshopProject {
        val projectFile = File(inputDir, "project.json")
        require(projectFile.isFile) { "Workshop input is missing project.json" }
        return WorkshopProject(readProjectJson(projectFile))
    }

    private fun buildVideoProjectJson(
        title: String,
        videoName: String,
        previewName: String,
    ): ByteArray {
        fun escape(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        return buildString {
            append("{\r\n")
            append("\t\"file\" : \"").append(escape(videoName)).append("\",\r\n")
            append("\t\"preview\" : \"").append(escape(previewName)).append("\",\r\n")
            append("\t\"title\" : \"").append(escape(title)).append("\",\r\n")
            append("\t\"type\" : \"video\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun rewriteSceneTextureReduction(
        source: ByteArray,
        textureReduction: Int,
    ): ByteArray {
        require(textureReduction in setOf(1, 2, 4)) { "Unsupported official texture reduction" }
        val scene = JSONObject(source.toString(Charsets.UTF_8))
        val general = scene.optJSONObject("general") ?: JSONObject().also { scene.put("general", it) }
        if (textureReduction == 1) {
            general.remove("texturereduction")
        } else {
            general.put("texturereduction", textureReduction)
        }
        return scene.toString(2).toByteArray(Charsets.UTF_8)
    }

    private class WorkshopProject(
        private val source: String,
    ) {
        fun string(name: String): String? {
            val expression = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
            val encoded = expression.find(source)?.groupValues?.getOrNull(1) ?: return null
            return encoded.replace(Regex("\\\\(.)")) { match ->
                when (match.groupValues[1]) {
                    "n" -> "\n"
                    "r" -> "\r"
                    "t" -> "\t"
                    else -> match.groupValues[1]
                }
            }
        }
    }
}

object ShaderCompatibility {
    fun rewriteKnownOrConservative(
        path: String,
        source: String,
        checkCancellation: () -> Unit = {},
    ): String {
        checkCancellation()
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        return if (
            normalized.endsWith("effects/xray.vert") || normalized.endsWith("effects/xray.frag")
        ) {
            rewriteXray(source, checkCancellation)
        } else {
            rewrite(source, checkCancellation)
        }
    }

    private fun rewriteXray(source: String, checkCancellation: () -> Unit): String {
        if (source.contains("uniform vec4 g_PointerState;")) return rewrite(source, checkCancellation)
        require(source.contains("v_PointerUV") && source.contains("v_PointerScale")) {
            "Known xray shader structure signature did not match"
        }
        var output = rewrite(source, checkCancellation)
        if (!output.contains("uniform vec4 g_PointerState;")) {
            val marker = "varying float v_PointerScale;"
            require(output.contains(marker)) { "Known xray shader injection point is missing" }
            output = output.replaceFirst(marker, "$marker\nuniform vec4 g_PointerState;")
        }
        if (output.contains("unprojectedUVs += 0.5;") && !output.contains("g_PointerState.y")) {
            output = output.replaceFirst(
                "unprojectedUVs += 0.5;",
                "unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));\nunprojectedUVs += 0.5;",
            )
        }
        return output
    }

    private val sampleCountDeclaration = Regex("const int sampleCount = (\\d+);")
    private val sampleCountLoop = Regex("for \\(int i = 0; i < sampleCount; \\+\\+i\\)")
    private val textureCoordinateZero = Regex("(v_TexCoord\\.[zw] = )0;")
    private val vectorDeclaration =
        Regex("""(?:uniform\s+|attribute\s+|varying\s+)?vec[234]\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val floatLiteral = Regex("""\d+\.\d*|\.\d+""")
    private val integerLiteral = Regex("""\d+""")
    private val numericArgument =
        Regex("""(?<![\w.])(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+\-]?\d+)?(?![\w.])""")
    private val mixCall = Regex("""(?<![A-Za-z0-9_])mix\(""")
    private val packedAudioSpectrumIndex =
        Regex(
            """(g_AudioSpectrum64(?:Left|Right))\[([A-Za-z_][A-Za-z0-9_]*)\s*/\s*4\]\[\2\s*%\s*4\]""",
        )
    private const val COMMON_BLUR_INCLUDE = "#include \"common_blur.h\""
    private const val HIDDEN_TEXTURE_DECLARATION = "uniform sampler2D g_Texture0; // {\"hidden\":true}"
    private const val HIDDEN_TEXTURE_MARKER = " // {\"hidden\":true}"

    fun rewrite(
        source: String,
        checkCancellation: () -> Unit = {},
    ): String {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SHADER_SOURCE_BYTES) {
            "Shader source exceeds the $MAX_SHADER_SOURCE_BYTES-byte rewrite limit"
        }
        checkCancellation()
        var output = source.replace(Regex("\\bsample\\b"), "_sample")
        mapOf(
            Regex("(?<![A-Za-z0-9_])vec4\\(1, 0, 0, 1\\)") to "vec4(1.0, 0.0, 0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(1, 0\\)") to "vec2(1.0, 0.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 1\\)") to "vec2(0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, -0\\.5\\)") to "vec2(0.0, -0.5)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 0\\.5\\)") to "vec2(0.0, 0.5)",
        ).forEach { (old, new) -> output = output.replace(old, new) }
        mapOf(
            "CAST3(0)" to "CAST3(0.0)",
            "mix(blurred.a, 1, step(blurred.a, 0))" to "mix(blurred.a, 1.0, step(blurred.a, 0.0))",
            "2 * abs(" to "2.0 * abs(",
            "30 / 50.0" to "30.0 / 50.0",
            "30 / 4.0" to "30.0 / 4.0",
            "30 / 8.0" to "30.0 / 8.0",
            "30 / 15.0" to "30.0 / 15.0",
        ).forEach { (old, new) -> output = output.replace(old, new) }
        checkCancellation()
        output = rewriteVectorIntegerArithmetic(output)
        checkCancellation()
        output = rewriteMixArguments(output)
        checkCancellation()
        output = rewritePackedAudioSpectrumIndex(output)
        checkCancellation()
        output = rewriteVec3ColorAccumulator(output)
        checkCancellation()
        output =
            output
                .replace(
                    "#define SMOOTH_CURVE A_SMOOTH_CURVE // For compatability with old wallpapers that get ported to Android\r\n\r\n",
                    "",
                ).replace(
                    "#define SMOOTH_CURVE A_SMOOTH_CURVE // For compatability with old wallpapers that get ported to Android\n\n",
                    "",
                )
        output =
            output.replace(sampleCountDeclaration) { match ->
                "const float sampleCount = ${match.groupValues[1]}.0;"
            }
        output = output.replace("const float sampleDrop = sampleCount - 1;", "const float sampleDrop = sampleCount - 1.0;")
        output = output.replace(sampleCountLoop, "for (float i = 0.0; i < sampleCount; ++i)")
        output = output.replace(textureCoordinateZero) { match -> "${match.groupValues[1]}0.0;" }
        if (output.contains(COMMON_BLUR_INCLUDE)) {
            val newline = if (output.contains("\r\n")) "\r\n" else "\n"
            output = output.replace(HIDDEN_TEXTURE_DECLARATION, HIDDEN_TEXTURE_MARKER)
            if (output.contains(HIDDEN_TEXTURE_MARKER) && !output.contains("uniform sampler2D g_Texture0;")) {
                output =
                    output.replaceFirst(
                        COMMON_BLUR_INCLUDE,
                        "uniform sampler2D g_Texture0;$newline$COMMON_BLUR_INCLUDE",
                    )
            }
        }
        return output
    }

    private fun rewriteVectorIntegerArithmetic(source: String): String {
        val vectorNames =
            vectorDeclaration
                .findAll(source)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .toSet()
        if (vectorNames.isEmpty()) return source
        val vectorName = vectorNames.joinToString("|") { Regex.escape(it) }
        val numericOperand = """(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+\-]?\d+)?"""
        val trailingExpression =
            Regex(
                """(?<![A-Za-z0-9_])((?:$vectorName)(?:\.\w+)?(?:\s*[*+/\-]\s*$numericOperand(?![\w.]))+)""",
            )
        val leadingExpression =
            Regex(
                """(?<![A-Za-z0-9_.])((?:$numericOperand(?![\w.])\s*[*+/\-]\s*)+(?:$vectorName)(?:\.\w+)?)""",
            )
        val trailingRewritten = trailingExpression.replace(source) { match -> floatArguments(match.value) }
        return leadingExpression.replace(trailingRewritten) { match -> floatArguments(match.value) }
    }

    private fun floatArguments(expression: String): String =
        expression.replace(numericArgument) { match ->
            if (match.value.all(Char::isDigit)) "${match.value}.0" else match.value
        }

    private fun rewriteMixArguments(source: String): String {
        val calls = mixCall.findAll(source).toList()
        if (calls.isEmpty()) return source
        val output = StringBuilder(source.length)
        var cursor = 0
        calls.forEach { call ->
            if (call.range.first < cursor) return@forEach
            val openParenthesis = call.range.last + 1
            val closeParenthesis = matchingParenthesis(source, openParenthesis)
            if (closeParenthesis < 0) return@forEach
            output.append(source, cursor, openParenthesis + 1)
            output.append(normalizeMixArguments(source.substring(openParenthesis + 1, closeParenthesis)))
            cursor = closeParenthesis
        }
        output.append(source, cursor, source.length)
        return output.toString()
    }

    private fun normalizeMixArguments(body: String): String {
        val normalizedBody = rewriteMixArguments(body)
        if (!floatLiteral.containsMatchIn(normalizedBody)) return normalizedBody
        val arguments = splitTopLevelArguments(normalizedBody)
        val coercedArguments =
            arguments.map { argument ->
                val trimmed = argument.trim()
                if (integerLiteral.matches(trimmed)) "$trimmed.0" else argument
            }
        if (coercedArguments == arguments) return normalizedBody
        return coercedArguments.joinToString(",")
    }

    private fun rewritePackedAudioSpectrumIndex(source: String): String =
        source.replace(packedAudioSpectrumIndex) { match ->
            val spectrum = match.groupValues[1]
            val index = match.groupValues[2]
            "$spectrum[int($index)]"
        }

    private fun rewriteVec3ColorAccumulator(source: String): String {
        if (!source.contains("vec3 drawSence(") || !source.contains("return col;")) return source
        return source
            .replace("vec4 col = vec4(0.,0.,0.,0.);", "vec3 col = vec3(0.,0.,0.);")
            .replace(
                "col = vec4(mix(u_userNewColor, col, step(0., sence)), 1.);",
                "col = mix(u_userNewColor, col, step(0., sence));",
            )
    }

    private fun matchingParenthesis(
        source: String,
        openParenthesis: Int,
    ): Int {
        var depth = 0
        for (position in openParenthesis until source.length) {
            when (source[position]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return position
                }
            }
        }
        return -1
    }

    private fun splitTopLevelArguments(body: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        body.forEach { character ->
            when (character) {
                '(', '[' -> {
                    depth += 1
                    current.append(character)
                }
                ')', ']' -> {
                    depth -= 1
                    current.append(character)
                }
                ',' ->
                    if (depth == 0) {
                        arguments += current.toString()
                        current.setLength(0)
                    } else {
                        current.append(character)
                    }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) arguments += current.toString()
        return arguments
    }
}

internal fun texConversionInputLimit(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
    (maxHeapBytes / TEX_CONVERSION_HEAP_DIVISOR).coerceIn(MIN_TEX_CONVERSION_INPUT_BYTES, MAX_TEX_CONVERSION_INPUT_BYTES)

private const val TEX_CONVERSION_HEAP_DIVISOR = 24L
private const val MIN_TEX_CONVERSION_INPUT_BYTES = 2L * 1024L * 1024L
private const val MAX_TEX_CONVERSION_INPUT_BYTES = 8L * 1024L * 1024L
internal const val MAX_SHADER_SOURCE_BYTES = 8L * 1024L * 1024L
private const val MAX_SCENE_JSON_BYTES = 8L * 1024L * 1024L
private const val COPY_BUFFER_SIZE = 1024 * 1024
private const val MAX_WARNING_DETAILS = 100
