package com.wallhub.android.data.downloads

import bruhcollective.itaysonlab.ksteam.SteamClient
import kotlinx.coroutines.withTimeout
import steam.webui.contentserverdirectory.CContentServerDirectory_GetCDNAuthToken_Request
import steam.webui.contentserverdirectory.CContentServerDirectory_GetCDNAuthToken_Response
import steam.webui.contentserverdirectory.CContentServerDirectory_GetManifestRequestCode_Request
import steam.webui.contentserverdirectory.CContentServerDirectory_GetManifestRequestCode_Response
import steam.webui.contentserverdirectory.CContentServerDirectory_GetServersForSteamPipe_Request
import steam.webui.contentserverdirectory.CContentServerDirectory_GetServersForSteamPipe_Response
import steam.webui.contentserverdirectory.CContentServerDirectory_ServerInfo

/**
 * Depot-facing Steam network access over the shared kSteam clients: the
 * `ContentServerDirectory` service methods that replace JavaSteam's SteamContent handler.
 */
internal suspend fun SteamClient.steamCdnServers(
    cellId: Int,
    maxServers: Int,
): List<CdnServer> {
    val response =
        withTimeout(KSteamSessionRpcTimeoutMs) {
            unifiedMessages.execute(
                signed = true,
                methodName = "ContentServerDirectory.GetServersForSteamPipe",
                requestAdapter = CContentServerDirectory_GetServersForSteamPipe_Request.ADAPTER,
                responseAdapter = CContentServerDirectory_GetServersForSteamPipe_Response.ADAPTER,
                requestData =
                    CContentServerDirectory_GetServersForSteamPipe_Request(
                        cell_id = cellId,
                        max_servers = maxServers,
                    ),
            )
        }
    return response.servers.map { server -> server.toCdnServer() }
}

internal suspend fun SteamClient.steamManifestRequestCode(
    depotId: Int,
    appId: Int,
    manifestId: Long,
): Long {
    val response =
        withTimeout(KSteamSessionRpcTimeoutMs) {
            unifiedMessages.execute(
                signed = true,
                methodName = "ContentServerDirectory.GetManifestRequestCode",
                requestAdapter = CContentServerDirectory_GetManifestRequestCode_Request.ADAPTER,
                responseAdapter = CContentServerDirectory_GetManifestRequestCode_Response.ADAPTER,
                requestData =
                    CContentServerDirectory_GetManifestRequestCode_Request(
                        app_id = appId,
                        depot_id = depotId,
                        manifest_id = manifestId,
                    ),
            )
        }
    return response.manifest_request_code ?: 0L
}

internal suspend fun SteamClient.steamCdnAuthToken(
    appId: Int,
    depotId: Int,
    hostName: String,
): Pair<String, Long> {
    val response =
        withTimeout(KSteamSessionRpcTimeoutMs) {
            unifiedMessages.execute(
                signed = true,
                methodName = "ContentServerDirectory.GetCDNAuthToken",
                requestAdapter = CContentServerDirectory_GetCDNAuthToken_Request.ADAPTER,
                responseAdapter = CContentServerDirectory_GetCDNAuthToken_Response.ADAPTER,
                requestData =
                    CContentServerDirectory_GetCDNAuthToken_Request(
                        depot_id = depotId,
                        host_name = hostName,
                        app_id = appId,
                    ),
            )
        }
    val token = response.token.orEmpty().trim().removePrefix("?")
    check(token.isNotEmpty()) { "Steam returned no CDN authorization token" }
    return token to (response.expiration_time ?: 0).toLong() * 1_000L
}

private fun CContentServerDirectory_ServerInfo.toCdnServer(): CdnServer {
    val https = https_support.equals("mandatory", ignoreCase = true)
    return CdnServer(
        host = host,
        vHost = vhost,
        port = if (https) 443 else 80,
        https = https,
        type = type,
        sourceId = source_id ?: 0,
        cellId = cell_id ?: 0,
        load = load ?: 0,
        weightedLoad = weighted_load ?: 0f,
        numEntries = num_entries_in_client_list ?: 0,
        steamChinaOnly = steam_china_only == true,
        useAsProxy = use_as_proxy == true,
        proxyRequestPathTemplate = proxy_request_path_template,
        allowedAppIds = allowed_app_ids.orEmpty().toIntArray(),
    )
}

internal const val KSteamSessionRpcTimeoutMs = 25_000L
