package com.wallhub.android.feature.setup

import com.wallhub.android.data.steam.cmCandidateCount
import com.wallhub.android.data.steam.cmCandidatesFromBody
import com.wallhub.android.data.steam.CM_DIRECTORY_URL
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
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
private const val SETUP_PROBE_BODY_LIMIT = 512L * 1024L
private const val SETUP_PROBE_WORKSHOP_ID = 3799142774L

internal enum class SetupNetworkCheck {
    DIRECTORY,
    PUBLIC_DETAILS_JSON,
    COMMUNITY_HTML,
    WORKSHOP_IMAGE,
    CM_TRANSPORT,
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
    suspend fun run(input: SetupNetworkPreflightInput): SetupNetworkPreflightResult {
        if (!input.automaticAntiBlockingEnabled) {
            return SetupNetworkPreflightResult(
                SetupNetworkOverallStatus.SKIPPED,
                SetupNetworkCheck.entries.map {
                    SetupNetworkCheckResult(it, SetupNetworkCheckStatus.SKIPPED, reason = "Automatic anti-blocking is off")
                },
            )
        }
        val permits = Semaphore(3)
        val items = coroutineScope {
            SetupNetworkCheck.entries.map { check ->
                async {
                    permits.withPermit {
                        val started = nowMillis()
                        try {
                            val timeoutMillis =
                                if (
                                    check == SetupNetworkCheck.DIRECTORY ||
                                    check == SetupNetworkCheck.COMMUNITY_HTML
                                ) {
                                    SETUP_WEB_PROBE_TIMEOUT_MS
                                } else {
                                    SETUP_PROBE_TIMEOUT_MS
                                }
                            when (val outcome = withTimeout(timeoutMillis) { probes.getValue(check).run() }) {
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
                }
            }.awaitAll()
        }
        val overall = if (items.any { it.status == SetupNetworkCheckStatus.FAILED }) {
            SetupNetworkOverallStatus.WARNING
        } else {
            SetupNetworkOverallStatus.PASSED
        }
        return SetupNetworkPreflightResult(overall, items)
    }
}

@Singleton
class SetupNetworkPreflight
@Inject constructor(
    httpClientFactory: SteamHttpClientFactory,
    private val accessManager: SteamAccessManager,
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

    private val runner = SetupNetworkPreflightRunner(
        mapOf(
            SetupNetworkCheck.DIRECTORY to SetupNetworkProbe { probeDirectory() },
            SetupNetworkCheck.PUBLIC_DETAILS_JSON to SetupNetworkProbe { probeDetails(includeImage = false) },
            SetupNetworkCheck.COMMUNITY_HTML to SetupNetworkProbe { probeCommunity() },
            SetupNetworkCheck.WORKSHOP_IMAGE to SetupNetworkProbe { probeDetails(includeImage = true) },
            SetupNetworkCheck.CM_TRANSPORT to SetupNetworkProbe {
                SetupProbeOutcome.Skipped("No safe anonymous CM transport probe")
            },
        ),
    )

    internal suspend fun run(input: SetupNetworkPreflightInput): SetupNetworkPreflightResult {
        accessManager.awaitEnhancedConnectionEnabled(input.automaticAntiBlockingEnabled)
        return runner.run(input)
    }

    private suspend fun probeDirectory(): SetupProbeOutcome {
        val body = getBounded(CM_DIRECTORY_URL, "application/json", anonymousWebClient)
        val count = cmCandidateCount(cmCandidatesFromBody(body))
        if (count == 0) throw IOException("No supported CM endpoints")
        return SetupProbeOutcome.Passed("$count CM endpoints")
    }

    private suspend fun probeDetails(includeImage: Boolean): SetupProbeOutcome {
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post(FormBody.Builder().add("itemcount", "1").add("publishedfileids[0]", SETUP_PROBE_WORKSHOP_ID.toString()).build())
            .header("Accept", "application/json")
            .build()
        val body = executeBounded(request, anonymousWebClient)
        val detail = JSONObject(body).optJSONObject("response")?.optJSONArray("publishedfiledetails")?.optJSONObject(0)
            ?: throw IOException("Invalid public details JSON")
        if (detail.optInt("result") != 1) throw IOException("Public details rejected")
        if (!includeImage) return SetupProbeOutcome.Passed("Valid public details")
        val imageUrl = detail.optString("preview_url").takeIf { it.startsWith("https://") }
            ?: throw IOException("No HTTPS Workshop image")
        val imageRequest = Request.Builder().url(imageUrl).header("Accept", "image/*").build()
        val type =
            withContext(Dispatchers.IO) {
                anonymousWebClient.newCall(imageRequest).await().use { response ->
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
    ): String =
        executeBounded(Request.Builder().url(url).header("Accept", accept).build(), httpClient)

    private suspend fun executeBounded(
        request: Request,
        httpClient: OkHttpClient = client,
    ): String =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > SETUP_PROBE_BODY_LIMIT) throw IOException("Response too large")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val remaining = SETUP_PROBE_BODY_LIMIT + 1L - output.size()
                        if (remaining <= 0L) throw IOException("Response too large")
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
}

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
