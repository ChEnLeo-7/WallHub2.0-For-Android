package com.wallhub.android.feature.setup

import com.wallhub.android.data.discover.DiscoverCuratorManifestParser
import com.wallhub.android.data.discover.DiscoverCuratorNoSniTransport
import com.wallhub.android.data.steam.cmCandidateCount
import com.wallhub.android.data.steam.cmCandidatesFromBody
import com.wallhub.android.data.steam.CM_DIRECTORY_URL
import com.wallhub.android.data.steam.MAX_CM_LIST_CACHE_BYTES
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import com.wallhub.android.data.update.GitHubNetworkClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SETUP_PROBE_TIMEOUT_MS = 5_000L
internal const val SETUP_WEB_PROBE_TIMEOUT_MS = 12_000L
internal const val SETUP_DIRECTORY_PROBE_TIMEOUT_MS = 34_000L
internal const val SETUP_DETAILS_PROBE_TIMEOUT_MS = 34_000L
internal const val SETUP_IMAGE_PROBE_TIMEOUT_MS = 68_000L
internal const val SETUP_GITHUB_PROBE_TIMEOUT_MS = 12_000L
internal const val SETUP_CURATOR_PROBE_TIMEOUT_MS = 20_000L
private const val SETUP_PROBE_BODY_LIMIT = 512L * 1024L
private const val SETUP_PROBE_WORKSHOP_ID = 3799142774L
private const val GITHUB_RELEASE_PROBE_URL =
    "https://api.github.com/repos/ChEnLeo-7/WallHub2.0-For-Android/releases/latest"
private const val CURATOR_PROBE_URL = "https://wallpaperengineapi.com/api/explore/v1"

internal enum class SetupNetworkCheck {
    DIRECTORY,
    PUBLIC_DETAILS_JSON,
    COMMUNITY_HTML,
    WORKSHOP_IMAGE,
    GITHUB_API,
    CURATOR_API,
}

internal fun setupProbeTimeoutMillis(
    check: SetupNetworkCheck,
    automaticAntiBlockingEnabled: Boolean,
): Long =
    when (check) {
        SetupNetworkCheck.DIRECTORY -> SETUP_DIRECTORY_PROBE_TIMEOUT_MS
        SetupNetworkCheck.PUBLIC_DETAILS_JSON ->
            if (automaticAntiBlockingEnabled) SETUP_DETAILS_PROBE_TIMEOUT_MS else SETUP_PROBE_TIMEOUT_MS
        SetupNetworkCheck.COMMUNITY_HTML -> SETUP_WEB_PROBE_TIMEOUT_MS
        SetupNetworkCheck.WORKSHOP_IMAGE ->
            if (automaticAntiBlockingEnabled) SETUP_IMAGE_PROBE_TIMEOUT_MS else SETUP_PROBE_TIMEOUT_MS
        SetupNetworkCheck.GITHUB_API -> SETUP_GITHUB_PROBE_TIMEOUT_MS
        SetupNetworkCheck.CURATOR_API ->
            if (automaticAntiBlockingEnabled) SETUP_CURATOR_PROBE_TIMEOUT_MS else SETUP_WEB_PROBE_TIMEOUT_MS
    }

internal enum class SetupNetworkCheckStatus { CHECKING, PASSED, FAILED, SKIPPED }

internal enum class SetupNetworkOverallStatus { CHECKING, PASSED, WARNING, SKIPPED }

internal data class SetupNetworkPreflightInput(val automaticAntiBlockingEnabled: Boolean)

internal data class SetupNetworkCheckResult(
    val check: SetupNetworkCheck,
    val status: SetupNetworkCheckStatus,
    val durationMillis: Long = 0L,
    val reason: String = "",
)

internal fun SetupNetworkCheckResult.compactLatencyLabel(): String? =
    durationMillis.takeIf { status == SetupNetworkCheckStatus.PASSED }?.let { "$it ms" }

internal fun SetupNetworkOverallStatus.showsContinueAction(): Boolean = this != SetupNetworkOverallStatus.CHECKING

internal fun SetupNetworkOverallStatus.showsRetryAction(): Boolean = this != SetupNetworkOverallStatus.CHECKING

internal data class SetupNetworkPreflightResult(
    val overallStatus: SetupNetworkOverallStatus,
    val items: List<SetupNetworkCheckResult>,
) {
    val mayContinue: Boolean = true

    companion object {
        fun checking(): SetupNetworkPreflightResult =
            SetupNetworkPreflightResult(
                SetupNetworkOverallStatus.CHECKING,
                SetupNetworkCheck.entries.map { SetupNetworkCheckResult(it, SetupNetworkCheckStatus.CHECKING) },
            )
    }
}

private class GitHubRateLimitException : IOException("GitHub API rate limit exceeded")

internal sealed interface SetupProbeOutcome {
    data class Passed(val reason: String) : SetupProbeOutcome
    data class Skipped(val reason: String) : SetupProbeOutcome
}

internal fun interface SetupNetworkProbe {
    suspend fun run(): SetupProbeOutcome
}

internal class SetupNetworkPreflightRunner(
    private val probes: Map<SetupNetworkCheck, SetupNetworkProbe>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        input: SetupNetworkPreflightInput,
        onUpdate: (SetupNetworkPreflightResult) -> Unit = {},
    ): SetupNetworkPreflightResult {
        val permits = Semaphore(3)
        val completed = linkedMapOf<SetupNetworkCheck, SetupNetworkCheckResult>()
        val publicationLock = Any()
        val items = coroutineScope {
            SetupNetworkCheck.entries.map { check ->
                async {
                    val item =
                        permits.withPermit {
                            runProbe(check, input.automaticAntiBlockingEnabled)
                        }
                    synchronized(publicationLock) {
                        completed[check] = item
                        onUpdate(preflightSnapshot(completed))
                    }
                    item
                }
            }.awaitAll()
        }
        return completedPreflightResult(items)
    }

    private suspend fun runProbe(
        check: SetupNetworkCheck,
        automaticAntiBlockingEnabled: Boolean,
    ): SetupNetworkCheckResult {
        val started = nowMillis()
        return try {
            when (
                val outcome =
                    withTimeout(setupProbeTimeoutMillis(check, automaticAntiBlockingEnabled)) {
                        probes.getValue(check).run()
                    }
            ) {
                is SetupProbeOutcome.Passed -> SetupNetworkCheckResult(
                    check,
                    SetupNetworkCheckStatus.PASSED,
                    (nowMillis() - started).coerceAtLeast(0L),
                    outcome.reason,
                )
                is SetupProbeOutcome.Skipped -> SetupNetworkCheckResult(
                    check,
                    SetupNetworkCheckStatus.SKIPPED,
                    (nowMillis() - started).coerceAtLeast(0L),
                    outcome.reason,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            SetupNetworkCheckResult(
                check,
                SetupNetworkCheckStatus.FAILED,
                (nowMillis() - started).coerceAtLeast(0L),
                "Timed out",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SetupNetworkCheckResult(
                check,
                SetupNetworkCheckStatus.FAILED,
                (nowMillis() - started).coerceAtLeast(0L),
                error.shortProbeReason(),
            )
        }
    }

    private fun preflightSnapshot(
        completed: Map<SetupNetworkCheck, SetupNetworkCheckResult>,
    ): SetupNetworkPreflightResult {
        val items =
            SetupNetworkCheck.entries.map { check ->
                completed[check] ?: SetupNetworkCheckResult(check, SetupNetworkCheckStatus.CHECKING)
            }
        return if (completed.size == SetupNetworkCheck.entries.size) {
            completedPreflightResult(items)
        } else {
            SetupNetworkPreflightResult(SetupNetworkOverallStatus.CHECKING, items)
        }
    }

    private fun completedPreflightResult(items: List<SetupNetworkCheckResult>): SetupNetworkPreflightResult {
        val overall =
            if (items.any { it.status == SetupNetworkCheckStatus.FAILED }) {
                SetupNetworkOverallStatus.WARNING
            } else {
                SetupNetworkOverallStatus.PASSED
            }
        return SetupNetworkPreflightResult(overall, items)
    }
}

@Singleton
class SetupNetworkPreflight
@Inject internal constructor(
    httpClientFactory: SteamHttpClientFactory,
    private val accessManager: SteamAccessManager,
    private val curatorNoSniTransport: DiscoverCuratorNoSniTransport,
    githubNetworkClient: GitHubNetworkClient,
) {
    private val client = httpClientFactory.newBuilder()
        .connectTimeout(SETUP_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SETUP_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val anonymousWebClient = httpClientFactory.newAnonymousReadBuilder()
        .connectTimeout(SETUP_WEB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SETUP_WEB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_WEB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val directoryClient = httpClientFactory.newAnonymousReadBuilder()
        .connectTimeout(SETUP_DIRECTORY_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SETUP_DIRECTORY_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_DIRECTORY_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val detailsImageClient = httpClientFactory.newAnonymousReadBuilder()
        .connectTimeout(SETUP_DETAILS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SETUP_DETAILS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_DETAILS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val githubDirectClient = githubNetworkClient.newBuilder(enhanced = false).setupProbeClient().build()
    private val githubEnhancedClient = githubNetworkClient.newBuilder(enhanced = true).setupProbeClient().build()
    private val curatorDirectClient = OkHttpClient.Builder().setupProbeClient().build()

    internal suspend fun run(
        input: SetupNetworkPreflightInput,
        onUpdate: (SetupNetworkPreflightResult) -> Unit = {},
    ): SetupNetworkPreflightResult {
        val githubProbe: suspend () -> SetupProbeOutcome = {
            probeGithub(if (input.automaticAntiBlockingEnabled) githubEnhancedClient else githubDirectClient)
        }
        val curatorProbe: suspend () -> SetupProbeOutcome = {
            if (input.automaticAntiBlockingEnabled) probeCuratorNoSni() else probeCuratorDirect()
        }
        val enhancedConnectionEnabled = accessManager.awaitEnhancedConnectionDecision()
        if (!enhancedConnectionEnabled) {
            return SetupNetworkPreflightRunner(
                commonProbes(
                    detailsProbe = {
                        fetchPublicDetail(client)
                        SetupProbeOutcome.Passed("Valid public details")
                    },
                    imageProbe = {
                        probeWorkshopImage(fetchPublicDetail(client), client)
                    },
                    githubProbe = githubProbe,
                    curatorProbe = curatorProbe,
                ),
            ).run(input, onUpdate)
        }
        return supervisorScope {
            val publicDetail = async {
                withTimeout(SETUP_DETAILS_PROBE_TIMEOUT_MS) { fetchPublicDetail(detailsImageClient) }
            }
            val runner =
                SetupNetworkPreflightRunner(
                    commonProbes(
                        detailsProbe = {
                            publicDetail.await()
                            SetupProbeOutcome.Passed("Valid public details")
                        },
                        imageProbe = {
                            probeWorkshopImage(publicDetail.await(), detailsImageClient)
                        },
                        githubProbe = githubProbe,
                        curatorProbe = curatorProbe,
                    ),
                )
            try {
                runner.run(input, onUpdate)
            } finally {
                publicDetail.cancel()
            }
        }
    }

    private fun commonProbes(
        detailsProbe: suspend () -> SetupProbeOutcome,
        imageProbe: suspend () -> SetupProbeOutcome,
        githubProbe: suspend () -> SetupProbeOutcome,
        curatorProbe: suspend () -> SetupProbeOutcome,
    ): Map<SetupNetworkCheck, SetupNetworkProbe> =
        mapOf(
            SetupNetworkCheck.DIRECTORY to SetupNetworkProbe { probeDirectory() },
            SetupNetworkCheck.PUBLIC_DETAILS_JSON to SetupNetworkProbe { detailsProbe() },
            SetupNetworkCheck.COMMUNITY_HTML to SetupNetworkProbe { probeCommunity() },
            SetupNetworkCheck.WORKSHOP_IMAGE to SetupNetworkProbe { imageProbe() },
            SetupNetworkCheck.GITHUB_API to SetupNetworkProbe { githubProbe() },
            SetupNetworkCheck.CURATOR_API to SetupNetworkProbe { curatorProbe() },
        )

    private suspend fun probeGithub(httpClient: OkHttpClient): SetupProbeOutcome {
        val request =
            Request
                .Builder()
                .url(GITHUB_RELEASE_PROBE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "WallHub-Android-Setup/1.0")
                .get()
                .build()
        val body =
            try {
                executeGitHubProbe(request, httpClient)
            } catch (_: GitHubRateLimitException) {
                return SetupProbeOutcome.Skipped("GitHub API rate limit exceeded")
            }
        val release = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("Invalid GitHub JSON")
        if (release.optString("tag_name").isBlank() || release.optJSONArray("assets") == null) {
            throw IOException("Unexpected GitHub Release JSON")
        }
        return SetupProbeOutcome.Passed("GitHub Release API")
    }

    private suspend fun probeCuratorNoSni(): SetupProbeOutcome {
        val response = curatorNoSniTransport.get(CURATOR_PROBE_URL, etag = null, lastModified = null)
        if (response.statusCode != 200) throw IOException("Curator HTTP ${response.statusCode}")
        DiscoverCuratorManifestParser.parse(response.body)
        return SetupProbeOutcome.Passed("Wallpaper curator API")
    }

    private suspend fun probeCuratorDirect(): SetupProbeOutcome {
        val body = getBounded(CURATOR_PROBE_URL, "application/json", curatorDirectClient)
        DiscoverCuratorManifestParser.parse(body)
        return SetupProbeOutcome.Passed("Wallpaper curator API")
    }

    private suspend fun probeDirectory(): SetupProbeOutcome {
        val body =
            getBounded(
                CM_DIRECTORY_URL,
                "application/json",
                directoryClient,
                MAX_CM_LIST_CACHE_BYTES,
            )
        val count = cmCandidateCount(cmCandidatesFromBody(body))
        if (count == 0) throw IOException("No supported CM endpoints")
        return SetupProbeOutcome.Passed("$count CM endpoints")
    }

    private suspend fun fetchPublicDetail(httpClient: OkHttpClient): JSONObject {
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post(FormBody.Builder().add("itemcount", "1").add("publishedfileids[0]", SETUP_PROBE_WORKSHOP_ID.toString()).build())
            .header("Accept", "application/json")
            .build()
        val body = executeBounded(request, httpClient)
        return JSONObject(body).optJSONObject("response")?.optJSONArray("publishedfiledetails")?.optJSONObject(0)
            ?.takeIf { detail -> detail.optInt("result") == 1 }
            ?: throw IOException("Invalid or rejected public details JSON")
    }

    private suspend fun probeWorkshopImage(
        detail: JSONObject,
        httpClient: OkHttpClient,
    ): SetupProbeOutcome {
        val imageUrl = detail.optString("preview_url").takeIf { it.startsWith("https://") }
            ?: throw IOException("No HTTPS Workshop image")
        val imageRequest = Request.Builder().url(imageUrl).header("Accept", "image/*").build()
        val type =
            withContext(Dispatchers.IO) {
                httpClient.newCall(imageRequest).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Image HTTP ${response.code}")
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';')
                    if (!contentType.startsWith("image/")) throw IOException("Unexpected image response")
                    if (response.body.byteStream().read() < 0) throw IOException("Empty image response")
                    contentType
                }
            }
        return SetupProbeOutcome.Passed(type)
    }

    private suspend fun probeCommunity(): SetupProbeOutcome {
        val body = getBounded(
            "https://steamcommunity.com/sharedfiles/filedetails/?id=$SETUP_PROBE_WORKSHOP_ID",
            "text/html",
            anonymousWebClient,
        )
        if (!body.contains("workshopItemTitle", ignoreCase = true) && !body.contains("PublishedFile", ignoreCase = true)) {
            throw IOException("Unexpected Community HTML")
        }
        return SetupProbeOutcome.Passed("Workshop page")
    }

    private suspend fun getBounded(
        url: String,
        accept: String,
        httpClient: OkHttpClient = client,
        bodyLimit: Long = SETUP_PROBE_BODY_LIMIT,
    ): String =
        executeBounded(Request.Builder().url(url).header("Accept", accept).build(), httpClient, bodyLimit)

    private suspend fun executeGitHubProbe(
        request: Request,
        httpClient: OkHttpClient,
    ): String =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).await().use { response ->
                if (response.code == 403 && response.header("X-RateLimit-Remaining") == "0") {
                    throw GitHubRateLimitException()
                }
                if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
                readBoundedBody(response, SETUP_PROBE_BODY_LIMIT)
            }
        }

    private suspend fun executeBounded(
        request: Request,
        httpClient: OkHttpClient = client,
        bodyLimit: Long = SETUP_PROBE_BODY_LIMIT,
    ): String =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                readBoundedBody(response, bodyLimit)
            }
        }

    private fun readBoundedBody(
        response: Response,
        bodyLimit: Long,
    ): String {
        val body = response.body
        if (body.contentLength() > bodyLimit) throw IOException("Response too large")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { input ->
            while (true) {
                val remaining = bodyLimit + 1L - output.size()
                if (remaining <= 0L) throw IOException("Response too large")
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

private fun OkHttpClient.Builder.setupProbeClient(): OkHttpClient.Builder =
    connectTimeout(SETUP_GITHUB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SETUP_GITHUB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_GITHUB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun Exception.shortProbeReason(): String =
    (message ?: javaClass.simpleName).replace(Regex("\\s+"), " ").take(96)
