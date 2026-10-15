package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionPlan
import com.wallhub.prototype.mpkg.ConversionPlanJsonCodec
import com.wallhub.prototype.mpkg.ConversionResourcePlan
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.MAX_PLAN_PATH_BYTES
import com.wallhub.prototype.mpkg.MAX_REASON_CHARS
import com.wallhub.prototype.mpkg.ResourceStrategy
import com.wallhub.prototype.mpkg.ScenePackageIdentity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONObject

internal data class ConversionPlanReference(
    val planVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
    val sourceIdentity: ScenePackageIdentity,
    val presetId: String,
    val preserveAudio: Boolean,
) {
    init {
        require(planVersion > 0) { "Conversion plan version must be positive" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid conversion plan digest" }
        require(sizeBytes > 0) { "Invalid conversion plan size" }
    }

    fun toJson(): String =
        JSONObject()
            .put("storage", "app_private_file")
            .put("planVersion", planVersion)
            .put("sha256", sha256)
            .put("sizeBytes", sizeBytes)
            .put("presetId", presetId)
            .put("preserveAudio", preserveAudio)
            .put(
                "sourceIdentity",
                JSONObject()
                    .put("version", sourceIdentity.version)
                    .put("packageLength", sourceIdentity.packageLength)
                    .put("indexDigest", sourceIdentity.indexDigest),
            ).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_REFERENCE_JSON_BYTES) }

    companion object {
        fun fromJson(json: String): ConversionPlanReference {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_REFERENCE_JSON_BYTES) { "Conversion plan reference exceeds byte limit" }
            val root = JSONObject(json)
            require(root.keys().asSequence().toSet() == REFERENCE_KEYS) { "Conversion plan reference fields do not match schema" }
            require(root.getString("storage") == "app_private_file") { "Unknown conversion plan storage" }
            val identity = root.getJSONObject("sourceIdentity")
            require(identity.keys().asSequence().toSet() == IDENTITY_KEYS) { "Conversion plan source identity fields do not match schema" }
            return ConversionPlanReference(
                planVersion = root.getInt("planVersion"),
                sha256 = root.getString("sha256"),
                sizeBytes = root.getLong("sizeBytes"),
                presetId = root.getString("presetId"),
                preserveAudio = root.getBoolean("preserveAudio"),
                sourceIdentity =
                    ScenePackageIdentity(
                        version = identity.getInt("version"),
                        packageLength = identity.getLong("packageLength"),
                        indexDigest = identity.getString("indexDigest"),
                    ),
            )
        }

        private const val MAX_REFERENCE_JSON_BYTES = 4 * 1024
        private val REFERENCE_KEYS =
            setOf("storage", "planVersion", "sha256", "sizeBytes", "presetId", "preserveAudio", "sourceIdentity")
        private val IDENTITY_KEYS = setOf("version", "packageLength", "indexDigest")
    }
}

internal class ConversionPlanStore(
    filesDir: File,
    private val move: (File, File) -> Unit = ::atomicReplace,
) {
    private val directory = File(filesDir, DIRECTORY_NAME)

    fun write(taskId: String, plan: ConversionPlan): ConversionPlanReference {
        val destination = planFile(taskId)
        check(directory.exists() || directory.mkdirs()) { "Unable to create conversion plan directory" }
        val temporary = File.createTempFile(".${destination.name}.", ".tmp", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DigestOutputStream(BufferedOutputStream(fileOutput), digest)
                ConversionPlanFileCodec.write(plan, output)
                output.flush()
                fileOutput.fd.sync()
            }
            move(temporary, destination)
        } finally {
            temporary.delete()
        }
        return reference(plan, temporaryDigest = digest.digest().toHex(), sizeBytes = destination.length())
    }

    fun read(taskId: String, reference: ConversionPlanReference): ConversionPlan {
        val file = planFile(taskId)
        require(file.isFile) { "Conversion plan file is missing" }
        val plan =
            FileInputStream(file).use { input ->
                val fileLength = input.channel.size()
                require(fileLength == reference.sizeBytes) { "Conversion plan size does not match reference" }
                require(input.streamingSha256() == reference.sha256) { "Conversion plan digest does not match reference" }
                input.channel.position(0L)
                val magic = if (fileLength >= Int.SIZE_BYTES) DataInputStream(input).readInt() else null
                input.channel.position(0L)
                if (magic == ConversionPlanFileCodec.MAGIC) {
                    ConversionPlanFileCodec.read(input, fileLength)
                } else {
                    // Compatibility with plans persisted by releases before the streamed journal codec.
                    require(fileLength <= LEGACY_MAX_JSON_BYTES) { "Legacy conversion plan exceeds byte limit" }
                    ConversionPlanJsonCodec.decode(input.readBytes().toString(Charsets.UTF_8))
                }
            }
        require(plan.version == reference.planVersion) { "Conversion plan version does not match reference" }
        require(plan.presetId == reference.presetId) { "Conversion plan preset does not match reference" }
        require(plan.preserveAudio == reference.preserveAudio) { "Conversion plan audio preference does not match reference" }
        require(plan.sourceIdentity == reference.sourceIdentity) { "Conversion plan source identity does not match reference" }
        return plan
    }

    fun delete(taskId: String): Boolean {
        val file = planFile(taskId)
        val deleted = !file.exists() || file.delete()
        directory.listFiles()
            ?.filter { it.name.startsWith(".${file.name}.") && it.name.endsWith(".tmp") }
            ?.forEach(File::delete)
        return deleted
    }

    private fun planFile(taskId: String): File {
        require(TASK_ID.matches(taskId)) { "Unsafe conversion plan task ID" }
        return File(directory, "$taskId.json")
    }

    private fun reference(plan: ConversionPlan, temporaryDigest: String, sizeBytes: Long) =
        ConversionPlanReference(
            planVersion = plan.version,
            sha256 = temporaryDigest,
            sizeBytes = sizeBytes,
            sourceIdentity = plan.sourceIdentity,
            presetId = plan.presetId,
            preserveAudio = plan.preserveAudio,
        )

    companion object {
        private const val DIRECTORY_NAME = "conversion-plans"
        private const val LEGACY_MAX_JSON_BYTES = 4L * 1024L * 1024L
        private val TASK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        private fun atomicReplace(source: File, destination: File) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

internal fun conversionPlanDigest(plan: ConversionPlan): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun append(value: String) = digest.update(value.toByteArray(Charsets.UTF_8))
    append("{\"version\":${plan.version},\"presetId\":${JSONObject.quote(plan.presetId)},\"policyVersion\":${plan.policyVersion}")
    append(",\"preserveAudio\":${plan.preserveAudio},\"device\":{\"maxHeapBytes\":${plan.device.maxHeapBytes}")
    append(",\"supportsEtc2\":${plan.device.supportsEtc2}},\"sourceIdentity\":{\"version\":${plan.sourceIdentity.version}")
    append(",\"packageLength\":${plan.sourceIdentity.packageLength},\"indexDigest\":${JSONObject.quote(plan.sourceIdentity.indexDigest)}}")
    append(",\"resources\":[")
    plan.resources.forEachIndexed { index, resource ->
        if (index > 0) append(",")
        append("{\"path\":${JSONObject.quote(resource.path)},\"sourceLength\":${resource.sourceLength}")
        append(",\"strategy\":${JSONObject.quote(resource.strategy.name)},\"reason\":${JSONObject.quote(resource.reason)}}")
    }
    append("]}")
    return digest.digest().toHex()
}

private object ConversionPlanFileCodec {
    const val MAGIC = 0x5748504c
    private const val CODEC_VERSION = 1
    private const val MAX_TOKEN_BYTES = 256
    private const val MAX_DIGEST_BYTES = 64
    private const val MAX_REASON_BYTES = MAX_REASON_CHARS * 4
    private const val MIN_RESOURCE_BYTES = 4L + 1L + 8L + 4L + 1L + 4L

    fun write(plan: ConversionPlan, output: OutputStream) {
        DataOutputStream(output).let { data ->
            data.writeInt(MAGIC)
            data.writeInt(CODEC_VERSION)
            data.writeInt(plan.version)
            data.writeBoundedUtf8(plan.presetId, MAX_TOKEN_BYTES)
            data.writeInt(plan.policyVersion)
            data.writeBoolean(plan.preserveAudio)
            data.writeLong(plan.device.maxHeapBytes)
            data.writeBoolean(plan.device.supportsEtc2)
            data.writeInt(plan.sourceIdentity.version)
            data.writeLong(plan.sourceIdentity.packageLength)
            data.writeBoundedUtf8(plan.sourceIdentity.indexDigest, MAX_DIGEST_BYTES)
            data.writeInt(plan.resources.size)
            plan.resources.forEach { resource ->
                data.writeBoundedUtf8(resource.path, MAX_PLAN_PATH_BYTES)
                data.writeLong(resource.sourceLength)
                data.writeBoundedUtf8(resource.strategy.name, MAX_TOKEN_BYTES)
                data.writeBoundedUtf8(resource.reason, MAX_REASON_BYTES)
            }
        }
    }

    fun read(input: java.io.InputStream, fileLength: Long): ConversionPlan =
        DataInputStream(BufferedInputStream(input)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid conversion plan journal magic" }
            require(data.readInt() == CODEC_VERSION) { "Unsupported conversion plan journal version" }
            val version = data.readInt()
            val presetId = data.readBoundedUtf8(MAX_TOKEN_BYTES)
            val policyVersion = data.readInt()
            val preserveAudio = data.readBoolean()
            val device = DeviceCapabilityProfile(data.readLong(), data.readBoolean())
            val sourceIdentity = ScenePackageIdentity(data.readInt(), data.readLong(), data.readBoundedUtf8(MAX_DIGEST_BYTES))
            val count = data.readInt()
            require(count >= 0 && count.toLong() <= fileLength / MIN_RESOURCE_BYTES) {
                "Conversion plan resource count is not representable by the journal"
            }
            val resources = ArrayList<ConversionResourcePlan>()
            repeat(count) {
                resources +=
                    ConversionResourcePlan(
                        path = data.readBoundedUtf8(MAX_PLAN_PATH_BYTES),
                        sourceLength = data.readLong(),
                        strategy = ResourceStrategy.valueOf(data.readBoundedUtf8(MAX_TOKEN_BYTES)),
                        reason = data.readBoundedUtf8(MAX_REASON_BYTES),
                    )
            }
            require(data.read() == -1) { "Conversion plan journal has trailing bytes" }
            ConversionPlan(version, presetId, policyVersion, preserveAudio, device, sourceIdentity, resources)
        }

    private fun DataOutputStream.writeBoundedUtf8(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Conversion plan field exceeds encoded byte limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(maxBytes: Int): String {
        val length = readInt()
        require(length in 0..maxBytes) { "Invalid conversion plan field length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        val decoded = bytes.toString(Charsets.UTF_8)
        require(decoded.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8 in conversion plan" }
        return decoded
    }
}

private fun FileInputStream.streamingSha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
