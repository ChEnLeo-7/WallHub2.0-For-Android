package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION
import com.wallhub.android.core.model.SteamDohEndpoint
import com.wallhub.android.core.model.SteamDohRegion

internal fun selectFastestDohEndpoints(
    orderedEndpoints: List<SteamDohEndpoint>,
    results: List<SteamDohProbeResult>,
): List<String> {
    val resultByUrl = results.associateBy { result -> result.endpoint.url }
    fun fastest(region: SteamDohRegion): List<String> =
        orderedEndpoints
            .withIndex()
            .filter { (_, endpoint) -> endpoint.region == region }
            .mapNotNull { (priority, endpoint) ->
                resultByUrl[endpoint.url]
                    ?.takeIf(SteamDohProbeResult::available)
                    ?.let { result -> Triple(endpoint.url, result.latencyMs ?: Long.MAX_VALUE, priority) }
            }
            .sortedWith(compareBy<Triple<String, Long, Int>> { it.second }.thenBy { it.third })
            .take(STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION)
            .map { it.first }
    val builtIns = fastest(SteamDohRegion.DOMESTIC) + fastest(SteamDohRegion.FOREIGN)
    val custom = orderedEndpoints
        .filter { endpoint -> endpoint.region == SteamDohRegion.CUSTOM }
        .filter { endpoint -> resultByUrl[endpoint.url]?.available == true }
        .map { endpoint -> endpoint.url }
    return (builtIns + custom).distinct()
}
