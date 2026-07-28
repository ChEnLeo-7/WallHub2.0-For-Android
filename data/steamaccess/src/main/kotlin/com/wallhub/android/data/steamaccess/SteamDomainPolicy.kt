package com.wallhub.android.data.steamaccess

internal object SteamDomainPolicy {
    private val coreHosts = setOf(
        "steamcommunity.com",
        "www.steamcommunity.com",
        "api.steampowered.com",
        "community.steam-api.com",
    )

    fun supports(hostname: String): Boolean = normalize(hostname) in coreHosts

    fun requireSupported(hostname: String): String = normalize(hostname).also { host ->
        require(host in coreHosts) { "Unsupported Steam acceleration host: $host" }
    }

    fun probePath(hostname: String): String = when (requireSupported(hostname)) {
        "api.steampowered.com", "community.steam-api.com" ->
            "/ISteamWebAPIUtil/GetSupportedAPIList/v1/?format=json"

        else -> "/workshop/browse/?appid=431960&numperpage=1"
    }

    private fun normalize(hostname: String): String = hostname.lowercase().trimEnd('.')
}
