package com.wallhub.android.data.steam

import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.IServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

internal const val STEAM_DIRECTORY_CALL_TIMEOUT_MS = 12_000L
internal const val STEAM_RPC_TIMEOUT_MS = 15_000L

internal fun createSteamDirectoryClient(clientFactory: SteamHttpClientFactory): OkHttpClient =
    createSteamDirectoryClient(clientFactory.newBuilder())

internal fun createSteamDirectoryClient(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
    builder
        .callTimeout(STEAM_DIRECTORY_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(6_000L, TimeUnit.MILLISECONDS)
        .readTimeout(8_000L, TimeUnit.MILLISECONDS)
        .writeTimeout(8_000L, TimeUnit.MILLISECONDS)
        .build()

internal fun createSteamConfiguration(
    directoryClient: OkHttpClient,
    serverListProvider: IServerListProvider,
): SteamConfiguration =
    SteamConfiguration.create { config ->
        config.withProtocolTypes(ProtocolTypes.WEB_SOCKET)
        config.withHttpClient(directoryClient)
        config.withServerListProvider(serverListProvider)
    }

internal class SteamWebSocketServerListProvider : IServerListProvider {
    private var servers: List<ServerRecord> = emptyList()
    private var refreshedAt: Instant = Instant.MIN

    override val lastServerListRefresh: Instant
        @Synchronized get() = refreshedAt

    @Synchronized
    override fun fetchServerList(): List<ServerRecord> = servers.toList()

    @Synchronized
    override fun updateServerList(endpoints: List<ServerRecord>) {
        servers =
            endpoints.filter { endpoint ->
                endpoint.protocolTypes.contains(ProtocolTypes.WEB_SOCKET)
            }
        refreshedAt = Instant.now()
    }
}

internal class SteamLogonRejectedException(
    val result: EResult,
) : IllegalStateException("Steam logon returned $result")

internal fun EResult.isCredentialRejection(): Boolean =
    when (this) {
        EResult.InvalidPassword,
        EResult.AccessDenied,
        EResult.AccountNotFound,
        EResult.AccountDisabled,
        EResult.Revoked,
        EResult.Expired,
        EResult.AccountLogonDenied,
        EResult.AccountLogonDeniedNoMail,
        EResult.AccountLogonDeniedVerifiedEmailRequired,
        EResult.RequirePasswordReEntry,
        -> true

        else -> false
    }
