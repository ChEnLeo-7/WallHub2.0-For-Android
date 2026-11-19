package com.wallhub.prototype.mpkg

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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
        preset: MpkgConversionPreset = MpkgConversionPreset.HIGH_QUALITY,
        device: DeviceCapabilityProfile = DeviceCapabilityProfile(Runtime.getRuntime().maxMemory()),
        preserveAudio: Boolean = false,
        timing: MpkgTimingSession? = null,
    ): ConversionPlan =
        timing.measureIfEnabled(MpkgPhase.PARSE) {
            WorkshopConversionPlanner.plan(inputDir, preset, device, preserveAudio)
        }

    fun execute(
        plan: ConversionPlan,
        inputDir: File,
        outputFile: File,
        contentTypeHint: String? = null,
        checkCancellation: () -> Unit = {},
        animatedImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
        staticImageDecoder: ((File, Int, Int, () -> Unit) -> ByteArray)? = null,
        shaderRewriter: ((String, () -> Unit) -> String)? = null,
        shaderRewriterWithPath: ((String, String, () -> Unit) -> String?)? = null,
        resourceCheckpoint: ConversionResourceCheckpoint? = null,
        timing: MpkgTimingSession? = null,
    ): WorkshopConversionReport {
        val current =
            timing.measureIfEnabled(MpkgPhase.PARSE) {
                WorkshopConversionPlanner.plan(
                    inputDir,
                    MpkgConversionPreset.fromStableId(plan.presetId),
                    plan.device,
                    plan.preserveAudio,
                )
            }
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
                shaderRewriterWithPath,
                resourceCheckpoint,
                timing,
            )
        val sourceAfterConversion = resolveCurrentScenePackage(inputDir)
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
                            ResourceStrategy.REWRITE_SCENE_SETTINGS -> "rewritten_scene_settings"
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
        shaderRewriter: ((String, () -> Unit) -> String)? = null,
        shaderRewriterWithPath: ((String, String, () -> Unit) -> String?)? = null,
        resourceCheckpoint: ConversionResourceCheckpoint? = null,
        timing: MpkgTimingSession? = null,
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

        val convertedTextures = AtomicInteger()
        val copiedTextures = AtomicInteger()
        val removedAudio = AtomicInteger()
        val rewrittenShaders = AtomicInteger()
        val warningDetails = Collections.synchronizedList(mutableListOf<ConversionWarningDetail>())
        val actualOutcomes = ConcurrentHashMap<String, ConversionResourceOutcome>()
        fun warn(code: String, path: String) {
            warningDetails += ConversionWarningDetail(code, path)
        }
        val archive = PkgReader.readIndex(scenePackage)
        fun checkpointRequest(
            entry: IndexedPkgEntry,
            strategy: ResourceStrategy,
            sourceSha256: String? = null,
        ): ConversionCheckpointRequest =
            ConversionCheckpointRequest(
                path = entry.path,
                sourceOffset = entry.offset,
                sourceLength = entry.length,
                sourceSha256 = sourceSha256 ?: digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation).toHex(),
                strategy = strategy,
            )
        fun checkpointProduct(request: ConversionCheckpointRequest, transformed: File): File {
            val checkpoint = resourceCheckpoint?.record(request, transformed, checkCancellation) ?: return transformed
            if (checkpoint != transformed) {
                check(transformed.delete() || !transformed.exists()) { "Unable to remove checkpointed conversion product" }
            }
            return checkpoint
        }
        val transformedDirectory = createConversionDirectory(outputFile)
        try {
            val entriesByPath = ConcurrentHashMap<String, MpkgInputEntry>(archive.entries.size)
            fun putEntry(entry: MpkgInputEntry) {
                entriesByPath[entry.path] = entry
            }
            fun fallbackToSource(
                entry: IndexedPkgEntry,
                transformed: File,
                code: String,
                workerCancellation: () -> Unit,
            ) {
                transformed.delete()
                digestFileSlice(scenePackage, entry.offset, entry.length, workerCancellation)
                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                copiedTextures.incrementAndGet()
                warn(code, entry.path)
                actualOutcomes[entry.path] =
                    ConversionResourceOutcome(entry.path, ResourceStrategy.PASSTHROUGH_WITH_WARNING, code)
            }
            val maxHeapBytes = plan?.device?.maxHeapBytes ?: Runtime.getRuntime().maxMemory()
            val memoryBudgetBytes = Math.multiplyExact(maxHeapBytes, 5L) / 8L
            val conversionPolicy = plan?.let { MpkgConversionPreset.fromStableId(it.presetId).policy() }
            val textureReduction = conversionPolicy?.textureReduction ?: 1
            val work =
                archive.entries.mapIndexed { index, entry ->
                    val plannedStrategy = plan?.resources?.getOrNull(index)?.strategy
                    val extension = entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val admissionStrategy =
                        plannedStrategy ?: if (extension == "tex") {
                            when (TexMobileConverter.inspectEntryRoute(scenePackage, entry.offset, entry.length, maxHeapBytes)) {
                                TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4
                                TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA,
                                TexEntryRoute.CONVERT_IN_MEMORY,
                                -> ResourceStrategy.CONVERT_TO_RGBA8888_LZ4
                                else -> null
                            }
                        } else if (extension == "frag" || extension == "vert") {
                            ResourceStrategy.REWRITE_SHADER
                        } else {
                            null
                        }
                    val estimatedPeakBytes =
                        if (
                            extension == "tex" &&
                            admissionStrategy in setOf(
                                ResourceStrategy.CONVERT_TO_RGBA8888_LZ4,
                                ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4,
                                ResourceStrategy.DOWNSAMPLE_TO_ETC2,
                            )
                        ) {
                            TexMobileConverter.estimateConversionPeakBytes(
                                scenePackage,
                                entry.offset,
                                entry.length,
                                requireNotNull(admissionStrategy),
                                textureReduction,
                            )
                        } else {
                            estimateNonTextureResourcePeakBytes(extension, entry.length, admissionStrategy)
                        }
                    val usesBoundedConverter = when (admissionStrategy) {
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4, ResourceStrategy.DOWNSAMPLE_TO_ETC2 -> true
                        ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 -> {
                            val chunkedRgba = conversionPolicy?.losslessVisuals == true && staticImageDecoder == null
                            chunkedRgba && TexMobileConverter.inspectEntryRoute(scenePackage, entry.offset, entry.length, maxHeapBytes) ==
                                TexEntryRoute.CONVERT_IMAGE_TO_ETC2
                        }
                        else -> false
                    }
                    val exclusiveBoundedPeakBytes =
                        if (estimatedPeakBytes > memoryBudgetBytes && extension == "tex" && usesBoundedConverter) {
                            TexMobileConverter.estimateExclusiveBoundedPeakBytes(
                                scenePackage,
                                entry.offset,
                                entry.length,
                                requireNotNull(admissionStrategy),
                                textureReduction,
                            )
                        } else {
                            null
                        }
                    val diagnosticDimensions =
                        if (estimatedPeakBytes > memoryBudgetBytes && extension == "tex") {
                            TexMobileConverter.inspectEntryDecision(scenePackage, entry.offset, entry.length, maxHeapBytes)
                        } else {
                            null
                        }
                    ResourceWork(
                        ordinal = index,
                        estimatedPeakBytes = estimatedPeakBytes,
                        payload = index to entry,
                        exclusiveBoundedPeakBytes = exclusiveBoundedPeakBytes,
                        budgetContext = ResourceBudgetContext(
                            relativePath = entry.path,
                            strategy = admissionStrategy,
                            sourceBytes = entry.length,
                            imageWidth = diagnosticDimensions?.imageWidth,
                            imageHeight = diagnosticDimensions?.imageHeight,
                        ),
                    )
                }
            val pipeline =
                MemoryWeightedResourcePipeline(
                    maxConcurrency =
                        if (
                            plan == null || animatedImageDecoder != null || staticImageDecoder != null ||
                            shaderRewriter != null || shaderRewriterWithPath != null
                        ) {
                            1
                        } else {
                            com.wallhub.android.BuildConfig.MPKG_RESOURCE_CONCURRENCY
                        },
                    memoryBudgetBytes = memoryBudgetBytes,
                )
            val completedPaths = pipeline.execute(work, checkCancellation) resource@{ item, workerCancellation ->
                val (index, entry) = item.payload
                val checkCancellation = workerCancellation
                timing.withCurrentIfEnabled {
                    checkCancellation()
                    val extension = entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in audioExtensions) {
                    val strategy = plan?.resources?.get(index)?.strategy ?: ResourceStrategy.REMOVE_AUDIO
                    when (strategy) {
                        ResourceStrategy.COPY ->
                            putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                        ResourceStrategy.REMOVE_AUDIO -> removedAudio.incrementAndGet()
                        else -> error("Planned strategy $strategy is invalid for audio ${entry.path}")
                    }
                    return@withCurrentIfEnabled entry.path
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
                        val sourceSha256 =
                            if (
                                strategy == ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 ||
                                strategy == ResourceStrategy.DOWNSAMPLE_TO_ETC2 ||
                                strategy == ResourceStrategy.CONVERT_TO_RGBA8888_LZ4
                            ) {
                                // Source reads are outside the recoverable transform boundary: a short read,
                                // invalid range or I/O failure must abort rather than masquerade as a codec failure.
                                digestFileSlice(scenePackage, entry.offset, entry.length, checkCancellation).toHex()
                            } else {
                                null
                            }
                        when (strategy) {
                            ResourceStrategy.RETAIN_VERIFIED_MOBILE -> {
                                copiedTextures.incrementAndGet()
                                if (route == TexEntryRoute.RETAIN_VERIFIED_VIDEO) {
                                    warn("validated_video_texture_retained", entry.path)
                                }
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                            }
                            ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP -> {
                                require(route == TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP) {
                                    "Planned runtime desktop retention is incompatible with ${entry.path}"
                                }
                                copiedTextures.incrementAndGet()
                                warn("verified_runtime_desktop_texture_retained", entry.path)
                                putEntry(MpkgInputEntry(entry.path, FileSlicePayload(scenePackage, entry.offset, entry.length)))
                            }
                            ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 -> {
                                require(route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2) { "Planned ETC2 strategy is incompatible with ${entry.path}" }
                                val transformed = File(transformedDirectory, "$index-etc2.tex")
                                val request = checkpointRequest(entry, strategy, sourceSha256)
                                val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedTexture)
                                val product =
                                    reused ?: run {
                                        val result = TexMobileConverter.convertToEtc2File(scenePackage, entry.offset, entry.length, transformed, checkCancellation)
                                        transformed.takeIf { result.converted && isVerifiedTexture(it) }
                                            ?.let { checkpointProduct(request, it) }
                                    }
                                if (product != null) {
                                    convertedTextures.incrementAndGet()
                                    putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                                } else {
                                    transformed.delete()
                                    error("Known visual texture could not be converted to a mobile ETC2 payload: ${entry.path}")
                                }
                            }
                            ResourceStrategy.DOWNSAMPLE_TO_ETC2 -> {
                                require(route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2) { "Planned downsample strategy is incompatible with ${entry.path}" }
                                val transformed = File(transformedDirectory, "$index-downsampled-etc2.tex")
                                val request = checkpointRequest(entry, strategy, sourceSha256)
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
                                            ?.let { checkpointProduct(request, it) }
                                    }
                                if (product != null) {
                                    convertedTextures.incrementAndGet()
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
                                copiedTextures.incrementAndGet()
                                if (strategy == ResourceStrategy.PASSTHROUGH_WITH_WARNING) {
                                    warn(plan?.resources?.get(index)?.reason ?: "tex_passthrough_unknown_profile", entry.path)
                                }
                            }
                            ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 -> {
                                val transformed = File(transformedDirectory, "$index-rgba.tex")
                                val request = checkpointRequest(entry, strategy, sourceSha256)
                                val reused = resourceCheckpoint?.reuse(request)?.takeIf(::isVerifiedTexture)
                                val product =
                                    reused ?: run {
                                        val result =
                                            when (route) {
                                                TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA ->
                                                    if (animatedImageDecoder == null) {
                                                        TexMobileConverter.convertAnimatedImageToRgbaFile(
                                                            sourceFile = scenePackage,
                                                            offset = entry.offset,
                                                            length = entry.length,
                                                            outputFile = transformed,
                                                            maxDecodedRgbaBytes = rgbaLimitForPreset(plan),
                                                            checkCancellation = checkCancellation,
                                                        )
                                                    } else {
                                                        TexMobileConverter.convertAnimatedImageToRgbaFile(
                                                            sourceFile = scenePackage,
                                                            offset = entry.offset,
                                                            length = entry.length,
                                                            outputFile = transformed,
                                                            maxDecodedRgbaBytes = rgbaLimitForPreset(plan),
                                                            checkCancellation = checkCancellation,
                                                            decodeImage = animatedImageDecoder,
                                                        )
                                                    }
                                                TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> {
                                                    val limit = rgbaLimitForPreset(plan)
                                                    if (MpkgConversionPreset.fromStableId(requireNotNull(plan).presetId).policy().losslessVisuals && staticImageDecoder == null) {
                                                        TexMobileConverter.convertImageToRgbaChunkedFile(
                                                            sourceFile = scenePackage,
                                                            offset = entry.offset,
                                                            length = entry.length,
                                                            outputFile = transformed,
                                                            maxDecodedRgbaBytes = limit,
                                                            checkCancellation = checkCancellation,
                                                        )
                                                    } else if (staticImageDecoder == null) {
                                                        TexMobileConverter.convertImageToRgbaFile(
                                                            sourceFile = scenePackage,
                                                            offset = entry.offset,
                                                            length = entry.length,
                                                            outputFile = transformed,
                                                            maxDecodedRgbaBytes = limit,
                                                            textureReduction = MpkgConversionPreset.fromStableId(requireNotNull(plan).presetId).policy().textureReduction,
                                                            checkCancellation = checkCancellation,
                                                        )
                                                    } else {
                                                        TexMobileConverter.convertImageToRgbaFile(
                                                            sourceFile = scenePackage,
                                                            offset = entry.offset,
                                                            length = entry.length,
                                                            outputFile = transformed,
                                                            maxDecodedRgbaBytes = limit,
                                                            textureReduction = MpkgConversionPreset.fromStableId(requireNotNull(plan).presetId).policy().textureReduction,
                                                            checkCancellation = checkCancellation,
                                                            decodeImage = staticImageDecoder,
                                                        )
                                                    }
                                                }
                                                TexEntryRoute.CONVERT_IN_MEMORY ->
                                                    TexMobileConverter.convertStaticToRgbaFile(
                                                        sourceFile = scenePackage,
                                                        offset = entry.offset,
                                                        length = entry.length,
                                                        outputFile = transformed,
                                                        maxDecodedRgbaBytes = WORKSHOP_STANDARD_MAX_RGBA_BYTES,
                                                        checkCancellation = checkCancellation,
                                                    )
                                                else -> error("Planned RGBA strategy is incompatible with ${entry.path}")
                                            }
                                        transformed.takeIf { result.converted && isVerifiedTexture(it) }
                                            ?.let { checkpointProduct(request, it) }
                                    }
                                if (product != null) {
                                    convertedTextures.incrementAndGet()
                                    putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                                } else {
                                    fallbackToSource(entry, transformed, "texture_conversion_failed", checkCancellation)
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
                            if (!reused.readBytes().contentEquals(originalBytes)) rewrittenShaders.incrementAndGet()
                            putEntry(MpkgInputEntry(entry.path, FilePayload(reused)))
                        } else {
                            checkCancellation()
                            val rewritten =
                                try {
                                    shaderRewriterWithPath?.invoke(entry.path, original, checkCancellation)
                                        ?: shaderRewriter?.invoke(original, checkCancellation)
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
                                if (rewritten != original) rewrittenShaders.incrementAndGet()
                                val transformed = File(transformedDirectory, "$index.$extension")
                                transformed.writeText(rewritten, Charsets.UTF_8)
                                checkCancellation()
                                require(isVerifiedShader(transformed)) { "Rewritten shader verification failed: ${entry.path}" }
                                val product = checkpointProduct(request, transformed)
                                putEntry(MpkgInputEntry(entry.path, FilePayload(product)))
                            }
                        }
                    }
                    else -> {
                        if (entry.path.equals("scene.json", ignoreCase = true) && plan != null) {
                            val planned = plan.resources.getOrNull(index)
                            require(planned?.path == entry.path && planned.sourceLength == entry.length) {
                                "Conversion plan resource order does not match scene package"
                            }
                            require(planned.strategy == ResourceStrategy.REWRITE_SCENE_SETTINGS) {
                                "Scene settings require an explicit rewrite strategy"
                            }
                            val transformed = File(transformedDirectory, "$index-scene.json")
                            val reduction = MpkgConversionPreset.fromStableId(plan.presetId).policy().textureReduction
                            StreamingSceneJson.rewriteTextureReduction(
                                source = scenePackage,
                                entry = entry,
                                textureReduction = reduction,
                                output = transformed,
                                checkCancellation = checkCancellation,
                            )
                            putEntry(MpkgInputEntry(entry.path, FilePayload(transformed)))
                            actualOutcomes[entry.path] =
                                ConversionResourceOutcome(entry.path, ResourceStrategy.REWRITE_SCENE_SETTINGS, planned.reason)
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
                    entry.path
                }
            }
            require(completedPaths == archive.entries.map(IndexedPkgEntry::path)) {
                "Resource pipeline did not preserve archive ordinal order"
            }
            if (presetDependency != null) {
                presetResolution.presetRoots.asReversed().forEach { presetRoot ->
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
                    ByteArrayPayload(mergePresetProject(sceneRoot, presetResolution.presetRoots, preview.name))
                }
            putEntry(MpkgInputEntry("project.json", outputProject))
            putEntry(MpkgInputEntry(preview.name, FilePayload(preview)))
            // Verification is part of the commit boundary: a malformed candidate must never replace an
            // existing good package or remain visible as the requested output.
            writeAtomically(outputFile) { candidate ->
                fun writeCandidate() {
                    timing.measureIfEnabled(MpkgPhase.PACKAGE) {
                        MpkgWriter.write(entriesByPath.values.toList(), candidate, SCENE_MPKG_MAGIC, checkCancellation)
                    }
                    checkCancellation()
                }
                fun failedConvertedTextures(): List<IndexedPkgEntry> =
                    timing.measureIfEnabled(MpkgPhase.VERIFY) {
                        val verifiedOutput = PkgReader.readIndex(candidate)
                        verifiedOutput.entries.filter { outputEntry ->
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
                val failedOutputTextures = failedConvertedTextures()
                failedOutputTextures.forEach { failedOutput ->
                    val sourceEntry = archive.entries.single { it.path == failedOutput.path }
                    entriesByPath[failedOutput.path] =
                        MpkgInputEntry(failedOutput.path, FileSlicePayload(scenePackage, sourceEntry.offset, sourceEntry.length))
                    convertedTextures.decrementAndGet()
                    copiedTextures.incrementAndGet()
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
            convertedTextures = convertedTextures.get(),
            copiedTextures = copiedTextures.get(),
            removedAudio = removedAudio.get(),
            rewrittenShaders = rewrittenShaders.get(),
            warnings = boundedWarningDetails.map { "${it.code}: ${it.path}" },
            warningDetails = boundedWarningDetails,
            warningCounts = warningCounts,
            outcomes = archive.entries.mapNotNull { entry -> actualOutcomes[entry.path] },
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

    internal fun resolveCurrentScenePackage(root: File): File {
        val sceneRoot = PresetDependencyResolver.resolve(root).getOrThrow()?.sceneRoot ?: root
        val project = readProject(sceneRoot)
        return resolveScenePackage(sceneRoot, project.string("file"))
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
        Regex("""(?<![A-Za-z0-9_])(?:uniform\s+|attribute\s+|varying\s+)?vec[234]\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val typedVectorAssignment =
        Regex("""(?<![A-Za-z0-9_])vec[234]\s+([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)""")
    private val vectorComponentCompoundAssignment =
        Regex("""(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)\.[A-Za-z_][A-Za-z0-9_]*\s*[+\-*/]=(?!=)""")
    private val floatDeclaration =
        Regex("""(?:uniform\s+|attribute\s+|varying\s+|const\s+)?float\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val floatFunctionDeclaration =
        Regex("""\bfloat\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^;]*\)\s*\{""")
    private val floatLiteral = Regex("""\d+\.\d*|\.\d+""")
    private val integerLiteral = Regex("""\d+""")
    private val mixCall = Regex("""(?<![A-Za-z0-9_])mix\(""")
    private val scalarAssignment =
        Regex("""(?:\bfloat\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)""")
    private val floatReturn = Regex("""(\breturn\s+)([^;]+)""")
    private val packedAudioSpectrumIndex =
        Regex(
            """(g_AudioSpectrum64(?:Left|Right))\[([A-Za-z_][A-Za-z0-9_]*)\s*/\s*4\]\[\2\s*%\s*4\]""",
        )
    private val FLOAT_VECTOR_CONSTRUCTORS = setOf("vec2", "vec3", "vec4", "mat2", "mat3", "mat4")
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
        var output = replaceShaderCode(source, Regex("\\bsample\\b"), checkCancellation) { "_sample" }
        mapOf(
            Regex("(?<![A-Za-z0-9_])vec4\\(1, 0, 0, 1\\)") to "vec4(1.0, 0.0, 0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(1, 0\\)") to "vec2(1.0, 0.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 1\\)") to "vec2(0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, -0\\.5\\)") to "vec2(0.0, -0.5)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 0\\.5\\)") to "vec2(0.0, 0.5)",
        ).forEach { (old, new) ->
            output = replaceShaderCode(output, old, checkCancellation) { new }
        }
        mapOf(
            "CAST3(0)" to "CAST3(0.0)",
            "mix(blurred.a, 1, step(blurred.a, 0))" to "mix(blurred.a, 1.0, step(blurred.a, 0.0))",
            "2 * abs(" to "2.0 * abs(",
            "30 / 50.0" to "30.0 / 50.0",
            "30 / 4.0" to "30.0 / 4.0",
            "30 / 8.0" to "30.0 / 8.0",
            "30 / 15.0" to "30.0 / 15.0",
        ).forEach { (old, new) ->
            output = replaceShaderCode(output, Regex(Regex.escape(old)), checkCancellation) { new }
        }
        checkCancellation()
        output = rewriteVectorIntegerArithmetic(output, checkCancellation)
        checkCancellation()
        output = rewriteScalarFloatContexts(output)
        checkCancellation()
        output =
            rewriteMixArguments(
                source = output,
                checkCancellation = checkCancellation,
                scanBudget = ShaderScanBudget(output.length.toLong() * MAX_SHADER_MIX_SCAN_MULTIPLIER),
            )
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

    private fun replaceShaderCode(
        source: String,
        pattern: Regex,
        checkCancellation: () -> Unit,
        replacement: (MatchResult) -> String,
    ): String {
        val codeMask = maskShaderCommentsAndStrings(source, checkCancellation)
        return pattern.replace(source) { match ->
            checkCancellation()
            if (codeMask.regionMatches(match.range.first, match.value, 0, match.value.length)) {
                replacement(match)
            } else {
                match.value
            }
        }
    }

    private fun rewriteVectorIntegerArithmetic(
        source: String,
        checkCancellation: () -> Unit,
    ): String {
        val codeMask = maskShaderCommentsAndStrings(source, checkCancellation)
        val vectorNames = mutableSetOf<String>()
        vectorDeclaration.findAll(codeMask).forEach { match ->
            checkCancellation()
            vectorNames += match.groupValues[1]
        }
        if (vectorNames.isEmpty()) return source

        val assignments =
            sequenceOf(
                typedVectorAssignment.findAll(codeMask).map { match -> match to true },
                vectorComponentCompoundAssignment.findAll(codeMask).map { match -> match to false },
            ).flatten().sortedBy { (match, _) -> match.range.first }
        val output = StringBuilder(source.length)
        var cursor = 0
        assignments.forEach { (assignment, typedDeclaration) ->
            checkCancellation()
            if (assignment.range.first < cursor) return@forEach
            val vectorName = assignment.groupValues[1]
            if (!typedDeclaration && vectorName !in vectorNames) return@forEach
            val rhsStart = assignment.range.last + 1
            val rhsEnd = findShaderStatementEnd(codeMask, rhsStart, checkCancellation)
            output.append(source, cursor, rhsStart)
            output.append(
                promoteIntegerLiteralsInFloatExpression(
                    expression = source.substring(rhsStart, rhsEnd),
                    codeMask = codeMask.substring(rhsStart, rhsEnd),
                    checkCancellation = checkCancellation,
                ),
            )
            cursor = rhsEnd
        }
        if (cursor == 0) return source
        output.append(source, cursor, source.length)
        return output.toString()
    }

    private fun findShaderStatementEnd(
        codeMask: String,
        start: Int,
        checkCancellation: () -> Unit,
    ): Int {
        var parenthesisDepth = 0
        var bracketDepth = 0
        for (index in start until codeMask.length) {
            if ((index - start) % SHADER_CANCELLATION_POLL_CHARS == 0) checkCancellation()
            when (codeMask[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth += 1
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                ';' -> if (parenthesisDepth == 0 && bracketDepth == 0) return index
            }
        }
        return codeMask.length
    }

    private fun maskShaderCommentsAndStrings(
        source: String,
        checkCancellation: () -> Unit,
    ): String {
        val mask = source.toCharArray()
        var index = 0
        var state = ShaderMaskState.CODE
        while (index < source.length) {
            if (index % SHADER_CANCELLATION_POLL_CHARS == 0) checkCancellation()
            when (state) {
                ShaderMaskState.CODE ->
                    when {
                        source.startsWith("//", index) -> {
                            mask[index] = ' '
                            mask[index + 1] = ' '
                            index += 2
                            state = ShaderMaskState.LINE_COMMENT
                        }
                        source.startsWith("/*", index) -> {
                            mask[index] = ' '
                            mask[index + 1] = ' '
                            index += 2
                            state = ShaderMaskState.BLOCK_COMMENT
                        }
                        source[index] == '"' -> {
                            mask[index] = ' '
                            index += 1
                            state = ShaderMaskState.STRING
                        }
                        else -> index += 1
                    }
                ShaderMaskState.LINE_COMMENT -> {
                    if (source[index] == '\n' || source[index] == '\r') {
                        state = ShaderMaskState.CODE
                    } else {
                        mask[index] = ' '
                    }
                    index += 1
                }
                ShaderMaskState.BLOCK_COMMENT ->
                    if (source.startsWith("*/", index)) {
                        mask[index] = ' '
                        mask[index + 1] = ' '
                        index += 2
                        state = ShaderMaskState.CODE
                    } else {
                        if (source[index] != '\n' && source[index] != '\r') mask[index] = ' '
                        index += 1
                    }
                ShaderMaskState.STRING ->
                    when {
                        source[index] == '\\' && index + 1 < source.length -> {
                            mask[index] = ' '
                            mask[index + 1] = ' '
                            index += 2
                        }
                        source[index] == '"' -> {
                            mask[index] = ' '
                            index += 1
                            state = ShaderMaskState.CODE
                        }
                        else -> {
                            if (source[index] != '\n' && source[index] != '\r') mask[index] = ' '
                            index += 1
                        }
                    }
            }
        }
        return mask.concatToString()
    }

    private enum class ShaderMaskState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
    }

    /**
     * Mobile GLSL is strict about int-to-float coercion. Desktop Workshop shaders commonly rely on literals
     * such as `float x = 0`, `x = max(0, y)`, and `return 1 - y`; the official mobile exporter promotes those
     * literals while retaining actual integer control flow and array indices. This bounded pass does the same
     * only when the assignment target or function return type is known to be float.
     */
    private fun rewriteScalarFloatContexts(source: String): String {
        val floatNames = floatDeclaration.findAll(source).map { it.groupValues[1] }.toSet()
        if (floatNames.isEmpty() && !floatFunctionDeclaration.containsMatchIn(source)) return source
        var braceDepth = 0
        var floatFunctionDepth: Int? = null
        return rewriteShaderLinesPreservingSeparators(source) { line ->
            val code = line.substringBefore("//")
            val comment = line.substring(code.length)
            val startsFloatFunction = floatFunctionDeclaration.containsMatchIn(code)
            val inFloatFunction = startsFloatFunction || floatFunctionDepth?.let { braceDepth >= it } == true
            val assignment = scalarAssignment.find(code)
            val rewrittenCode =
                when {
                    code.trimStart().startsWith("#") -> code
                    assignment != null && assignment.groupValues[1] in floatNames -> {
                        val rhsStart = assignment.range.last + 1
                        code.substring(0, rhsStart) + promoteIntegerLiteralsInFloatExpression(code.substring(rhsStart))
                    }
                    inFloatFunction ->
                        floatReturn.replace(code) { match ->
                            match.groupValues[1] + promoteIntegerLiteralsInFloatExpression(match.groupValues[2])
                        }
                    else -> code
                }
            val opens = code.count { it == '{' }
            val closes = code.count { it == '}' }
            if (startsFloatFunction && opens > 0) floatFunctionDepth = braceDepth + 1
            braceDepth += opens - closes
            if (floatFunctionDepth?.let { braceDepth < it } == true) floatFunctionDepth = null
            rewrittenCode + comment
        }
    }

    private inline fun rewriteShaderLinesPreservingSeparators(
        source: String,
        transform: (String) -> String,
    ): String {
        val output = StringBuilder(source.length)
        var start = 0
        while (start < source.length) {
            var end = start
            while (end < source.length && source[end] != '\r' && source[end] != '\n') end += 1
            output.append(transform(source.substring(start, end)))
            if (end < source.length) {
                output.append(source[end])
                end += 1
                if (end < source.length && source[end - 1] == '\r' && source[end] == '\n') {
                    output.append(source[end])
                    end += 1
                }
            }
            start = end
        }
        return output.toString()
    }

    private fun promoteIntegerLiteralsInFloatExpression(
        expression: String,
        codeMask: String = expression,
        checkCancellation: () -> Unit = {},
    ): String {
        require(expression.length == codeMask.length) { "Shader expression mask length changed" }
        if (
            codeMask.any { it in "&|%" } ||
            "<<" in codeMask ||
            ">>" in codeMask ||
            "==" in codeMask ||
            "!=" in codeMask ||
            "<=" in codeMask ||
            ">=" in codeMask
        ) {
            return expression
        }
        val output = StringBuilder(expression.length)
        val floatContextStack = mutableListOf<Boolean>()
        var index = 0
        var bracketDepth = 0
        while (index < expression.length) {
            if (index % SHADER_CANCELLATION_POLL_CHARS == 0) checkCancellation()
            when {
                codeMask[index] == '(' -> {
                    val functionName = shaderIdentifierBefore(codeMask, index)
                    val inheritedFloatContext = floatContextStack.lastOrNull() ?: true
                    floatContextStack +=
                        if (functionName == null) {
                            inheritedFloatContext
                        } else {
                            functionName in FLOAT_VECTOR_CONSTRUCTORS
                        }
                    output.append(expression[index++])
                }
                codeMask[index] == ')' -> {
                    if (floatContextStack.isNotEmpty()) floatContextStack.removeAt(floatContextStack.lastIndex)
                    output.append(expression[index++])
                }
                codeMask[index] == '[' -> {
                    bracketDepth += 1
                    output.append(expression[index++])
                }
                codeMask[index] == ']' -> {
                    bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    output.append(expression[index++])
                }
                codeMask[index].isDigit() -> {
                    val start = index
                    index = numericLiteralEnd(codeMask, start)
                    val token = expression.substring(start, index)
                    val isFloatingLiteral = token.any { it == '.' || it == 'e' || it == 'E' }
                    val isDecimalPart = start > 0 && codeMask[start - 1] == '.'
                    val touchesIdentifier =
                        (start > 0 && (codeMask[start - 1].isLetterOrDigit() || codeMask[start - 1] == '_')) ||
                            (index < codeMask.length && (codeMask[index].isLetterOrDigit() || codeMask[index] == '_'))
                    val inFloatContext = floatContextStack.lastOrNull() ?: true
                    output.append(
                        if (
                            bracketDepth == 0 &&
                            inFloatContext &&
                            !isFloatingLiteral &&
                            !isDecimalPart &&
                            !touchesIdentifier
                        ) {
                            "$token.0"
                        } else {
                            token
                        },
                    )
                }
                else -> output.append(expression[index++])
            }
        }
        return output.toString()
    }

    private fun numericLiteralEnd(
        source: String,
        start: Int,
    ): Int {
        var index = start
        while (index < source.length && source[index].isDigit()) index += 1
        if (index < source.length && source[index] == '.') {
            index += 1
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (index < source.length && source[index] in "eE") {
            val exponent = index
            index += 1
            if (index < source.length && source[index] in "+-") index += 1
            val exponentDigits = index
            while (index < source.length && source[index].isDigit()) index += 1
            if (index == exponentDigits) index = exponent
        }
        return index
    }

    private fun shaderIdentifierBefore(
        source: String,
        endExclusive: Int,
    ): String? {
        var end = endExclusive
        while (end > 0 && source[end - 1].isWhitespace()) end -= 1
        var start = end
        while (start > 0 && (source[start - 1].isLetterOrDigit() || source[start - 1] == '_')) start -= 1
        if (start == end || !(source[start].isLetter() || source[start] == '_')) return null
        return source.substring(start, end)
    }

    private fun rewriteMixArguments(
        source: String,
        checkCancellation: () -> Unit = {},
        depth: Int = 0,
        scanBudget: ShaderScanBudget =
            ShaderScanBudget(source.length.toLong() * MAX_SHADER_MIX_SCAN_MULTIPLIER),
    ): String {
        require(depth <= MAX_SHADER_NESTED_CALL_DEPTH) { "Shader mix nesting exceeds limit" }
        checkCancellation()
        val calls = mixCall.findAll(source).toList()
        if (calls.isEmpty()) return source
        val output = StringBuilder(source.length)
        var cursor = 0
        calls.forEach { call ->
            checkCancellation()
            if (call.range.first < cursor) return@forEach
            val openParenthesis = call.range.last
            val closeParenthesis = matchingParenthesis(source, openParenthesis, checkCancellation, scanBudget)
            if (closeParenthesis < 0) return@forEach
            output.append(source, cursor, openParenthesis + 1)
            output.append(
                normalizeMixArguments(
                    source.substring(openParenthesis + 1, closeParenthesis),
                    checkCancellation,
                    depth + 1,
                    scanBudget,
                ),
            )
            cursor = closeParenthesis
        }
        output.append(source, cursor, source.length)
        return output.toString()
    }

    private fun normalizeMixArguments(
        body: String,
        checkCancellation: () -> Unit,
        depth: Int,
        scanBudget: ShaderScanBudget,
    ): String {
        val normalizedBody = rewriteMixArguments(body, checkCancellation, depth, scanBudget)
        if (!floatLiteral.containsMatchIn(normalizedBody)) return normalizedBody
        val arguments = splitTopLevelArguments(normalizedBody, checkCancellation)
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
        checkCancellation: () -> Unit,
        scanBudget: ShaderScanBudget,
    ): Int {
        var depth = 0
        for (position in openParenthesis until source.length) {
            scanBudget.consume()
            if ((position - openParenthesis) % SHADER_CANCELLATION_POLL_CHARS == 0) checkCancellation()
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

    private class ShaderScanBudget(
        private var remaining: Long,
    ) {
        fun consume() {
            require(remaining-- > 0L) { "Shader compatibility scan budget exceeded" }
        }
    }

    private fun splitTopLevelArguments(
        body: String,
        checkCancellation: () -> Unit,
    ): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        body.forEachIndexed { index, character ->
            if (index % SHADER_CANCELLATION_POLL_CHARS == 0) checkCancellation()
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

private const val WORKSHOP_STANDARD_MAX_RGBA_BYTES = 64L * 1024L * 1024L
private const val EXTREME_MAX_RGBA_BYTES = 256L * 1024L * 1024L

private fun rgbaLimitForPreset(plan: ConversionPlan?): Long =
    if (plan != null && MpkgConversionPreset.fromStableId(plan.presetId).policy().losslessVisuals) {
        EXTREME_MAX_RGBA_BYTES
    } else {
        WORKSHOP_STANDARD_MAX_RGBA_BYTES
    }

internal const val MAX_SHADER_SOURCE_BYTES = 8L * 1024L * 1024L
private const val TEXT_RESOURCE_FIXED_OVERHEAD_BYTES = 1024L * 1024L

internal fun estimateNonTextureResourcePeakBytes(
    extension: String,
    sourceLength: Long,
    strategy: ResourceStrategy?,
): Long =
    runCatching {
        require(sourceLength >= 0L)
        when {
            extension in setOf("frag", "vert") && strategy == ResourceStrategy.REWRITE_SHADER ->
                Math.addExact(Math.multiplyExact(sourceLength, 6L), TEXT_RESOURCE_FIXED_OVERHEAD_BYTES)
            extension == "json" && strategy == ResourceStrategy.REWRITE_SCENE_SETTINGS ->
                Math.addExact(Math.multiplyExact(sourceLength, 4L), TEXT_RESOURCE_FIXED_OVERHEAD_BYTES)
            else -> MIN_RESOURCE_PIPELINE_WEIGHT_BYTES
        }
    }.getOrDefault(Long.MAX_VALUE)
private const val SHADER_CANCELLATION_POLL_CHARS = 4096
private const val MAX_SHADER_NESTED_CALL_DEPTH = 16
private const val MAX_SHADER_MIX_SCAN_MULTIPLIER = 32L
private const val COPY_BUFFER_SIZE = 1024 * 1024
private const val MAX_WARNING_DETAILS = 100
