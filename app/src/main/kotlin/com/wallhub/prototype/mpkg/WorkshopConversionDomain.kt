package com.wallhub.prototype.mpkg

import com.wallhub.android.core.model.MpkgConversionPreset
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

fun MpkgConversionPreset.policy(): ConversionPolicy =
    when (this) {
        MpkgConversionPreset.BALANCED -> ConversionPolicy(stableId, 32L * MIB, 1L * GIB, 0L, "standard")
        MpkgConversionPreset.COMPATIBILITY -> ConversionPolicy(stableId, 16L * MIB, 512L * MIB, 0L, "compatibility_guardrails")
        MpkgConversionPreset.HIGH_QUALITY -> ConversionPolicy(stableId, 64L * MIB, 2L * GIB, 16L * MIB, "source_dimensions_retained")
        MpkgConversionPreset.SOURCE_FIDELITY -> ConversionPolicy(stableId, 64L * MIB, 4L * GIB, 64L * MIB, "verified_mobile_payloads_retained")
    }

data class ConversionPolicy(
    val presetId: String,
    val maxTextureInputBytes: Long,
    val maxScenePackageBytes: Long,
    val maxStaticImageRgbaBytes: Long,
    val retentionNotice: String,
    val version: Int = 1,
) {
    /** Describes only transformations the v1 converter can actually execute. */
    fun stableDescription(): String {
        val staticStrategy =
            if (maxStaticImageRgbaBytes == 0L) {
                "etc2_rgba8_lz4"
            } else {
                "rgba8888_lz4_up_to_$maxStaticImageRgbaBytes"
            }
        return "$presetId|v$version|static=$staticStrategy|animated=rgba8888_lz4|verified=retain|" +
            "input=$maxTextureInputBytes|package=$maxScenePackageBytes|notice=$retentionNotice"
    }
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
    val version: Int = 1,
    val presetId: String,
    val policyVersion: Int,
    val device: DeviceCapabilityProfile,
    val sourceIdentity: ScenePackageIdentity,
    val resources: List<ConversionResourcePlan>,
) {
    init {
        require(version == 1) { "Unsupported conversion plan version: $version" }
        require(policyVersion == 1) { "Unsupported conversion policy version: $policyVersion" }
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

    /** Durable reports are summaries; per-resource outcomes remain process-local. */
    fun encode(report: WorkshopConversionReport): String =
        JSONObject()
            .put("version", 1)
            .put("planVersion", report.planVersion ?: 1)
            .put("presetId", report.presetId ?: MpkgConversionPreset.BALANCED.stableId)
            .put("kind", report.kind.name)
            .put("outputBytes", report.outputFile.length())
            .put("resourceCount", report.outcomes.size)
            .put("convertedTextures", report.convertedTextures)
            .put("copiedTextures", report.copiedTextures)
            .put("removedAudio", report.removedAudio)
            .put("rewrittenShaders", report.rewrittenShaders)
            .put("warningCount", report.warnings.size)
            .toString()
            .also(::requireBoundedPersistedConversionJson)
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
        require(actual == expected) { "Conversion plan JSON fields do not match v1 schema" }
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
        require(scenePackage.length() <= policy.maxScenePackageBytes) { "Scene package exceeds ${preset.stableId} package budget" }
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
        if (entry.length > MAX_GLOBAL_IMAGE_TEX_INPUT_BYTES) {
            return ResourceStrategy.REJECT to "global_texture_input_budget_exceeded"
        }
        val decision = TexMobileConverter.inspectEntryDecision(scenePackage, entry.offset, entry.length, device.maxHeapBytes)
        if (
            entry.length > policy.maxTextureInputBytes &&
            policy.presetId != MpkgConversionPreset.COMPATIBILITY.stableId &&
            decision.route != TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP
        ) {
            return ResourceStrategy.REJECT to "preset_texture_input_budget_exceeded"
        }
        val exceedsCompatibilityBudget =
            policy.presetId == MpkgConversionPreset.COMPATIBILITY.stableId &&
                (entry.length > policy.maxTextureInputBytes ||
                    (decision.imageWidth ?: 0) > COMPATIBILITY_MAX_TEXTURE_DIMENSION ||
                    (decision.imageHeight ?: 0) > COMPATIBILITY_MAX_TEXTURE_DIMENSION)
        if (
            exceedsCompatibilityBudget &&
            decision.route != TexEntryRoute.CONVERT_IMAGE_TO_ETC2 &&
            decision.route != TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP
        ) {
            return ResourceStrategy.REJECT to "preset_texture_input_budget_exceeded"
        }
        return when (decision.route) {
            TexEntryRoute.RETAIN_VERIFIED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_ANIMATED_MOBILE,
            TexEntryRoute.RETAIN_VERIFIED_VIDEO,
            -> ResourceStrategy.RETAIN_VERIFIED_MOBILE to "verified_mobile_payload_retained"
            TexEntryRoute.RETAIN_VERIFIED_RUNTIME_DESKTOP -> {
                val family = decision.runtimeDesktopFamily
                    ?: return ResourceStrategy.REJECT to "unsupported_texture_profile"
                val reason =
                    when (family) {
                        TexRuntimeDesktopFamily.STANDARD_BLOCK_COMPRESSED_ANIMATED ->
                            "verified_runtime_desktop_standard_block_compressed_animated"
                        TexRuntimeDesktopFamily.CONDITIONAL_VARIANT_EXTENDED ->
                            "verified_runtime_desktop_conditional_variant_extended"
                    }
                ResourceStrategy.RETAIN_VERIFIED_RUNTIME_DESKTOP to reason
            }
            TexEntryRoute.CONVERT_IMAGE_TO_ETC2 ->
                if (exceedsCompatibilityBudget) {
                    when {
                        !device.supportsEtc2 -> ResourceStrategy.REJECT to "device_does_not_support_etc2"
                        !decision.boundedStaticPngOrJpeg -> ResourceStrategy.REJECT to "unsafe_compatibility_downsample_source"
                        !isConservativeColorTexturePath(entry.path) -> ResourceStrategy.REJECT to "unknown_texture_semantics"
                        (decision.imageWidth ?: 0) > COMPATIBILITY_MAX_TEXTURE_DIMENSION ||
                            (decision.imageHeight ?: 0) > COMPATIBILITY_MAX_TEXTURE_DIMENSION ->
                            ResourceStrategy.DOWNSAMPLE_TO_ETC2 to "compatibility_static_color_downsample"
                        else -> ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 to "compatibility_large_static_color_etc2"
                    }
                } else if (
                    !decision.animated &&
                    policy.maxStaticImageRgbaBytes > 0L &&
                    decision.decodedRgbaBytes!! <= policy.maxStaticImageRgbaBytes &&
                    decision.rgbaConversionFitsMemoryBudget
                ) {
                    ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "static_image_within_preset_rgba_budget"
                } else if (device.supportsEtc2) {
                    ResourceStrategy.CONVERT_TO_ETC2_RGBA8_LZ4 to "static_image_requires_etc2_rgba8_lz4"
                } else {
                    ResourceStrategy.REJECT to "device_does_not_support_etc2"
                }
            TexEntryRoute.CONVERT_ANIMATED_IMAGE_TO_RGBA,
            TexEntryRoute.CONVERT_IN_MEMORY,
            -> ResourceStrategy.CONVERT_TO_RGBA8888_LZ4 to "texture_requires_rgba8888_lz4"
            TexEntryRoute.UNSUPPORTED_RESOURCE_BUDGET -> ResourceStrategy.REJECT to "device_memory_budget_exceeded"
            TexEntryRoute.UNSUPPORTED_VIDEO -> ResourceStrategy.REJECT to "unsupported_video_texture_profile"
            TexEntryRoute.UNSUPPORTED -> ResourceStrategy.REJECT to "unsupported_texture_profile"
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

private fun String.isAbsolutePlanPath(): Boolean =
    startsWith('/') || startsWith('\\') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(this)

private const val IDENTITY_BUFFER_BYTES = 64 * 1024
private const val COMPATIBILITY_MAX_TEXTURE_DIMENSION = 2048
// File-backed compatibility downsampling never materializes the encoded payload in heap. Keep a
// separate hard file-size ceiling so large PNG/JPEG sources can reach metadata inspection while
// decoded pixels remain bounded by TexMobileConverter's global pixel and heap checks.
private const val MAX_GLOBAL_IMAGE_TEX_INPUT_BYTES = 256L * 1024L * 1024L
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
