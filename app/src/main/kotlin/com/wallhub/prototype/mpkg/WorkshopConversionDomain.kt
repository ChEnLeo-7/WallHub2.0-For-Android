package com.wallhub.prototype.mpkg

import com.wallhub.android.core.model.MpkgConversionPreset
import java.io.File
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
    val device: DeviceCapabilityProfile,
    val sourceIdentity: ScenePackageIdentity,
    val resources: List<ConversionResourcePlan>,
) {
    init {
        require(version == 2) { "Unsupported conversion plan version: $version" }
        require(policyVersion == 2) { "Unsupported conversion policy version: $policyVersion" }
        require(MpkgConversionPreset.fromStableId(presetId).stableId == presetId) { "Unknown conversion preset: $presetId" }
        require(resources.size <= MAX_PLAN_RESOURCES) { "Conversion plan exceeds resource limit" }
        require(resources.sumOf(::persistedResourceBudget) <= MAX_PLAN_RESOURCE_BUDGET_BYTES) {
            "Conversion plan exceeds persisted resource budget"
        }
        require(resources.map(ConversionResourcePlan::path).distinct().size == resources.size) {
            "Conversion plan contains duplicate resource paths"
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

object ConversionReportJsonCodec {
    const val MAX_JSON_BYTES = 128 * 1024

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
                    .put(
                        "warningDetails",
                        JSONArray().also { array ->
                            persistedDetails.forEach { detail ->
                                array.put(JSONObject().put("code", detail.code).put("path", detail.path))
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

object ConversionPlanJsonCodec {
    const val MAX_JSON_BYTES = 4 * 1024 * 1024

    fun encode(plan: ConversionPlan): String {
        val root = JSONObject()
        root.put("version", plan.version)
        root.put("presetId", plan.presetId)
        root.put("policyVersion", plan.policyVersion)
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
        return root.toString().also(::requireBoundedJson)
    }

    fun decode(json: String): ConversionPlan {
        requireBoundedJson(json)
        val root = JSONObject(json)
        requireKeys(root, setOf("version", "presetId", "policyVersion", "device", "sourceIdentity", "resources"))
        val deviceJson = root.getJSONObject("device")
        requireKeys(deviceJson, setOf("maxHeapBytes", "supportsEtc2"))
        val identityJson = root.getJSONObject("sourceIdentity")
        requireKeys(identityJson, setOf("version", "packageLength", "indexDigest"))
        val resourcesJson = root.getJSONArray("resources")
        require(resourcesJson.length() <= MAX_PLAN_RESOURCES) { "Conversion plan exceeds resource limit" }
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

    private fun requireBoundedJson(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Conversion plan JSON exceeds byte limit" }
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) { "Conversion plan JSON fields do not match v2 schema" }
    }
}

internal object WorkshopConversionPlanner {
    fun plan(inputDir: File, preset: MpkgConversionPreset, device: DeviceCapabilityProfile): ConversionPlan {
        require(inputDir.isDirectory) { "Workshop input directory is missing" }
        require(WorkshopConverter.detect(inputDir) == WorkshopKind.SCENE) { "Conversion plans currently support scene MPKG conversion only" }
        val resolution = PresetDependencyResolver.resolve(inputDir).getOrThrow()
        val sceneRoot = resolution?.sceneRoot ?: inputDir
        val projectFile = File(sceneRoot, "project.json")
        val project = JSONObject(com.wallhub.android.data.downloads.readProjectJson(projectFile))
        val scenePackage = WorkshopConverter.resolveScenePackage(sceneRoot, project.optString("file").takeIf(String::isNotBlank))
        val policy = preset.policy()
        val archive = PkgReader.readIndex(scenePackage)
        require(archive.entries.size <= MAX_PLAN_RESOURCES) { "Conversion plan exceeds resource limit" }
        var persistedResourceBytes = 0L
        val resources =
            archive.entries.map { entry ->
                val resource = resourcePlan(scenePackage, entry, policy, device)
                persistedResourceBytes = Math.addExact(persistedResourceBytes, persistedResourceBudget(resource))
                require(persistedResourceBytes <= MAX_PLAN_RESOURCE_BUDGET_BYTES) {
                    "Conversion plan exceeds persisted resource budget"
                }
                resource
            }
        return ConversionPlan(
            presetId = preset.stableId,
            policyVersion = policy.version,
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
    ): ConversionResourcePlan {
        val extension = entry.path.substringAfterLast('.', "").lowercase()
        val (strategy, reason) =
            when (extension) {
                "mp3", "wav", "ogg", "flac", "aac", "m4a" -> ResourceStrategy.REMOVE_AUDIO to "audio_removed_by_mobile_policy"
                "frag", "vert" -> ResourceStrategy.REWRITE_SHADER to "rewrite_shader_for_mobile_glsl"
                "tex" -> textureStrategy(scenePackage, entry, policy, device)
                else -> ResourceStrategy.COPY to "copy_non_texture"
            }
        return ConversionResourcePlan(entry.path, entry.length, strategy, reason)
    }

    private fun textureStrategy(
        scenePackage: File,
        entry: IndexedPkgEntry,
        policy: ConversionPolicy,
        device: DeviceCapabilityProfile,
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
        return when (decision.route) {
            TexEntryRoute.RETAIN_VERIFIED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_VIDEO,
            -> ResourceStrategy.RETAIN_VERIFIED_MOBILE to "verified_mobile_payload_retained"
            TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP ->
                ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP to "verified_runtime_desktop_payload_retained"
            TexEntryRoute.CONVERT_IMAGE_TO_ETC2 -> {
                val decodedBytes = decision.decodedRgbaBytes ?: Long.MAX_VALUE
                val colorTexture = isConservativeColorTexturePath(entry.path)
                when {
                    policy.presetId == MpkgConversionPreset.COMPATIBILITY.stableId && colorTexture && device.supportsEtc2 ->
                        ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 to "compatibility_color_texture_etc2"
                    !colorTexture && decision.rgbaConversionFitsMemoryBudget ->
                        ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "semantic_texture_lossless_rgba"
                    decodedBytes <= policy.maxStaticImageRgbaBytes && decision.rgbaConversionFitsMemoryBudget ->
                        ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "static_image_within_decoded_rgba_budget"
                    colorTexture && device.supportsEtc2 ->
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

private fun persistedResourceBudget(resource: ConversionResourcePlan): Long =
    resource.path.toByteArray(Charsets.UTF_8).size.toLong() +
        resource.reason.toByteArray(Charsets.UTF_8).size.toLong() +
        MAX_RESOURCE_JSON_OVERHEAD_BYTES

internal fun TexEntryRoute.isVerifiedPassthroughRoute(): Boolean =
    this == TexEntryRoute.RETAIN_VERIFIED_MOBILE ||
        this == TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE ||
        this == TexEntryRoute.RETAIN_VERIFIED_VIDEO ||
        this == TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP

private fun String.isAbsolutePlanPath(): Boolean =
    startsWith('/') || startsWith('\\') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(this)

private const val IDENTITY_BUFFER_BYTES = 64 * 1024
private const val LEGACY_BALANCED_TEXTURE_INPUT_BYTES = 32L * 1024L * 1024L
private val SAFE_COLOR_TEXTURE_TOKENS = setOf("background", "wallpaper", "backdrop", "scene", "color", "albedo", "diffuse")
private val UNSAFE_TEXTURE_SEMANTIC_TOKENS = setOf("normal", "norm", "nrm", "mask", "roughness", "metallic", "height", "depth", "specular", "ao", "alpha")
private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB
private const val MAX_PERSISTED_CONVERSION_JSON_BYTES = 128 * 1024
private const val MAX_PLAN_RESOURCES = 32 * 1024
private const val MAX_PLAN_PATH_BYTES = 4 * 1024
private const val MAX_REASON_CHARS = 256
private const val MAX_RESOURCE_JSON_OVERHEAD_BYTES = 160L
private const val MAX_PLAN_RESOURCE_BUDGET_BYTES = ConversionPlanJsonCodec.MAX_JSON_BYTES.toLong() - 1024L
