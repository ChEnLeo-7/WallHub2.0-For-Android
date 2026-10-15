package com.wallhub.prototype.mpkg

import com.wallhub.android.core.model.MpkgConversionPreset
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

fun MpkgConversionPreset.policy(): ConversionPolicy =
    when (this) {
        MpkgConversionPreset.BALANCED -> ConversionPolicy(stableId, 8L * MIB, "decoded_rgba_up_to_8mib_else_etc2")
        MpkgConversionPreset.COMPATIBILITY -> ConversionPolicy(stableId, 0L, "eligible_color_textures_etc2")
        MpkgConversionPreset.HIGH_QUALITY -> ConversionPolicy(stableId, 32L * MIB, "decoded_rgba_up_to_32mib_else_etc2")
        MpkgConversionPreset.SOURCE_FIDELITY -> ConversionPolicy(stableId, Long.MAX_VALUE, "all_tex_passthrough")
    }

data class ConversionPolicy(
    val presetId: String,
    val maxStaticImageRgbaBytes: Long,
    val retentionNotice: String,
    val version: Int = 2,
) {
    fun stableDescription(): String =
        "$presetId|v$version|static=$retentionNotice|unknown=passthrough_with_warning|downsample=never"
}

data class DeviceCapabilityProfile(
    val maxHeapBytes: Long,
    val supportsEtc2: Boolean = true,
) {
    init {
        require(maxHeapBytes > 0) { "Device max heap must be positive" }
    }
}

enum class ResourceStrategy {
    COPY,
    RETAIN_VERIFIED_MOBILE,
    RETAIN_VERIFIED_RUNTIME_DESKTOP,
    CONVERT_TO_ETC2_RGBA8_LZ4,
    DOWNSAMPLE_TO_ETC2,
    CONVERT_TO_RGBA8888_LZ4,
    REWRITE_SHADER,
    REMOVE_AUDIO,
    PASSTHROUGH,
    PASSTHROUGH_WITH_WARNING,
    REJECT,
}

data class ScenePackageIdentity(
    val version: Int = 1,
    val packageLength: Long,
    val indexDigest: String,
) {
    init {
        require(version == 1) { "Unsupported source identity version: $version" }
        require(packageLength >= 0) { "Negative scene package length" }
        require(indexDigest.matches(Regex("[0-9a-f]{64}"))) { "Invalid scene package index digest" }
    }
}

data class ConversionResourcePlan(
    val path: String,
    val sourceLength: Long,
    val strategy: ResourceStrategy,
    val reason: String,
) {
    init {
        require(path.toByteArray(Charsets.UTF_8).size <= MAX_PLAN_PATH_BYTES) { "Conversion resource path is too long" }
        require(!path.isAbsolutePlanPath()) { "Conversion resource path must be relative" }
        require(MpkgWriter.normalizePath(path) == path) { "Conversion resource path is not normalized" }
        require(sourceLength >= 0) { "Conversion resource length is negative" }
        require(reason.length <= MAX_REASON_CHARS) { "Conversion resource reason is too long" }
    }
}

data class ConversionPlan(
    val version: Int = 2,
    val presetId: String,
    val policyVersion: Int,
    val preserveAudio: Boolean = false,
    val device: DeviceCapabilityProfile,
    val sourceIdentity: ScenePackageIdentity,
    val resources: List<ConversionResourcePlan>,
) {
    init {
        require(version == 2) { "Unsupported conversion plan version: $version" }
        require(policyVersion == 2) { "Unsupported conversion policy version: $policyVersion" }
        require(MpkgConversionPreset.fromStableId(presetId).stableId == presetId) { "Unknown conversion preset: $presetId" }
        val paths = HashSet<String>()
        resources.forEach { resource ->
            require(paths.add(resource.path)) { "Conversion plan contains duplicate resource paths" }
        }
    }
}

data class ConversionResourceOutcome(
    val path: String,
    val strategy: ResourceStrategy,
    val reason: String,
)

class StaleConversionSourceException(
    val reason: String,
) : IllegalStateException(reason)

data class PersistedConversionWarningDetail(
    val path: String,
    val code: String,
    val strategy: String?,
)

data class PersistedConversionReport(
    val convertedTextures: Int,
    val copiedTextures: Int,
    val removedAudio: Int,
    val rewrittenShaders: Int,
    val warningTotal: Int,
    val warningCounts: Map<String, Int>,
    val warningDetails: List<PersistedConversionWarningDetail>,
)

object ConversionReportJsonCodec {
    const val MAX_JSON_BYTES = 128 * 1024

    fun decodeSummary(json: String): PersistedConversionReport? =
        runCatching {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
            val root = JSONObject(json)
            require(root.getInt("version") == 2)
            val countsObject = root.optJSONObject("warningCounts") ?: JSONObject()
            val counts =
                countsObject.keys().asSequence().map { code ->
                    require(code.isSafeWarningToken())
                    code to countsObject.getInt(code).also { require(it >= 0) }
                }.toMap().toSortedMap()
            val detailsArray = root.optJSONArray("warningDetails") ?: JSONArray()
            require(detailsArray.length() <= MAX_WARNING_DETAILS)
            val details =
                (0 until detailsArray.length()).map { index ->
                    val detail = detailsArray.getJSONObject(index)
                    val path = detail.getString("path").safeRelativeWarningPath()
                    val code = detail.getString("code").also { require(it.isSafeWarningToken()) }
                    val strategy =
                        detail.optString("strategy").takeIf(String::isNotBlank)?.also {
                            require(it.isSafeWarningToken())
                        }
                    PersistedConversionWarningDetail(path = path, code = code, strategy = strategy)
                }.distinct().sortedWith(compareBy(PersistedConversionWarningDetail::path).thenBy(PersistedConversionWarningDetail::code))
            val summarizedCounts =
                counts.ifEmpty { details.groupingBy(PersistedConversionWarningDetail::code).eachCount().toSortedMap() }
            val warningTotal = root.optInt("warningTotal", root.optInt("warningCount", summarizedCounts.values.sum()))
            require(warningTotal >= 0)
            PersistedConversionReport(
                convertedTextures = root.optInt("convertedTextures").also { require(it >= 0) },
                copiedTextures = root.optInt("copiedTextures").also { require(it >= 0) },
                removedAudio = root.optInt("removedAudio").also { require(it >= 0) },
                rewrittenShaders = root.optInt("rewrittenShaders").also { require(it >= 0) },
                warningTotal = warningTotal,
                warningCounts = summarizedCounts,
                warningDetails = details,
            )
        }.getOrNull()

    /** Durable reports are summaries; resource outcomes remain process-local and warning detail is bounded. */
    fun encode(report: WorkshopConversionReport): String {
        val allDetails = report.warningDetails.distinct()
        val details =
            allDetails
                .sortedWith(compareBy(ConversionWarningDetail::path).thenBy(ConversionWarningDetail::code))
                .take(MAX_WARNING_DETAILS)
        val counts =
            if (report.warningCounts.isNotEmpty()) {
                report.warningCounts.toSortedMap()
            } else {
                report.warningDetails.groupingBy(ConversionWarningDetail::code).eachCount().toSortedMap()
            }
        val strategiesByPath = report.outcomes.associate { it.path to it.strategy.name.lowercase() }
        var persistedDetails = details
        while (true) {
            val encoded =
                JSONObject()
                    .put("version", 2)
                    .put("planVersion", report.planVersion ?: 1)
                    .put("presetId", report.presetId ?: MpkgConversionPreset.BALANCED.stableId)
                    .put("kind", report.kind.name)
                    .put("outputBytes", report.outputFile.length())
                    .put("resourceCount", report.outcomes.size)
                    .put("convertedTextures", report.convertedTextures)
                    .put("copiedTextures", report.copiedTextures)
                    .put("removedAudio", report.removedAudio)
                    .put("rewrittenShaders", report.rewrittenShaders)
                    .put("warningCount", counts.values.sum().takeIf { it > 0 } ?: report.warnings.size)
                    .put("warningTotal", counts.values.sum().takeIf { it > 0 } ?: report.warnings.size)
                    .put(
                        "warningDetails",
                        JSONArray().also { array ->
                            persistedDetails.forEach { detail ->
                                array.put(
                                    JSONObject()
                                        .put("code", detail.code)
                                        .put("path", detail.path)
                                        .also { json -> strategiesByPath[detail.path]?.let { json.put("strategy", it) } },
                                )
                            }
                        },
                    ).put(
                        "warningCounts",
                        JSONObject().also { summary -> counts.forEach { (code, count) -> summary.put(code, count) } },
                    ).toString()
            if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) return encoded
            require(persistedDetails.isNotEmpty()) { "Persisted conversion report summary exceeds byte limit" }
            persistedDetails = persistedDetails.dropLast(1)
        }
    }

    private const val MAX_WARNING_DETAILS = 100
}

private fun String.isSafeWarningToken(): Boolean =
    isNotBlank() && length <= 80 && all { it.isLetterOrDigit() || it == '_' || it == '-' }

private fun String.safeRelativeWarningPath(): String {
    val normalized = replace('\\', '/').trim()
    require(normalized.isNotBlank() && normalized.length <= 1024)
    require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:/").containsMatchIn(normalized))
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." })
    require(normalized.none { it.code < 0x20 || it == '\u007f' })
    return normalized
}

object ConversionPlanJsonCodec {
    fun encode(plan: ConversionPlan): String {
        val root = JSONObject()
        root.put("version", plan.version)
        root.put("presetId", plan.presetId)
        root.put("policyVersion", plan.policyVersion)
        root.put("preserveAudio", plan.preserveAudio)
        root.put(
            "device",
            JSONObject()
                .put("maxHeapBytes", plan.device.maxHeapBytes)
                .put("supportsEtc2", plan.device.supportsEtc2),
        )
        root.put(
            "sourceIdentity",
            JSONObject()
                .put("version", plan.sourceIdentity.version)
                .put("packageLength", plan.sourceIdentity.packageLength)
                .put("indexDigest", plan.sourceIdentity.indexDigest),
        )
        root.put(
            "resources",
            JSONArray().also { resources ->
                plan.resources.forEach { resource ->
                    resources.put(
                        JSONObject()
                            .put("path", resource.path)
                            .put("sourceLength", resource.sourceLength)
                            .put("strategy", resource.strategy.name)
                            .put("reason", resource.reason),
                    )
                }
            },
        )
        return root.toString()
    }

    fun decode(json: String): ConversionPlan {
        val root = JSONObject(json)
        requireKeys(root, setOf("version", "presetId", "policyVersion", "preserveAudio", "device", "sourceIdentity", "resources"))
        val deviceJson = root.getJSONObject("device")
        requireKeys(deviceJson, setOf("maxHeapBytes", "supportsEtc2"))
        val identityJson = root.getJSONObject("sourceIdentity")
        requireKeys(identityJson, setOf("version", "packageLength", "indexDigest"))
        val resourcesJson = root.getJSONArray("resources")
        val resources =
            List(resourcesJson.length()) { index ->
                val resource = resourcesJson.getJSONObject(index)
                requireKeys(resource, setOf("path", "sourceLength", "strategy", "reason"))
                val path = resource.getString("path")
                require(path.toByteArray(Charsets.UTF_8).size <= MAX_PLAN_PATH_BYTES) { "Conversion resource path is too long" }
                ConversionResourcePlan(
                    path = MpkgWriter.normalizePath(path),
                    sourceLength = resource.getLong("sourceLength").also { require(it >= 0) },
                    strategy = ResourceStrategy.valueOf(resource.getString("strategy")),
                    reason = resource.getString("reason").also { require(it.length <= MAX_REASON_CHARS) },
                )
            }
        val presetId = root.getString("presetId")
        require(MpkgConversionPreset.fromStableId(presetId).stableId == presetId) { "Unknown conversion preset: $presetId" }
        return ConversionPlan(
            version = root.getInt("version"),
            presetId = presetId,
            policyVersion = root.getInt("policyVersion"),
            preserveAudio = root.getBoolean("preserveAudio"),
            device = DeviceCapabilityProfile(deviceJson.getLong("maxHeapBytes"), deviceJson.getBoolean("supportsEtc2")),
            sourceIdentity =
                ScenePackageIdentity(
                    version = identityJson.getInt("version"),
                    packageLength = identityJson.getLong("packageLength"),
                    indexDigest = identityJson.getString("indexDigest"),
                ),
            resources = resources,
        )
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) { "Conversion plan JSON fields do not match v2 schema" }
    }
}

internal object WorkshopConversionPlanner {
    fun plan(
        inputDir: File,
        preset: MpkgConversionPreset,
        device: DeviceCapabilityProfile,
        preserveAudio: Boolean,
    ): ConversionPlan {
        require(inputDir.isDirectory) { "Workshop input directory is missing" }
        require(WorkshopConverter.detect(inputDir) == WorkshopKind.SCENE) { "Conversion plans currently support scene MPKG conversion only" }
        val resolution = PresetDependencyResolver.resolve(inputDir).getOrThrow()
        val sceneRoot = resolution?.sceneRoot ?: inputDir
        val projectFile = File(sceneRoot, "project.json")
        val project = JSONObject(com.wallhub.android.data.downloads.readProjectJson(projectFile))
        val scenePackage = WorkshopConverter.resolveScenePackage(sceneRoot, project.optString("file").takeIf(String::isNotBlank))
        val policy = preset.policy()
        val archive = PkgReader.readIndex(scenePackage)
        val graphEntries = archive.entries.filter(::isDeclarativeGraphDocument).sortedBy(IndexedPkgEntry::path)
        val texturePaths = archive.entries.filter { it.path.endsWith(".tex", ignoreCase = true) }.mapTo(mutableSetOf(), IndexedPkgEntry::path)
        val materialGraph =
            DeclarativeMaterialGraph.analyzeDocuments(
                documents = boundedGraphDocuments(archive, graphEntries),
                texturePaths = texturePaths,
            )
        val resources =
            archive.entries.map { entry ->
                val semantic = materialGraph.semantics[entry.path].takeIf { graphEntries.isNotEmpty() }
                val graphWarning = materialGraph.documentWarnings[entry.path]?.firstOrNull()
                resourcePlan(scenePackage, entry, policy, device, preserveAudio, semantic, graphWarning)
            }
        return ConversionPlan(
            presetId = preset.stableId,
            policyVersion = policy.version,
            preserveAudio = preserveAudio,
            device = device,
            sourceIdentity = identity(scenePackage),
            resources = resources,
        )
    }

    fun identity(scenePackage: File): ScenePackageIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        scenePackage.inputStream().buffered().use { input ->
            val buffer = ByteArray(IDENTITY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ScenePackageIdentity(packageLength = scenePackage.length(), indexDigest = digest.digest().toHex())
    }

    private fun resourcePlan(
        scenePackage: File,
        entry: IndexedPkgEntry,
        policy: ConversionPolicy,
        device: DeviceCapabilityProfile,
        preserveAudio: Boolean,
        semantic: TextureSemantic?,
        graphWarning: String?,
    ): ConversionResourcePlan {
        val extension = entry.path.substringAfterLast('.', "").lowercase()
        val (strategy, reason) =
            when (extension) {
                "mp3", "wav", "ogg", "flac", "aac", "m4a" ->
                    if (preserveAudio) {
                        ResourceStrategy.COPY to "audio_preserved_by_user_preference"
                    } else {
                        ResourceStrategy.REMOVE_AUDIO to "audio_removed_by_mobile_policy"
                    }
                "frag", "vert" -> ResourceStrategy.REWRITE_SHADER to "rewrite_shader_for_mobile_glsl"
                "tex" -> textureStrategy(scenePackage, entry, policy, device, semantic)
                "json" ->
                    if (graphWarning != null) {
                        ResourceStrategy.PASSTHROUGH_WITH_WARNING to graphWarning
                    } else {
                        ResourceStrategy.COPY to "copy_non_texture"
                    }
                else -> ResourceStrategy.COPY to "copy_non_texture"
            }
        return ConversionResourcePlan(entry.path, entry.length, strategy, reason)
    }

    private fun textureStrategy(
        scenePackage: File,
        entry: IndexedPkgEntry,
        policy: ConversionPolicy,
        device: DeviceCapabilityProfile,
        declaredSemantic: TextureSemantic?,
    ): Pair<ResourceStrategy, String> {
        val decision = TexMobileConverter.inspectEntryDecision(scenePackage, entry.offset, entry.length, device.maxHeapBytes)
        if (policy.presetId == MpkgConversionPreset.SOURCE_FIDELITY.stableId) {
            return if (
                decision.route == TexEntryRoute.UNSUPPORTED ||
                decision.route == TexEntryRoute.UNSUPPORTED_VIDEO ||
                decision.route == TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET
            ) {
                ResourceStrategy.PASSTHROUGH_WITH_WARNING to "tex_passthrough_source_fidelity_unverified"
            } else {
                ResourceStrategy.PASSTHROUGH to "source_fidelity_tex_passthrough"
            }
        }
        val semantic = declaredSemantic ?: if (isConservativeColorTexturePath(entry.path)) TextureSemantic.COLOR else TextureSemantic.UNKNOWN
        if (semantic != TextureSemantic.COLOR) {
            return when {
                decision.verifiedLosslessRgba -> ResourceStrategy.RETAIN_VERIFIED_MOBILE to "verified_lossless_rgba_retained"
                decision.route == TexEntryRoute.CONVERT_IMAGE_TO_ETC2 && decision.rgbaConversionFitsMemoryBudget ->
                    ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "semantic_texture_lossless_rgba"
                decision.route in setOf(TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA, TexEntryRoute.CONVERT_IN_MEMORY) ->
                    ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "semantic_texture_lossless_rgba"
                else ->
                    ResourceStrategy.PASSTHROUGH_WITH_WARNING to
                        when {
                            policy.presetId == MpkgConversionPreset.SOURCE_FIDELITY.stableId -> "tex_passthrough_source_fidelity_unverified"
                            decision.route == TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET -> "tex_passthrough_resource_budget_exceeded"
                            decision.route == TexEntryRoute.UNSUPPORTED_VIDEO -> "tex_passthrough_unsupported_video"
                            decision.route == TexEntryRoute.UNSUPPORTED ->
                                if (entry.length > LEGACY_BALANCED_TEXTURE_INPUT_BYTES) {
                                    "tex_passthrough_resource_budget_exceeded"
                                } else {
                                    "tex_passthrough_unknown_profile"
                                }
                            else -> "tex_passthrough_semantic_requires_lossless_rgba"
                        }
            }
        }
        return when (decision.route) {
            TexEntryRoute.RETAIN_VERIFIED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_VIDEO,
            -> ResourceStrategy.RETAIN_VERIFIED_MOBILE to "verified_mobile_payload_retained"
            TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP ->
                ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP to "verified_runtime_desktop_payload_retained"
            TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> {
                val decodedBytes = decision.decodedRgbaBytes ?: Long.MAX_VALUE
                when {
                    policy.presetId == MpkgConversionPreset.COMPATIBILITY.stableId && device.supportsEtc2 ->
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 to "compatibility_color_texture_etc2"
                    decodedBytes <= policy.maxStaticImageRgbaBytes && decision.rgbaConversionFitsMemoryBudget ->
                        ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "static_image_within_decoded_rgba_budget"
                    device.supportsEtc2 ->
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 to "static_image_above_decoded_rgba_budget"
                    else -> ResourceStrategy.PASSTHROUGH_WITH_WARNING to "tex_passthrough_resource_budget_exceeded"
                }
            }
            TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA,
            TexEntryRoute.CONVERT_IN_MEMORY,
            -> ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "texture_requires_rgba8888_lz4"
            TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET ->
                ResourceStrategy.PASSTHROUGH_WITH_WARNING to "tex_passthrough_resource_budget_exceeded"
            TexEntryRoute.UNSUPPORTED_VIDEO ->
                ResourceStrategy.PASSTHROUGH_WITH_WARNING to "tex_passthrough_unsupported_video"
            TexEntryRoute.UNSUPPORTED ->
                ResourceStrategy.PASSTHROUGH_WITH_WARNING to
                    if (entry.length > LEGACY_BALANCED_TEXTURE_INPUT_BYTES) {
                        "tex_passthrough_resource_budget_exceeded"
                    } else {
                        "tex_passthrough_unknown_profile"
                    }
        }
    }

    private fun isDeclarativeGraphDocument(entry: IndexedPkgEntry): Boolean {
        if (!entry.path.endsWith(".json", ignoreCase = true)) return false
        val normalized = entry.path.lowercase()
        val nameTokens = normalized.substringAfterLast('/').substringBeforeLast('.').split(Regex("[^a-z0-9]+"))
        val parentSegments = normalized.substringBeforeLast('/', "").split('/')
        return nameTokens.any { it == "project" || it == "scene" || it == "material" } ||
            parentSegments.any { it == "material" || it == "materials" }
    }

    private fun boundedGraphDocuments(
        archive: PkgArchive,
        entries: List<IndexedPkgEntry>,
    ): Sequence<Pair<String, Result<String>>> = sequence {
        var totalBytes = 0L
        entries.forEach { entry ->
            val nextTotal = runCatching { Math.addExact(totalBytes, entry.length) }.getOrDefault(Long.MAX_VALUE)
            if (entry.length > MAX_GRAPH_DOCUMENT_BYTES || nextTotal > MAX_GRAPH_TOTAL_BYTES) {
                yield(entry.path to Result.failure<String>(IllegalArgumentException("material graph JSON byte limit exceeded")))
            } else {
                totalBytes = nextTotal
                val json =
                    runCatching {
                        val bytes = archive.readBytes(entry, MAX_GRAPH_DOCUMENT_BYTES)
                        Charsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    }
                yield(entry.path to json)
            }
        }
    }

    private fun isConservativeColorTexturePath(path: String): Boolean {
        val stem = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val semanticTokens = stem.split(Regex("[^a-z0-9]+"))
        if (semanticTokens.any { it in UNSAFE_TEXTURE_SEMANTIC_TOKENS }) return false
        return semanticTokens.any { it in SAFE_COLOR_TEXTURE_TOKENS }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun requireBoundedPersistedConversionJson(json: String?) {
    if (json == null) return
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_CONVERSION_JSON_BYTES) {
        "Persisted conversion JSON exceeds byte limit"
    }
}

internal fun TexEntryRoute.isVerifiedPassthroughRoute(): Boolean =
    this == TexEntryRoute.RETAIN_VERIFIED_MOBILE ||
        this == TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE ||
        this == TexEntryRoute.RETAIN_VERIFIED_VIDEO ||
        this == TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP

private fun String.isAbsolutePlanPath(): Boolean =
    startsWith('/') || startsWith('\\') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(this)

private const val IDENTITY_BUFFER_BYTES = 64 * 1024
private const val MAX_GRAPH_DOCUMENT_BYTES = 2L * 1024L * 1024L
private const val MAX_GRAPH_TOTAL_BYTES = 8L * 1024L * 1024L
private const val LEGACY_BALANCED_TEXTURE_INPUT_BYTES = 32L * 1024L * 1024L
private val SAFE_COLOR_TEXTURE_TOKENS = setOf("background", "wallpaper", "backdrop", "scene", "color", "albedo", "diffuse")
private val UNSAFE_TEXTURE_SEMANTIC_TOKENS = setOf("normal", "norm", "nrm", "mask", "roughness", "metallic", "height", "depth", "specular", "ao", "alpha")
private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB
private const val MAX_PERSISTED_CONVERSION_JSON_BYTES = 128 * 1024
internal const val MAX_PLAN_PATH_BYTES = 4 * 1024
internal const val MAX_REASON_CHARS = 256
