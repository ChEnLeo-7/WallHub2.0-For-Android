package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Separates the externally hosted Steam session from WorkManager task orchestration. */
internal interface WorkshopContentGateway {
    suspend fun acquireContentTransportLease(): Closeable = Closeable {}

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
    private val contentDownloader: SteamContentDownloader,
) : WorkshopContentGateway {
    override suspend fun acquireContentTransportLease(): Closeable =
        contentDownloader.acquireContentTransportLease()

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
        contentDownloader.download(
            target = target,
            destinationDirectory = destinationDirectory,
            credential = credential,
            options = options,
            control = control,
            onProgress = onProgress,
        )
}

@Singleton
internal class SteamWorkshopContentClient private constructor(
    private val gateway: WorkshopContentGateway,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    @Inject
    constructor(
        httpClientFactory: SteamHttpClientFactory,
        contentDownloader: SteamContentDownloader,
    ) : this(FormalSteamWorkshopContentGateway(httpClientFactory, contentDownloader), Unit)

    internal constructor(
        gateway: WorkshopContentGateway,
    ) : this(gateway, Unit)

    internal suspend fun fetchContentTarget(
        publishedFileId: Long,
        proxyUrl: String,
    ): WorkshopContentTarget = gateway.fetchContentTarget(publishedFileId, proxyUrl)

    internal suspend fun acquireContentTransportLease(): Closeable =
        gateway.acquireContentTransportLease()

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
