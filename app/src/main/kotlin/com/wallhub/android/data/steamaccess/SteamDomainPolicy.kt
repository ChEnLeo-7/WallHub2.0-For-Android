package com.wallhub.android.data.steamaccess

internal object SteamDomainPolicy {
    private val coreHosts =
        setOf(
            "steamcommunity.com",
            "www.steamcommunity.com",
            "api.steampowered.com",
            "community.steam-api.com",
        )

    fun supports(hostname: String): Boolean {
        val host = normalize(hostname)
        return host in coreHosts ||
            (host.length > STEAM_CM_HOST_SUFFIX.length && host.endsWith(STEAM_CM_HOST_SUFFIX))
    }

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
            host in coreHosts -> port == STEAM_HTTPS_PORT
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

    private fun isCmHost(hostname: String): Boolean =
        hostname.length > STEAM_CM_HOST_SUFFIX.length && hostname.endsWith(STEAM_CM_HOST_SUFFIX)

    private const val STEAM_CM_HOST_SUFFIX = ".steamserver.net"
}

internal const val STEAM_HTTPS_PORT = 443
internal const val STEAM_CM_MIN_PORT = 27017
internal const val STEAM_CM_MAX_PORT = 27050
private const val MAX_TCP_PORT = 65535
