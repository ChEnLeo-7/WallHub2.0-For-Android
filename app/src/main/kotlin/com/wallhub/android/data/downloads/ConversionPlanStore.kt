package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.ConversionPlan
import com.wallhub.prototype.mpkg.ConversionPlanJsonCodec
import com.wallhub.prototype.mpkg.ScenePackageIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal data class ConversionPlanReference(
    val planVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
    val sourceIdentity: ScenePackageIdentity,
    val presetId: String,
) {
    init {
        require(planVersion > 0) { "Conversion plan version must be positive" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid conversion plan digest" }
        require(sizeBytes in 1..ConversionPlanStore.MAX_FILE_BYTES) { "Invalid conversion plan size" }
    }

    fun toJson(): String =
        JSONObject()
            .put("storage", "app_private_file")
            .put("planVersion", planVersion)
            .put("sha256", sha256)
            .put("sizeBytes", sizeBytes)
            .put("presetId", presetId)
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
                sourceIdentity =
                    ScenePackageIdentity(
                        version = identity.getInt("version"),
                        packageLength = identity.getLong("packageLength"),
                        indexDigest = identity.getString("indexDigest"),
                    ),
            )
        }

        private const val MAX_REFERENCE_JSON_BYTES = 4 * 1024
        private val REFERENCE_KEYS = setOf("storage", "planVersion", "sha256", "sizeBytes", "presetId", "sourceIdentity")
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
        val bytes = ConversionPlanJsonCodec.encode(plan).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Conversion plan file exceeds byte limit" }
        check(directory.exists() || directory.mkdirs()) { "Unable to create conversion plan directory" }
        val temporary = File.createTempFile(".${destination.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            move(temporary, destination)
        } finally {
            temporary.delete()
        }
        return reference(plan, bytes)
    }

    fun read(taskId: String, reference: ConversionPlanReference): ConversionPlan {
        val file = planFile(taskId)
        require(file.isFile) { "Conversion plan file is missing" }
        require(file.length() == reference.sizeBytes) { "Conversion plan size does not match reference" }
        require(file.length() <= MAX_FILE_BYTES) { "Conversion plan file exceeds byte limit" }
        val bytes = file.readBytes()
        require(digest(bytes) == reference.sha256) { "Conversion plan digest does not match reference" }
        val plan = ConversionPlanJsonCodec.decode(bytes.toString(Charsets.UTF_8))
        require(plan.version == reference.planVersion) { "Conversion plan version does not match reference" }
        require(plan.presetId == reference.presetId) { "Conversion plan preset does not match reference" }
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

    private fun reference(plan: ConversionPlan, bytes: ByteArray) =
        ConversionPlanReference(
            planVersion = plan.version,
            sha256 = digest(bytes),
            sizeBytes = bytes.size.toLong(),
            sourceIdentity = plan.sourceIdentity,
            presetId = plan.presetId,
        )

    companion object {
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        private const val DIRECTORY_NAME = "conversion-plans"
        private val TASK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
