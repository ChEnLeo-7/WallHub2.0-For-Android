package com.wallhub.android.data.steamaccess

import java.net.InetAddress

internal object SteamAccessRoutes {
    private val excludedSuffixes = listOf(".steamcontent.com", ".steamserver.net")

    private val aliases = mapOf(
        "steamcommunity.com" to listOf("steamcommunity-a.akamaihd.net.edgesuite.net"),
        "api.steampowered.com" to listOf(
            "api.steampowered.com.edgekey.net",
            "api.steampowered.com.edgesuite.net",
        ),
        "community.steam-api.com" to listOf(
            "community.steam-api.com.edgekey.net",
            "community.steam-api.com.edgesuite.net",
        ),
        "store.steampowered.com" to listOf("steamstore-a.akamaihd.net.edgesuite.net"),
        "images.steamusercontent.com" to listOf("steamuserimages-a.akamaihd.net.edgesuite.net"),
    )

    private val seedAddresses = mapOf(
        "steamcommunity.com" to listOf(
            "23.44.248.222",
            "23.44.248.223",
            "23.52.74.146",
            "23.52.74.163",
            "104.90.128.70",
            "184.25.56.178",
        ),
        "api.steampowered.com" to listOf(
            "173.222.146.99",
            "184.85.112.102",
            "23.45.12.51",
            "23.45.12.60",
            "96.6.190.4",
            "96.7.49.34",
        ),
        "community.steam-api.com" to listOf(
            "173.222.146.99",
            "184.85.112.102",
            "23.45.12.51",
            "23.45.12.60",
            "96.6.190.4",
            "96.7.49.34",
        ),
    )

    fun supports(hostname: String): Boolean {
        val host = hostname.lowercase().trimEnd('.')
        if (host.isBlank() || excludedSuffixes.any(host::endsWith)) return false
        return host == "steamcommunity.com" || host.endsWith(".steamcommunity.com") ||
            host == "steampowered.com" || host.endsWith(".steampowered.com") ||
            host == "steam-api.com" || host.endsWith(".steam-api.com") ||
            host == "steamusercontent.com" || host.endsWith(".steamusercontent.com") ||
            host == "steamstatic.com" || host.endsWith(".steamstatic.com") ||
            host == "akamaihd.net" || host.endsWith(".akamaihd.net")
    }

    fun aliases(hostname: String): List<String> {
        val host = hostname.lowercase().trimEnd('.')
        aliases[host]?.let { return it }
        return when {
            host.endsWith(".steamcommunity.com") -> aliases.getValue("steamcommunity.com")
            host.endsWith(".steamusercontent.com") -> aliases.getValue("images.steamusercontent.com")
            host == "checkout.steampowered.com" || host == "media.steampowered.com" ->
                aliases.getValue("store.steampowered.com")
            else -> emptyList()
        }
    }

    fun seeds(hostname: String): List<InetAddress> = seedAddresses[hostname.lowercase()]
        .orEmpty()
        .mapNotNull { address -> runCatching { InetAddress.getByName(address) }.getOrNull() }
}

internal object SteamHostsParser {
    fun parse(text: String): Map<String, List<InetAddress>> {
        val result = linkedMapOf<String, MutableList<InetAddress>>()
        text.lineSequence().forEach { rawLine ->
            val fields = rawLine.substringBefore('#').trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (fields.size < 2) return@forEach
            val address = parseLiteralAddress(fields.first()) ?: return@forEach
            fields.drop(1).forEach { rawHost ->
                val host = rawHost.lowercase().trimEnd('.')
                if (SteamAccessRoutes.supports(host)) {
                    result.getOrPut(host) { mutableListOf() }.apply {
                        if (none { it.hostAddress == address.hostAddress }) add(address)
                    }
                }
            }
        }
        return result
    }

    private fun parseLiteralAddress(value: String): InetAddress? {
        val isIpv4 = value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
            value.split('.').all { part ->
                part.toIntOrNull()?.let { octet -> octet in 0..255 } == true
            }
        val isIpv6 = ':' in value && value.matches(Regex("[0-9a-fA-F:.%]+"))
        if (!isIpv4 && !isIpv6) return null
        return runCatching { InetAddress.getByName(value.substringBefore('%')) }.getOrNull()
    }
}
