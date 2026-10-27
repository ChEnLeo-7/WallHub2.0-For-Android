package com.wallhub.android.data.steamaccess

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

internal data class SteamSystemProxyRoute(
    val representable: Boolean,
    val sdkProxyUri: String?,
)

@Singleton
internal class SteamSystemProxyResolver
    @Inject
    constructor() {
        private val selector = ProxySelector.getDefault()

        fun resolve(targets: List<URI>): SteamSystemProxyRoute = resolveSteamSystemProxy(selector, targets)
    }

/** Resolves one egress that both the Java probe and the single-proxy SDK route can represent. */
internal fun resolveSteamSystemProxy(
    selector: ProxySelector?,
    targets: List<URI>,
): SteamSystemProxyRoute {
    if (targets.isEmpty()) return SteamSystemProxyRoute(representable = false, sdkProxyUri = null)
    val routes =
        targets.map { target ->
            val selected = runCatching { selector?.select(target) }.getOrNull()
                ?: return SteamSystemProxyRoute(representable = false, sdkProxyUri = null)
            if (selected.size != 1) return SteamSystemProxyRoute(representable = false, sdkProxyUri = null)
            selected.single().toSdkProxyUri()
                ?: return SteamSystemProxyRoute(representable = false, sdkProxyUri = null)
        }
    if (routes.distinct().size != 1) return SteamSystemProxyRoute(representable = false, sdkProxyUri = null)
    return SteamSystemProxyRoute(representable = true, sdkProxyUri = routes.first().takeIf(String::isNotEmpty))
}

private fun Proxy.toSdkProxyUri(): String? {
    if (type() == Proxy.Type.DIRECT) return ""
    val socket = address() as? InetSocketAddress ?: return null
    val host = socket.hostString.takeIf(String::isNotBlank) ?: return null
    val port = socket.port.takeIf { it in 1..65_535 } ?: return null
    val authorityHost = if (':' in host) "[$host]" else host
    val scheme = when (type()) {
        Proxy.Type.HTTP -> "http"
        Proxy.Type.SOCKS -> "socks"
        else -> return null
    }
    return "$scheme://$authorityHost:$port"
}
