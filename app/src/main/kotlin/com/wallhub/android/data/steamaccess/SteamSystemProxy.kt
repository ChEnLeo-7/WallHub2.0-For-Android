package com.wallhub.android.data.steamaccess

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

internal data class SteamProxySelection(
    val endpoint: SteamProxyEndpoint?,
    val failure: SteamProxyAmbiguity? = null,
)

@Singleton
internal class SteamSystemProxyResolver
    @Inject
    constructor() {
        private val selector = ProxySelector.getDefault()

        fun resolve(targets: List<URI>): SteamSystemProxy = resolveSteamSystemProxy(selector, targets)
    }

/**
 * Resolves the system proxy for every Steam target and reports one of four outcomes.
 *
 * The app can only build a route for a single uniform proxy. Anything else - an empty answer, several
 * proxies, targets that disagree, or a selector that throws - is reported as such. Treating those as
 * "direct" is what silently bypassed a working proxy, so this function never does.
 */
internal fun resolveSteamSystemProxy(
    selector: ProxySelector?,
    targets: List<URI>,
): SteamSystemProxy {
    if (selector == null || targets.isEmpty()) return SteamSystemProxy.Unavailable
    val selections = mutableListOf<SteamProxySelection>()
    for (target in targets) {
        val selected =
            runCatching { selector.select(target) }.getOrElse {
                return SteamSystemProxy.Unavailable
            } ?: return SteamSystemProxy.Unavailable
        selections +=
            when {
                selected.isEmpty() -> SteamProxySelection(null, SteamProxyAmbiguity.EmptySelection)
                selected.size != 1 -> SteamProxySelection(null, SteamProxyAmbiguity.MultipleProxies)
                else -> selected.single().toSteamProxySelection()
            }
    }
    selections.firstOrNull { it.failure != null }?.let { failed ->
        return SteamSystemProxy.Ambiguous(requireNotNull(failed.failure))
    }
    val distinct = selections.mapNotNull(SteamProxySelection::endpoint).distinct()
    if (selections.any { it.endpoint == null } && distinct.isNotEmpty()) {
        return SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.InconsistentTargets)
    }
    if (distinct.size > 1) return SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.InconsistentTargets)
    return distinct.singleOrNull()?.let(SteamSystemProxy::Present) ?: SteamSystemProxy.Absent
}

private fun Proxy.toSteamProxySelection(): SteamProxySelection {
    if (type() == Proxy.Type.DIRECT) return SteamProxySelection(null)
    val socket = address() as? InetSocketAddress ?: return SteamProxySelection(null, SteamProxyAmbiguity.UnsupportedScheme)
    val host = socket.hostString.takeIf(String::isNotBlank) ?: return SteamProxySelection(null, SteamProxyAmbiguity.UnsupportedScheme)
    val port = socket.port.takeIf { it in 1..65_535 } ?: return SteamProxySelection(null, SteamProxyAmbiguity.UnsupportedScheme)
    val kind =
        when (type()) {
            Proxy.Type.HTTP -> SteamProxyKind.HTTP
            Proxy.Type.SOCKS -> SteamProxyKind.SOCKS
            else -> return SteamProxySelection(null, SteamProxyAmbiguity.UnsupportedScheme)
        }
    return SteamProxySelection(SteamProxyEndpoint(kind, host.trim('[', ']'), port))
}

/** The Java proxy a request must use for a planned egress, or null when the plan is direct. */
internal fun SteamEgress.toJavaProxy(): Proxy? =
    when (this) {
        is SteamEgress.Proxy ->
            Proxy(
                if (endpoint.kind == SteamProxyKind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(endpoint.host, endpoint.port),
            )
        else -> null
    }

/**
 * Parses the user's configured proxy into the same endpoint the plan carries.
 *
 * Credentials, query data and fragments are rejected rather than silently dropped: a proxy URL that
 * carries them cannot be represented by this app, and pretending otherwise is how a credential leaks.
 */
internal fun steamProxyEndpointOrNull(raw: String?): SteamProxyEndpoint? {
    val uri = parseProxyUri(raw) ?: return null
    val kind = proxyKindFor(uri.scheme) ?: return null
    val host = uri.host?.takeIf(String::isNotBlank) ?: return null
    val port = uri.port.takeIf { it in 1..65_535 } ?: defaultProxyPort(kind, uri.scheme)
    return runCatching { SteamProxyEndpoint(kind, host.trim('[', ']'), port) }.getOrNull()
}

private fun parseProxyUri(raw: String?): URI? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf {
        it.userInfo == null && it.query == null && it.fragment == null
    }
}

private fun proxyKindFor(scheme: String?): SteamProxyKind? =
    when (scheme?.lowercase()) {
        "http", "https" -> SteamProxyKind.HTTP
        "socks", "socks5" -> SteamProxyKind.SOCKS
        else -> null
    }

private fun defaultProxyPort(
    kind: SteamProxyKind,
    scheme: String?,
): Int =
    when {
        kind == SteamProxyKind.SOCKS -> 1080
        scheme?.lowercase() == "https" -> 443
        else -> 8080
    }
