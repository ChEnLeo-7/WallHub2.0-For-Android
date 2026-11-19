package com.wallhub.android.data.steamaccess

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Decides whether Steam is reachable over one concrete egress.
 *
 * The verdict is made from real Steam targets over the same egress the app will use, and every failure
 * keeps its own reason. Google's 204 endpoint is a separate auxiliary signal: it is recorded for
 * diagnostics and never decides whether Steam is blocked.
 *
 * The baseline only counts as [SteamEnvironment.Open] when *every* required plane answered. A first
 * answer is not a verdict: on a network that blocks Community HTML while the Web API still responds,
 * stopping at the first success classified the blocked plane as open, and that verdict is what forbids
 * the enhancement the blocked plane needs.
 */
@Singleton
internal class SteamNetworkEnvironmentProbe
    internal constructor(
        private val targets: List<String>,
    ) {
        @Inject
        constructor() : this(STEAM_PROBE_TARGETS)

        suspend fun probe(egress: SteamEgress): SteamEnvironment {
            val viaProxy = egress is SteamEgress.Proxy
            val proxyAuth = SteamProxyAuthChallenge()
            val client = clientFor(egress, proxyAuth) ?: return SteamEnvironment.Unknown
            val failures = mutableListOf<SteamBlockReason>()
            for (target in targets) {
                // The challenge belongs to one attempt: a 407 one target saw says nothing about the next
                // target's failure, and the targets run strictly one after another.
                proxyAuth.reset()
                val request =
                    Request
                        .Builder()
                        .url(target)
                        .header("Cache-Control", "no-cache")
                        .header("Connection", "close")
                        .build()
                val outcome =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful && response.body.byteStream().read() >= 0) {
                                    null
                                } else {
                                    classifySteamProbeFailure(
                                        status = response.code,
                                        error = null,
                                        viaProxy = viaProxy,
                                        proxyAuthChallenged = proxyAuth.wasChallenged,
                                    )
                                }
                            }
                        }.getOrElse { error ->
                            classifySteamProbeFailure(
                                status = null,
                                error = error,
                                viaProxy = viaProxy,
                                proxyAuthChallenged = proxyAuth.wasChallenged,
                            )
                        }
                    }
                if (outcome != null) failures += outcome
            }
            return if (failures.isEmpty()) {
                SteamEnvironment.Open
            } else {
                SteamEnvironment.Blocked(mostActionable(failures))
            }
        }

        /** Auxiliary only: proves the device has *some* internet, never that Steam is unblocked. */
        suspend fun auxiliaryInternetReachable(): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    OkHttpClient
                        .Builder()
                        .dns(Dns.SYSTEM)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build()
                        .newCall(
                            Request
                                .Builder()
                                .url(GOOGLE_CONNECTIVITY_CHECK_URL)
                                .header("Cache-Control", "no-cache")
                                .header("Connection", "close")
                                .build(),
                        ).execute().use { response -> response.code == 204 }
                }.getOrDefault(false)
            }

        private fun clientFor(
            egress: SteamEgress,
            proxyAuth: SteamProxyAuthChallenge,
        ): OkHttpClient? =
            when (egress) {
                is SteamEgress.Proxy ->
                    baseClient()
                        .proxy(requireNotNull(egress.toJavaProxy()) { "Proxy egress has no socket address" })
                        // OkHttp reports a tunnel 407 as a bare IOException when no authenticator answers.
                        // Recording the challenge keeps that fact without ever producing a credential:
                        // this authenticator always declines, so no Proxy-Authorization is written for a
                        // credential the app does not own.
                        .proxyAuthenticator { _, response -> proxyAuth.observe(response) }
                        .build()
                // A direct plan is probed with an explicit NO_PROXY so the probe cannot re-read the selector
                // and disagree with the snapshot it was asked to verify.
                SteamEgress.Direct -> baseClient().proxy(Proxy.NO_PROXY).build()
                is SteamEgress.Bridge, is SteamEgress.Unavailable -> null
            }

        /** System DNS is deliberate: this is the baseline the app had before any enhancement. */
        private fun baseClient(): OkHttpClient.Builder =
            OkHttpClient
                .Builder()
                .dns(Dns.SYSTEM)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

/**
 * Notes that a proxy demanded credentials for a tunnel, and never answers the demand.
 *
 * OkHttp sends CONNECT for an HTTPS target and, when the proxy replies 407, asks the client's
 * `proxyAuthenticator` for a retry request. With no configured credential the authenticator returns
 * null and OkHttp closes the tunnel with a bare `IOException("Failed to authenticate with proxy")` -
 * no status, no response, no cause. That is why the caller's status-based classification never saw a
 * 407. Observing the challenge here keeps the fact the status used to carry, while sending nothing:
 * this type has no credential to send, so declining is the only thing it can do.
 */
internal class SteamProxyAuthChallenge {
    private val challenged = AtomicBoolean(false)

    /** Answers every 407 with "no credential"; the proxy sees no Proxy-Authorization from this path. */
    fun observe(response: Response): Request? {
        if (response.code == HTTP_PROXY_AUTH_REQUIRED) challenged.set(true)
        return null
    }

    /** Starts a new attempt, so one target's 407 cannot describe the next target's failure. */
    fun reset() {
        challenged.set(false)
    }

    val wasChallenged: Boolean get() = challenged.get()

    private companion object {
        const val HTTP_PROXY_AUTH_REQUIRED = 407
    }
}

/** OkHttp's own tunnel refusal: a 407 it could not answer with a credential. */
private fun isProxyAuthenticationFailure(error: Throwable): Boolean =
    error is IOException && error.message?.contains(PROXY_AUTH_FAILURE_MARKER) == true

/**
 * Maps one failed Steam probe to an operator-actionable reason. Order matters: a status code is more
 * specific than the transport error that delivered it.
 *
 * Proxy authentication is only ever reported for a probe that was actually sent through a proxy, and
 * only from evidence that the proxy demanded credentials: an observed 407 challenge for this attempt,
 * or OkHttp's own "failed to authenticate with proxy" tunnel refusal. A DNS, TLS, timeout, connect or
 * server-status failure never becomes a 407 no matter which transport delivered it - and a 407 an
 * origin server answers directly is the origin rejecting the request, not a proxy asking to authenticate.
 */
internal fun classifySteamProbeFailure(
    status: Int?,
    error: Throwable?,
    viaProxy: Boolean,
    proxyAuthChallenged: Boolean = false,
): SteamBlockReason {
    if (status != null) {
        when {
            status == 407 && viaProxy -> return SteamBlockReason.PROXY_AUTH_REQUIRED
            status == 429 -> return SteamBlockReason.RATE_LIMITED
            status in 400..599 -> return SteamBlockReason.SERVER_REJECTED
        }
    }
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (wasProxyAuthenticationDemanded(viaProxy, proxyAuthChallenged, chain)) {
        return SteamBlockReason.PROXY_AUTH_REQUIRED
    }
    return classifyTransportFailure(chain, viaProxy)
}

/**
 * Whether this failure is the proxy refusing the app rather than the network refusing Steam.
 *
 * Both inputs are evidence that the proxy asked for a credential, and neither can exist without a proxy
 * egress: a status the tunnel never exposed, or OkHttp's own refusal to answer one.
 */
private fun wasProxyAuthenticationDemanded(
    viaProxy: Boolean,
    proxyAuthChallenged: Boolean,
    chain: List<Throwable>,
): Boolean = viaProxy && (proxyAuthChallenged || chain.any(::isProxyAuthenticationFailure))

private fun classifyTransportFailure(
    chain: List<Throwable>,
    viaProxy: Boolean,
): SteamBlockReason =
    when {
        chain.any { it is UnknownHostException } ->
            if (viaProxy) SteamBlockReason.CONNECT_FAILURE else SteamBlockReason.DNS_FAILURE
        chain.any { it is SSLException || it is CertificateException } -> SteamBlockReason.TLS_FAILURE
        chain.any { it is SocketTimeoutException } -> SteamBlockReason.TIMEOUT
        chain.any { it is ConnectException } -> SteamBlockReason.CONNECT_FAILURE
        else -> SteamBlockReason.CONNECT_FAILURE
    }

private const val PROXY_AUTH_FAILURE_MARKER = "Failed to authenticate with proxy"

private fun mostActionable(reasons: List<SteamBlockReason>): SteamBlockReason =
    reasons.minByOrNull { reason -> ACTION_PRIORITY.indexOf(reason).takeIf { it >= 0 } ?: ACTION_PRIORITY.size }
        ?: SteamBlockReason.CONNECT_FAILURE

private val ACTION_PRIORITY =
    listOf(
        SteamBlockReason.PROXY_AUTH_REQUIRED,
        SteamBlockReason.TLS_FAILURE,
        SteamBlockReason.DNS_FAILURE,
        SteamBlockReason.RATE_LIMITED,
        SteamBlockReason.TIMEOUT,
        SteamBlockReason.SERVER_REJECTED,
        SteamBlockReason.CONNECT_FAILURE,
    )

/** Real Steam endpoints the app itself calls anonymously; no credentials are sent. */
internal val STEAM_PROBE_TARGETS =
    listOf(
        "https://api.steampowered.com/ISteamWebAPIUtil/GetSupportedAPIList/v1/?format=json",
        "https://steamcommunity.com/workshop/browse/?appid=431960&numperpage=1",
    )

internal val STEAM_SYSTEM_PROXY_TARGETS =
    listOf(
        java.net.URI("https://api.steampowered.com/"),
        java.net.URI("https://steamcommunity.com/"),
        java.net.URI("https://cdn.steamcontent.com/"),
    )

internal const val GOOGLE_CONNECTIVITY_CHECK_URL = "https://www.google.com/generate_204"
internal const val STEAM_ENVIRONMENT_OPEN_TTL_MS = 5 * 60_000L
internal const val STEAM_ENVIRONMENT_RESTRICTED_TTL_MS = 60_000L
internal const val STEAM_ENVIRONMENT_NETWORK_DEBOUNCE_MS = 500L
internal const val STEAM_ENVIRONMENT_WARNING_REPROBE_COOLDOWN_MS = 60_000L
private const val PROBE_TIMEOUT_SECONDS = 3L
