package com.wallhub.android.feature.setup

import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.data.discover.DiscoverCuratorManifestParser
import com.wallhub.android.data.discover.DiscoverCuratorNoSniTransport
import com.wallhub.android.data.steam.cmCandidateCount
import com.wallhub.android.data.steam.cmCandidatesFromBody
import com.wallhub.android.data.steam.CM_DIRECTORY_URL
import com.wallhub.android.data.steam.MAX_CM_LIST_CACHE_BYTES
import com.wallhub.android.data.steamaccess.SteamEgressDecisionSource
import com.wallhub.android.data.steamaccess.SteamEnvironment
import com.wallhub.android.data.steamaccess.steamEnvironmentLabel
import com.wallhub.android.data.steamaccess.SteamEgressPlan
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import com.wallhub.android.data.update.GitHubNetworkClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
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
internal const val SETUP_IMAGE_PROBE_TIMEOUT_MS = 34_000L
internal const val SETUP_GITHUB_PROBE_TIMEOUT_MS = 12_000L
internal const val SETUP_CURATOR_PROBE_TIMEOUT_MS = 12_000L

/** How long the page waits for a decided egress before measuring whatever plan exists. */
internal const val SETUP_EGRESS_DECISION_WAIT_MS = 25_000L

/** One re-measure when the egress moved mid-check. More than that is a churning network, not a check. */
private const val SETUP_EGRESS_MAX_ATTEMPTS = 2
private const val SETUP_PROBE_BODY_LIMIT = 512L * 1024L
private const val SETUP_PROBE_WORKSHOP_ID = 3799142774L
/**
 * Probes the repository, not `releases/latest`: GitHub answers 404 for that path whenever the repo has no
 * published (non-draft, non-prerelease) release, which says nothing about whether the API is reachable.
 */
private const val GITHUB_API_PROBE_URL =
    "https://api.github.com/repos/ChEnLeo-7/WallHub2.0-For-Android"
private const val CURATOR_PROBE_URL = "https://wallpaperengineapi.com/api/explore/v1"

internal enum class SetupNetworkCheck {
    DIRECTORY,
    PUBLIC_DETAILS_JSON,
    COMMUNITY_HTML,
    WORKSHOP_IMAGE,
    GITHUB_API,
    CURATOR_API,
}

/**
 * Where a failed check actually stopped.
 *
 * Every failure used to arrive as the word "timeout", which says nothing about whether the item failed in
 * name resolution, on the TCP connect, in the TLS handshake, at the HTTP status, on the content itself or
 * because the page's own budget expired. The stage is what an operator acts on, so it is recorded
 * separately from the (unchanged) user-visible reason.
 */
internal enum class SetupProbeStage {
    PASSED,
    DNS,
    TCP,
    TLS,
    HTTP,
    CONTENT,
    TIMEOUT,
    BUDGET,
    UNKNOWN,
}

/** A probe reached Steam but Steam answered with a status. */
internal class SetupProbeHttpException(
    val statusCode: Int,
) : IOException("HTTP $statusCode")

/** A probe got a response but not the resource it asked for. */
internal class SetupProbeContentException(
    reason: String,
) : IOException(reason)

internal fun setupProbeStage(error: Throwable?): SetupProbeStage {
    if (error == null) return SetupProbeStage.PASSED
    if (error is TimeoutCancellationException) return SetupProbeStage.BUDGET
    val chain = generateSequence(error) { current -> current.cause }.take(8).toList()
    return when {
        chain.any { it is SetupProbeHttpException } -> SetupProbeStage.HTTP
        chain.any { it is SetupProbeContentException } -> SetupProbeStage.CONTENT
        chain.any { it is UnknownHostException } -> SetupProbeStage.DNS
        chain.any { it is SSLException || it is CertificateException } -> SetupProbeStage.TLS
        // OkHttp reports its own call deadline as InterruptedIOException("timeout").
        chain.any { it is SocketTimeoutException || it is InterruptedIOException } -> SetupProbeStage.TIMEOUT
        chain.any { it is ConnectException || it is NoRouteToHostException } -> SetupProbeStage.TCP
        else -> SetupProbeStage.UNKNOWN
    }
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
    val stage: SetupProbeStage = SetupProbeStage.UNKNOWN,
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
        isCurrent: () -> Boolean = { true },
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
                        // A snapshot measured for an egress that has since moved must never be shown:
                        // that is how a Direct measurement described a network the bridge already served.
                        if (isCurrent()) onUpdate(preflightSnapshot(completed))
                    }
                    item
                }
            }.awaitAll()
        }
        return completedPreflightResult(items)
    }

    // The probe contract is "every failure becomes a result with its stage", so the catch is deliberately
    // broad and the budget expiry deliberately becomes the displayed reason.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
                    SetupProbeStage.PASSED,
                )
                is SetupProbeOutcome.Skipped -> SetupNetworkCheckResult(
                    check,
                    SetupNetworkCheckStatus.SKIPPED,
                    (nowMillis() - started).coerceAtLeast(0L),
                    outcome.reason,
                    SetupProbeStage.UNKNOWN,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            SetupNetworkCheckResult(
                check,
                SetupNetworkCheckStatus.FAILED,
                (nowMillis() - started).coerceAtLeast(0L),
                "Timed out",
                SetupProbeStage.BUDGET,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SetupNetworkCheckResult(
                check,
                SetupNetworkCheckStatus.FAILED,
                (nowMillis() - started).coerceAtLeast(0L),
                error.shortProbeReason(),
                setupProbeStage(error),
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
    private val egress: SteamEgressDecisionSource,
    private val diagnostics: DiagnosticRepository,
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
    ): SetupNetworkPreflightResult =
        runSetupNetworkCheckOnLatestEgress(egress) { plan ->
            val result = runOnce(input, plan, onUpdate)
            recordConnectionCheck(plan, result)
            result
        }

    /**
     * Measures the six items against one already-decided plan.
     *
     * The Steam planes read the plan per request through the shared proxy selector, so the only thing this
     * function decides is how long each item may take. It must not decide *which egress* the item is on:
     * the plan it is handed is the decided one, and a bridge that lands mid-check re-runs the whole check
     * through [runSetupNetworkCheckOnLatestEgress] instead of leaving half the items on the old route.
     */
    private suspend fun runOnce(
        input: SetupNetworkPreflightInput,
        plan: SteamEgressPlan,
        onUpdate: (SetupNetworkPreflightResult) -> Unit,
    ): SetupNetworkPreflightResult {
        val key = egressSnapshotKey(plan)
        val isCurrent = { egressSnapshotKey(egress.publishedEgressPlan()) == key }
        val githubProbe: suspend () -> SetupProbeOutcome = {
            probeGithub(if (input.automaticAntiBlockingEnabled) githubEnhancedClient else githubDirectClient)
        }
        val curatorProbe: suspend () -> SetupProbeOutcome = {
            if (input.automaticAntiBlockingEnabled) probeCuratorNoSni() else probeCuratorDirect()
        }
        // GitHub and the curator use their own enhanced transports, so their probe choice follows the saved
        // switch. The Steam budgets follow the decided egress: a bridged plan is what may legitimately need
        // the longer budget, and a decided direct plan must not stretch every check to a minute.
        val steamInput = input.copy(automaticAntiBlockingEnabled = input.automaticAntiBlockingEnabled && plan.usesBridge)
        if (!plan.usesBridge) {
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
            ).run(steamInput, onUpdate, isCurrent)
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
                runner.run(steamInput, onUpdate, isCurrent)
            } finally {
                publicDetail.cancel()
            }
        }
    }

    private suspend fun recordConnectionCheck(
        plan: SteamEgressPlan,
        result: SetupNetworkPreflightResult,
    ) {
        try {
            diagnostics.record(
                DiagnosticEvent(
                    source = "steam-access",
                    level =
                        if (result.overallStatus == SetupNetworkOverallStatus.PASSED) {
                            DiagnosticLevel.INFO
                        } else {
                            DiagnosticLevel.WARNING
                        },
                    message = "Steam connection check completed",
                    attributes = setupNetworkCheckDiagnosticAttributes(plan, result),
                ),
            )
        } catch (cancelled: CancellationException) {
            // The diagnostic write is best effort, but a cancelled page must still stop the pass: folding
            // cancellation into "record failed" would let a pass finish and publish after the user left.
            throw cancelled
        } catch (_: Throwable) {
            // Diagnostics are best-effort.
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
                .url(GITHUB_API_PROBE_URL)
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
        val repository = runCatching { JSONObject(body) }.getOrNull() ?: throw SetupProbeContentException("Invalid GitHub JSON")
        if (repository.optString("full_name").isBlank()) {
            throw SetupProbeContentException("Unexpected GitHub Repository JSON")
        }
        return SetupProbeOutcome.Passed("GitHub API")
    }

    private suspend fun probeCuratorNoSni(): SetupProbeOutcome {
        val response = curatorNoSniTransport.get(CURATOR_PROBE_URL, etag = null, lastModified = null)
        if (response.statusCode != 200) throw SetupProbeHttpException(response.statusCode)
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
        if (count == 0) throw SetupProbeContentException("No supported CM endpoints")
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
            ?: throw SetupProbeContentException("Invalid or rejected public details JSON")
    }

    // Three distinct content failures, each with its own operator-facing reason: the missing image URL,
    // a non-image body and an empty body. Collapsing them would lose which one happened.
    @Suppress("ThrowsCount")
    private suspend fun probeWorkshopImage(
        detail: JSONObject,
        httpClient: OkHttpClient,
    ): SetupProbeOutcome {
        val imageUrl = detail.optString("preview_url").takeIf { it.startsWith("https://") }
            ?: throw SetupProbeContentException("No HTTPS Workshop image")
        val imageRequest = Request.Builder().url(imageUrl).header("Accept", "image/*").build()
        val type =
            withContext(Dispatchers.IO) {
                httpClient.newCall(imageRequest).await().use { response ->
                    if (!response.isSuccessful) throw SetupProbeHttpException(response.code)
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';')
                    if (!contentType.startsWith("image/")) throw SetupProbeContentException("Unexpected image response")
                    if (response.body.byteStream().read() < 0) throw SetupProbeContentException("Empty image response")
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
            throw SetupProbeContentException("Unexpected Community HTML")
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
                if (!response.isSuccessful) throw SetupProbeHttpException(response.code)
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
                if (!response.isSuccessful) throw SetupProbeHttpException(response.code)
                readBoundedBody(response, bodyLimit)
            }
        }

    private fun readBoundedBody(
        response: Response,
        bodyLimit: Long,
    ): String {
        val body = response.body
        if (body.contentLength() > bodyLimit) throw SetupProbeContentException("Response too large")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { input ->
            while (true) {
                val remaining = bodyLimit + 1L - output.size()
                if (remaining <= 0L) throw SetupProbeContentException("Response too large")
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

/** A plan's identity for "did the egress move under this measurement?" - generation and egress kind. */
private fun egressSnapshotKey(plan: SteamEgressPlan): String =
    "${plan.generation}:${plan.egressIdentity ?: "undecided"}"

/**
 * Runs the whole connection check against the egress the app finally uses.
 *
 * Two rules that the field report needed, and that no timeout value can replace:
 *
 * 1. The check waits, bounded, for a *decided* plan. Measuring the provisional direct baseline is how a
 *    page reported four timeouts for a network whose bridge decision landed seconds later.
 * 2. A result measured for one egress is discarded if the egress moved while it ran, and the check runs
 *    once more against the new one. It never runs more than [SETUP_EGRESS_MAX_ATTEMPTS] times, so a
 *    churning network cannot turn one tap into an unbounded probe loop.
 */
internal suspend fun runSetupNetworkCheckOnLatestEgress(
    egress: SteamEgressDecisionSource,
    waitMillis: Long = SETUP_EGRESS_DECISION_WAIT_MS,
    attempt: suspend (SteamEgressPlan) -> SetupNetworkPreflightResult,
): SetupNetworkPreflightResult {
    var plan = egress.awaitDecidedEgressPlan(waitMillis) ?: return setupEgressUndecidedResult()
    var result = attempt(plan)
    var attempts = 1
    while (attempts < SETUP_EGRESS_MAX_ATTEMPTS) {
        val published = egress.publishedEgressPlan()
        if (egressSnapshotKey(published) == egressSnapshotKey(plan)) break
        // The egress moved, so the new one has to be decided too before it can be measured: re-waiting is
        // what stops the retry from repeating the stale-egress measurement the first wait already refused.
        plan = egress.awaitDecidedEgressPlan(waitMillis) ?: return setupEgressUndecidedResult()
        result = attempt(plan)
        attempts += 1
    }
    return result
}

/**
 * The honest answer when no egress was decided in the budget.
 *
 * Every item is reported as a budget failure instead of being measured on a route the app has not chosen.
 * The page therefore says "failed" with the stage that actually happened, which is the one thing a
 * timeout-shaped result must never do: invent a Direct verdict for a network whose egress is unknown.
 */
private fun setupEgressUndecidedResult(): SetupNetworkPreflightResult =
    SetupNetworkPreflightResult(
        overallStatus = SetupNetworkOverallStatus.WARNING,
        items =
            SetupNetworkCheck.entries.map { check ->
                SetupNetworkCheckResult(
                    check = check,
                    status = SetupNetworkCheckStatus.FAILED,
                    reason = "Steam egress undecided",
                    stage = SetupProbeStage.BUDGET,
                )
            },
    )

/**
 * One redacted summary of what the check measured and which egress it measured it on.
 *
 * It carries kinds, a generation and per-item status/stage - never a URL, a host list, a header or a
 * credential - because the export is meant to be handed to someone else.
 */
internal fun setupNetworkCheckDiagnosticAttributes(
    plan: SteamEgressPlan,
    result: SetupNetworkPreflightResult,
): Map<String, String> =
    buildMap {
        put("egress", plan.egressKindLabel)
        put("baseline", plan.baseline.kind.name.lowercase())
        put("generation", plan.generation.toString())
        put("environment", steamEnvironmentLabel(plan.environment))
        plan.problem?.let { put("problem", it.name) }
        result.items.forEach { item ->
            put(
                "check.${item.check.name}",
                buildString {
                    append(item.status.name.lowercase())
                    append(':').append(item.durationMillis)
                    if (item.status == SetupNetworkCheckStatus.FAILED) {
                        append(':').append(item.stage.name)
                    }
                },
            )
        }
    }

internal fun steamEnvironmentDiagnosticLabel(environment: SteamEnvironment): String =
    when (environment) {
        SteamEnvironment.Open -> "open"
        SteamEnvironment.Unknown -> "unknown"
        is SteamEnvironment.Blocked -> "blocked:${environment.reason.name.lowercase()}"
    }
