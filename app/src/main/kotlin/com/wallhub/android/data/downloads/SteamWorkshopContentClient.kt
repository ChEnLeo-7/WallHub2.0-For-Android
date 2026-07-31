package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Separates the externally hosted Steam session from WorkManager task orchestration. */
internal interface WorkshopContentGateway {
    suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget

    suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult
}

internal class FormalSteamWorkshopContentGateway(
    private val httpClientFactory: SteamHttpClientFactory,
) : WorkshopContentGateway {
    override suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget =
        SteamWorkshopContentApi(
            httpClientFactory.newBuilder().applyDownloadProxy(proxyUrl),
        ).fetchContentTarget(publishedFileId)

    override suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        SteamContentDownloader().download(
            target = target,
            destinationDirectory = destinationDirectory,
            credential = credential,
            options = options,
            control = control,
            onProgress = onProgress,
        )
}

@Singleton
class SteamWorkshopContentClient private constructor(
    private val gateway: WorkshopContentGateway,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    @Inject
    constructor(
        httpClientFactory: SteamHttpClientFactory,
    ) : this(FormalSteamWorkshopContentGateway(httpClientFactory), Unit)

    internal constructor(
        gateway: WorkshopContentGateway,
    ) : this(gateway, Unit)

    internal suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget = gateway.fetchContentTarget(publishedFileId, proxyUrl)

    internal suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        gateway.download(
            target = target,
            destinationDirectory = destinationDirectory,
            credential = credential,
            options = options,
            control = control,
            onProgress = onProgress,
        )
}
