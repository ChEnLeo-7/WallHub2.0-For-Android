package com.wallhub.android.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.wallhub.android.core.model.AppReleaseInfo
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.InstalledAppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubAppUpdateRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppUpdateRepository {
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
        private val activeDownloadCall = AtomicReference<Call?>()

        override val installedAppInfo: InstalledAppInfo by lazy {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            InstalledAppInfo(
                appName = context.applicationInfo.loadLabel(context.packageManager).toString(),
                packageName = context.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = packageInfo.compatLongVersionCode(),
                lastUpdateTimeMillis = packageInfo.lastUpdateTime,
            )
        }

        override suspend fun latestRelease(): AppReleaseInfo {
            val request =
                Request
                    .Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            return client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub Release check failed: HTTP ${response.code}")
                }
                val body = response.body.string()
                parseLatestRelease(body, installedAppInfo.versionName)
            }
        }

        override suspend fun downloadRelease(
            release: AppReleaseInfo,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): String =
            withContext(Dispatchers.IO) {
                release.assetUrl.requireOfficialAssetUrl(release.tagName, release.assetName)
                require(release.assetSizeBytes in 1L..MAX_APK_BYTES) { "Release APK size is invalid" }
                require(release.sha256.matches(SHA_256_REGEX)) { "Release APK SHA-256 is invalid" }

                val directory = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
                check(directory.isDirectory) { "Cannot create update cache directory" }
                val destination = File(directory, VERIFIED_APK_NAME)
                val partial = File(directory, "$VERIFIED_APK_NAME.part")
                partial.delete()

                val request =
                    Request
                        .Builder()
                        .url(release.assetUrl)
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                val call = client.newCall(request)
                check(activeDownloadCall.compareAndSet(null, call)) { "A Release APK download is already active" }
                try {
                    call.awaitResponse().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Release APK download failed: HTTP ${response.code}")
                        }
                        val responseLength = response.body.contentLength()
                        if (responseLength > 0L && responseLength != release.assetSizeBytes) {
                            throw IOException("Release APK size metadata mismatch")
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var downloaded = 0L
                        var lastReportedBytes = 0L
                        response.body.byteStream().use { input ->
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(COPY_BUFFER_BYTES)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    downloaded += read
                                    if (downloaded > release.assetSizeBytes || downloaded > MAX_APK_BYTES) {
                                        throw IOException("Release APK exceeds expected size")
                                    }
                                    if (
                                        downloaded == release.assetSizeBytes ||
                                        downloaded - lastReportedBytes >= PROGRESS_REPORT_INTERVAL_BYTES
                                    ) {
                                        onProgress(downloaded, release.assetSizeBytes)
                                        lastReportedBytes = downloaded
                                    }
                                }
                            }
                        }
                        if (downloaded != release.assetSizeBytes) {
                            throw IOException("Release APK download is incomplete")
                        }
                        val actualDigest = digest.digest().toHexString()
                        if (!actualDigest.equals(release.sha256, ignoreCase = true)) {
                            throw IOException("Release APK SHA-256 verification failed")
                        }
                    }
                    verifyArchiveIdentityAndSigner(partial, release.versionName)
                    try {
                        Files.move(
                            partial.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            partial.toPath(),
                            destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    destination.absolutePath
                } catch (error: Throwable) {
                    partial.delete()
                    throw error
                } finally {
                    activeDownloadCall.compareAndSet(call, null)
                }
            }

        override fun cancelDownload() {
            activeDownloadCall.get()?.cancel()
        }

        @Suppress("DEPRECATION")
        private fun verifyArchiveIdentityAndSigner(
            apk: File,
            expectedVersionName: String,
        ) {
            val packageManager = context.packageManager
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            val archive =
                packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            val installed = packageManager.getPackageInfo(context.packageName, flags)
            val validationError = archiveValidationError(archive, installed, expectedVersionName)
            if (validationError != null) throw IOException(validationError)
        }

        private fun archiveValidationError(
            archive: PackageInfo?,
            installed: PackageInfo,
            expectedVersionName: String,
        ): String? {
            if (archive == null) return "Downloaded file is not a readable APK"
            if (archive.packageName != context.packageName) {
                return "Release APK package name does not match WallHub"
            }
            if (archive.versionName != expectedVersionName) {
                return "Release APK version does not match GitHub metadata"
            }
            if (archive.compatLongVersionCode() < installed.compatLongVersionCode()) {
                return "Release APK would downgrade the installed WallHub version"
            }
            val archiveSigners = archive.signerDigests()
            val installedSigners = installed.signerDigests()
            return when {
                archiveSigners.isEmpty() || archiveSigners != installedSigners ->
                    "Release APK signing certificate does not match installed WallHub"
                else -> null
            }
        }

        @Suppress("DEPRECATION")
        private fun PackageInfo.signerDigests(): Set<String> {
            val signerBytes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    signingInfo?.apkContentsSigners?.map { signature -> signature.toByteArray() }.orEmpty()
                } else {
                    signatures?.map { signature -> signature.toByteArray() }.orEmpty()
                }
            return signerBytes.mapTo(linkedSetOf()) { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            }
        }

        @Suppress("DEPRECATION")
        private fun PackageInfo.compatLongVersionCode(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

        private companion object {
            const val LATEST_RELEASE_URL =
                "https://api.github.com/repos/ChEnLeo-7/WallHub2.0-For-Android/releases/latest"
            const val USER_AGENT = "WallHub-Android-AppUpdater/1.0"
            const val UPDATE_DIRECTORY = "app-updates"
            const val VERIFIED_APK_NAME = "wallhub-latest-release.apk"
            const val COPY_BUFFER_BYTES = 64 * 1024
            const val PROGRESS_REPORT_INTERVAL_BYTES = 512 * 1024L
            const val MAX_APK_BYTES = 200L * 1024L * 1024L
        }
    }

internal fun parseLatestRelease(
    json: String,
    installedVersionName: String,
): AppReleaseInfo {
    val release = JSONObject(json)
    require(!release.optBoolean("draft", true)) { "Latest GitHub Release is still a draft" }
    require(!release.optBoolean("prerelease", true)) { "Latest GitHub Release is a prerelease" }
    val tagName = release.getString("tag_name").trim()
    val versionName = tagName.removePrefix("v").removePrefix("V")
    require(parseSemanticVersion(versionName) != null) { "Latest Release tag is not a semantic version" }
    val htmlUrl = release.getString("html_url").requireOfficialReleasePageUrl(tagName)
    val notes = release.optString("body")
    val assets = release.getJSONArray("assets")
    val universalAssets =
        buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name").trim()
                val contentType = asset.optString("content_type").trim()
                if (
                    name.endsWith("-universal.apk", ignoreCase = true) &&
                    contentType == APK_CONTENT_TYPE
                ) {
                    add(asset)
                }
            }
        }
    require(universalAssets.size == 1) { "Latest Release must contain exactly one universal APK" }
    val asset = universalAssets.single()
    val assetName = asset.getString("name").trim()
    val assetUrl =
        asset
            .getString("browser_download_url")
            .requireOfficialAssetUrl(tagName, assetName)
    val assetSize = asset.getLong("size")
    require(assetSize > 0L) { "Universal APK size is missing" }
    val sha256 =
        extractReleaseChecksum(
            digest = asset.optString("digest"),
            body = notes,
            assetName = assetName,
        ) ?: error("Universal APK SHA-256 is missing")
    return AppReleaseInfo(
        tagName = tagName,
        versionName = versionName,
        releaseName = release.optString("name").ifBlank { tagName },
        notes = notes,
        publishedAt = release.optString("published_at"),
        htmlUrl = htmlUrl,
        assetName = assetName,
        assetUrl = assetUrl,
        assetSizeBytes = assetSize,
        sha256 = sha256,
        isNewer = compareSemanticVersions(versionName, installedVersionName) > 0,
    )
}

internal fun extractReleaseChecksum(
    digest: String,
    body: String,
    assetName: String,
): String? {
    val digestValue = digest.trim().removePrefix("sha256:")
    if (digestValue.matches(SHA_256_REGEX)) return digestValue.lowercase(Locale.ROOT)
    val checksumLine = body.lineSequence().firstOrNull { line -> assetName in line } ?: return null
    return SHA_256_REGEX.find(checksumLine)?.value?.lowercase(Locale.ROOT)
}

internal fun compareSemanticVersions(
    left: String,
    right: String,
): Int {
    val leftParts = parseSemanticVersion(left) ?: return 0
    val rightParts = parseSemanticVersion(right) ?: return 0
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

private fun parseSemanticVersion(value: String): List<Int>? {
    val normalized =
        value
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
    if (!normalized.matches(Regex("\\d+(?:\\.\\d+)*"))) return null
    val parts = mutableListOf<Int>()
    normalized.split('.').forEach { part -> parts += part.toIntOrNull() ?: return null }
    return parts.takeIf { it.isNotEmpty() }
}

private fun String.requireOfficialReleasePageUrl(tagName: String): String {
    requireOfficialGitHubUri(
        expectedPath = "$OFFICIAL_RELEASE_PATH/tag/$tagName",
        description = "Release page",
    )
    return this
}

private fun String.requireOfficialAssetUrl(
    tagName: String,
    assetName: String,
): String {
    requireOfficialGitHubUri(
        expectedPath = "$OFFICIAL_RELEASE_PATH/download/$tagName/$assetName",
        description = "Release asset",
    )
    return this
}

private fun String.requireOfficialGitHubUri(
    expectedPath: String,
    description: String,
) {
    val uri = runCatching { URI(this) }.getOrNull()
    require(
        uri?.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            uri.path == expectedPath,
    ) {
        "$description URL must exactly match the official WallHub Release"
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
private const val OFFICIAL_RELEASE_PATH = "/ChEnLeo-7/WallHub2.0-For-Android/releases"
private val SHA_256_REGEX = Regex("(?i)[a-f0-9]{64}")

private suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            },
        )
    }
