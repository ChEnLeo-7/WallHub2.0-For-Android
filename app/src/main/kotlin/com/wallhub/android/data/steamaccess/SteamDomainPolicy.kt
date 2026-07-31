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

    fun probePath(hostname: String): String {
        val host = requireSupported(hostname)
        return when (host) {
            "api.steampowered.com", "community.steam-api.com" ->
                "/ISteamWebAPIUtil/GetSupportedAPIList/v1/?format=json"

            else ->
                if (host.endsWith(STEAM_CM_HOST_SUFFIX)) {
                    "/cmsocket/"
                } else {
                    "/workshop/browse/?appid=431960&numperpage=1"
                }
        }
    }

    private fun normalize(hostname: String): String = hostname.lowercase().trimEnd('.')

    private const val STEAM_CM_HOST_SUFFIX = ".steamserver.net"
}
