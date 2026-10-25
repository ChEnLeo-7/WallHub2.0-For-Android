package com.wallhub.android.data.steamaccess

import java.net.InetAddress

internal data class SteamWebUpstreamRule(
    val resolveAuthority: String,
    val tlsAuthority: String,
    val httpAuthority: String,
) {
    init {
        require(resolveAuthority == tlsAuthority) { "Steam upstream resolution and TLS authority must match" }
    }
}

internal data class SteamWebDialTarget(
    val rule: SteamWebUpstreamRule,
    val address: InetAddress,
) {
    val healthKey: String
        get() = "${rule.httpAuthority}|${rule.tlsAuthority}|${address.hostAddress.orEmpty()}"
}

internal fun bindSteamWebTargets(
    rules: List<SteamWebUpstreamRule>,
    addressesByAuthority: Map<String, List<InetAddress>>,
): List<SteamWebDialTarget> {
    val addressesByRule =
        rules.associateWith { rule ->
            addressesByAuthority[rule.resolveAuthority]
                .orEmpty()
                .filter(::isAuthenticatedCdnAddress)
                .distinctBy(InetAddress::getHostAddress)
        }
    val targets = mutableListOf<SteamWebDialTarget>()
    val depth = addressesByRule.values.maxOfOrNull(List<InetAddress>::size) ?: 0
    repeat(depth) { addressIndex ->
        rules.forEach { rule ->
            addressesByRule.getValue(rule).getOrNull(addressIndex)?.let { address ->
                targets += SteamWebDialTarget(rule, address)
            }
        }
    }
    return targets.distinctBy(SteamWebDialTarget::healthKey).take(MAX_STEAM_WEB_DIAL_TARGETS)
}

private const val MAX_STEAM_WEB_DIAL_TARGETS = 4

internal fun steamWebTargetsForRequest(
    connectAuthority: String,
    requestHead: String,
    targets: List<SteamWebDialTarget>,
): List<SteamWebDialTarget> =
    if (mayUseSteamWebAlias(connectAuthority, requestHead)) {
        targets.sortedBy { target -> target.rule.tlsAuthority == target.rule.httpAuthority }
    } else {
        targets.filter { target -> target.rule.tlsAuthority == target.rule.httpAuthority }
    }

internal object SteamAccessRoutes {
    private const val VERIFIED_WEB_UPSTREAM = "steamcommunity-a.akamaihd.net"

    private val compatibleWebUpstreams =
        mapOf(
            "steamcommunity.com" to listOf(VERIFIED_WEB_UPSTREAM),
            "www.steamcommunity.com" to listOf(VERIFIED_WEB_UPSTREAM),
            "api.steampowered.com" to listOf(VERIFIED_WEB_UPSTREAM),
            "community.steam-api.com" to listOf(VERIFIED_WEB_UPSTREAM),
            "images.steamusercontent.com" to listOf("steamuserimages-a.akamaihd.net"),
        )

    fun supports(hostname: String): Boolean = SteamDomainPolicy.supports(hostname)

    fun webUpstreams(hostname: String): List<SteamWebUpstreamRule> {
        val host = hostname.lowercase().trimEnd('.')
        val aliases = compatibleWebUpstreams[host] ?: return emptyList()
        return buildList {
            add(SteamWebUpstreamRule(host, host, host))
            aliases.forEach { authority -> add(SteamWebUpstreamRule(authority, authority, host)) }
        }
    }

    fun aliases(hostname: String): List<String> =
        webUpstreams(hostname).drop(1).map(SteamWebUpstreamRule::resolveAuthority)
}

internal fun steamRouteCacheKey(
    networkType: String,
    hostname: String,
    port: Int,
): String = "$networkType|${hostname.lowercase().trimEnd('.')}|$port"

internal fun steamWebRouteCacheKey(
    networkType: String,
    originalAuthority: String,
    tlsAuthority: String,
    port: Int,
): String =
    "web|${steamRouteCacheKey(networkType, originalAuthority, port)}|${tlsAuthority.lowercase().trimEnd('.')}"

internal fun steamWebRouteHealthHost(
    originalAuthority: String,
    tlsAuthority: String,
): String =
    "web~${originalAuthority.lowercase().trimEnd('.')}~${tlsAuthority.lowercase().trimEnd('.')}"

internal object SteamHostsParser {
    fun parse(text: String): Map<String, List<InetAddress>> {
        val result = linkedMapOf<String, MutableList<InetAddress>>()
        text.lineSequence().forEach { rawLine ->
            val fields =
                rawLine
                    .substringBefore('#')
                    .trim()
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank)
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
        val isIpv4 =
            value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
                value.split('.').all { part ->
                    part.toIntOrNull()?.let { octet -> octet in 0..255 } == true
                }
        val isIpv6 = ':' in value && value.matches(Regex("[0-9a-fA-F:.%]+"))
        if (!isIpv4 && !isIpv6) return null
        return runCatching { InetAddress.getByName(value.substringBefore('%')) }.getOrNull()
    }
}
