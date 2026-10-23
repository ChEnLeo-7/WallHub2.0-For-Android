package com.wallhub.android.data.steamaccess

internal object SteamDomainPolicy {
    private val coreHosts =
        setOf(
            "steamcommunity.com",
            "www.steamcommunity.com",
            "api.steampowered.com",
            "community.steam-api.com",
        )

    // Exact first-party origins returned by Valve's official finalizelogin transfer flow.
    // They use a direct CONNECT tunnel because hidden-SNI acceleration breaks their TLS handshake.
    private val webSessionHosts =
        setOf(
            // The authenticated transfer and final voted-page GET both use this exact origin.
            "steamcommunity.com",
            "login.steampowered.com",
            "store.steampowered.com",
            "help.steampowered.com",
        )

    fun supports(hostname: String): Boolean {
        val host = normalize(hostname)
        return host in coreHosts || host in webSessionHosts ||
            (host.length > STEAM_CM_HOST_SUFFIX.length && host.endsWith(STEAM_CM_HOST_SUFFIX))
    }

    fun accelerates(hostname: String): Boolean {
        val host = normalize(hostname)
        return host in coreHosts || isCmHost(host)
    }

    fun usesDirectWebSessionTunnel(hostname: String): Boolean = normalize(hostname) in webSessionHosts

    fun requireSupported(hostname: String): String =
        normalize(hostname).also { host ->
            require(supports(host)) { "Unsupported Steam acceleration host: $host" }
        }

    fun supportsEndpoint(
        hostname: String,
        port: Int,
    ): Boolean {
        if (port !in 1..MAX_TCP_PORT) return false
        val host = normalize(hostname)
        return when {
            host in coreHosts || host in webSessionHosts -> port == STEAM_HTTPS_PORT
            isCmHost(host) -> port == STEAM_HTTPS_PORT || port in STEAM_CM_MIN_PORT..STEAM_CM_MAX_PORT
            else -> false
        }
    }

    fun requireSupportedEndpoint(
        hostname: String,
        port: Int,
    ): String =
        normalize(hostname).also { host ->
            require(port in 1..MAX_TCP_PORT) { "Invalid Steam endpoint port: $port" }
            require(supportsEndpoint(host, port)) {
                "Unsupported Steam acceleration endpoint: $host:$port"
            }
        }

    fun requireAuthenticatedCdnEndpoint(
        hostname: String,
        port: Int,
    ): String {
        val host = normalize(hostname)
        require(port == STEAM_HTTPS_PORT) { "Invalid Steam CDN endpoint port: $port" }
        require(
            host.length in 1..253 &&
                '.' in host &&
                host.any(Char::isLetter) &&
                host.split('.').all { label ->
                    label.length in 1..63 &&
                        label.first().isLetterOrDigit() &&
                        label.last().isLetterOrDigit() &&
                        label.all { character -> character.isLetterOrDigit() || character == '-' }
                },
        ) { "Invalid Steam CDN endpoint host" }
        return host
    }

    fun probePath(hostname: String): String {
        val host = requireSupported(hostname)
        return when (host) {
            "api.steampowered.com", "community.steam-api.com" ->
                "/ISteamWebAPIUtil/GetSupportedAPIList/v1/?format=json"

            else ->
                if (isCmHost(host)) {
                    "/cmsocket/"
                } else {
                    "/workshop/browse/?appid=431960&numperpage=1"
                }
        }
    }

    private fun normalize(hostname: String): String = hostname.lowercase().trimEnd('.')

    internal fun isCmHost(hostname: String): Boolean =
        hostname.length > STEAM_CM_HOST_SUFFIX.length && hostname.endsWith(STEAM_CM_HOST_SUFFIX)

    private const val STEAM_CM_HOST_SUFFIX = ".steamserver.net"
}

internal const val STEAM_HTTPS_PORT = 443
internal const val STEAM_CM_MIN_PORT = 27017
internal const val STEAM_CM_MAX_PORT = 27050
private const val MAX_TCP_PORT = 65535
