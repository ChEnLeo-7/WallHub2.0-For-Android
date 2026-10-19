package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopVideoCdnProvider
import com.wallhub.android.core.model.WorkshopVideoStreamFailure
import com.wallhub.android.core.model.WorkshopVideoStreamFailureException
import com.wallhub.android.data.steam.SteamContentSdkEngine
import io.steamcontent.sdk.CdnProvider
import io.steamcontent.sdk.DepotRangeStream
import io.steamcontent.sdk.SteamDownloadTask
import io.steamcontent.sdk.StreamReadFailure
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Borrowed engine facade with no close authority over the account session. */
private class SteamContentSdkEngineSession(
    private val engine: SteamContentSdkEngine,
) : SteamContentSdkSession {
    override val storageRoot: File
        get() = engine.storageRoot

    override fun resolveWorkshop(
        itemId: Long,
        appIdHint: Long,
        contentManifestIdHint: Long,
    ): SteamContentSdkResolvedItem {
        val response = engine.resolveWorkshop(itemId, appIdHint, contentManifestIdHint)
        return SteamContentSdkResolvedItem(
            itemId = response.itemId,
            appId = response.appId,
            depotId = response.depotId,
            manifestId = response.manifestId,
            resolved = response.isResolved,
        )
    }

    override fun startDownload(
        appId: Long,
        depotId: Long,
        manifestId: Long,
        destination: String?,
        chunkConcurrency: Int,
    ): SteamContentSdkTask {
        val task =
            engine.startDownload(
                appId = appId,
                depotId = depotId,
                manifestId = manifestId,
                destination = destination,
                chunkConcurrency = chunkConcurrency,
            ) ?: error("The Steam Content SDK did not start a download task")
        return SteamContentSdkEngineTask(task)
    }

    override fun manifestFiles(
        appId: Long,
        depotId: Long,
        manifestId: Long,
    ): List<SteamContentSdkManifestFile> =
        engine.openManifestFiles(appId, depotId, manifestId).use { list ->
            (0 until list.size()).map { index ->
                val file = list.file(index)
                SteamContentSdkManifestFile(
                    path = list.path(index),
                    size = file.size,
                    flags = file.flags,
                )
            }
        }

    override fun openRangeStream(
        appId: Long,
        depotId: Long,
        filePath: String,
        manifestId: Long,
        cacheCapacityBytes: Long,
    ): SteamContentSdkRangeStream =
        SteamContentSdkEngineRangeStream(
            engine.openRangeStream(
                appId = appId,
                depotId = depotId,
                filePath = filePath,
                manifestId = manifestId,
                cacheCapacityBytes = cacheCapacityBytes,
            ),
        )

    override fun close() = Unit
}

internal fun borrowedSteamContentSdkSession(engine: SteamContentSdkEngine): SteamContentSdkSession =
    SteamContentSdkEngineSession(engine)

/** The SDK range-stream handle as [SteamContentSdkRangeStream]. */
private class SteamContentSdkEngineRangeStream(
    private val stream: DepotRangeStream,
) : SteamContentSdkRangeStream {
    private val lastProvider = AtomicReference<WorkshopVideoCdnProvider?>(null)

    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val result = stream.readAtEx(position, destination, offset, length)
        result.cdnProvider.toAppProvider()?.let(lastProvider::set)
        if (result.failure == StreamReadFailure.NONE) return result.written
        throw result.failure.toAppStreamReadException(
            generation = result.generation,
            cdnProvider = result.cdnProvider,
        )
    }

    override fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand): SteamContentSdkBufferSnapshot {
        stream.updatePlaybackDemand(
            position = demand.position,
            bufferedBytes = demand.bufferedBytes,
            throughputBytesPerSecond = demand.throughputBytesPerSecond,
            bitrateBitsPerSecond = demand.bitrateBitsPerSecond,
            rebuffered = demand.rebuffered,
        )
        return stream.bufferSnapshot().toAppSnapshot()
    }

    override fun bufferSnapshot(): SteamContentSdkBufferSnapshot = stream.bufferSnapshot().toAppSnapshot()

    override fun cdnProvider(): WorkshopVideoCdnProvider? = lastProvider.get()

    override fun telemetry(): SteamContentSdkStreamTelemetry =
        stream.telemetry().let { value ->
            SteamContentSdkStreamTelemetry(
                fetchStarted = value.fetchStarted,
                fetchCompleted = value.fetchCompleted,
                fetchFailed = value.fetchFailed,
                deduplicated = value.deduplicated,
                foregroundWaits = value.foregroundWaits,
                staleCompletions = value.staleCompletions,
                fetchMicros = value.fetchMicros,
                queueMicros = value.queueMicros,
                lockMicros = value.lockMicros,
                decodeMicros = value.decodeMicros,
                cacheHits = value.cacheHits,
                cacheMisses = value.cacheMisses,
                cacheEvictions = value.cacheEvictions,
                foregroundFetchStarted = value.foregroundFetchStarted,
                foregroundFetchCompleted = value.foregroundFetchCompleted,
                foregroundFetchFailed = value.foregroundFetchFailed,
                foregroundFetchMicros = value.foregroundFetchMicros,
                foregroundDecodeMicros = value.foregroundDecodeMicros,
            )
        }

    override fun cancelPlaybackDemand() = stream.cancelPlaybackDemand()

    override fun cancel() = stream.cancel()

    override fun close() {
        stream.close()
    }
}

private fun CdnProvider.toAppProvider(): WorkshopVideoCdnProvider? =
    when (this) {
        CdnProvider.UNKNOWN -> null
        CdnProvider.VALVE -> WorkshopVideoCdnProvider.VALVE
        CdnProvider.STEAMCHINA -> WorkshopVideoCdnProvider.STEAM_CHINA
        CdnProvider.HUAWEI_CLOUD -> WorkshopVideoCdnProvider.HUAWEI_CLOUD
        CdnProvider.BAISHAN_CLOUD -> WorkshopVideoCdnProvider.BAISHAN_CLOUD
        CdnProvider.TENCENT_CLOUD -> WorkshopVideoCdnProvider.TENCENT_CLOUD
        CdnProvider.ALIBABA_CLOUD -> WorkshopVideoCdnProvider.ALIBABA_CLOUD
        CdnProvider.NETEASE_CLOUD -> WorkshopVideoCdnProvider.NETEASE_CLOUD
        CdnProvider.CHINANETCENTER -> WorkshopVideoCdnProvider.CHINA_NET_CENTER
        CdnProvider.FASTLY -> WorkshopVideoCdnProvider.FASTLY
        CdnProvider.EDGECAST -> WorkshopVideoCdnProvider.EDGECAST
        CdnProvider.SINA_CLOUD -> WorkshopVideoCdnProvider.SINA_CLOUD
        CdnProvider.OTHER -> WorkshopVideoCdnProvider.OTHER
    }

internal fun StreamReadFailure.toAppStreamReadException(
    generation: Long,
    cdnProvider: CdnProvider,
): IOException {
    val rawFailure =
        IOException(
            "Steam Content SDK range read failed " +
                "(failure=$name, generation=$generation, provider=${cdnProvider.name})",
        )
    return when (this) {
        StreamReadFailure.CLOSED,
        StreamReadFailure.SESSION_GENERATION_LOST,
        -> WorkshopVideoStreamFailureException(WorkshopVideoStreamFailure.SESSION_INVALIDATED, rawFailure)

        StreamReadFailure.CDN_CANDIDATES_EXHAUSTED ->
            WorkshopVideoStreamFailureException(WorkshopVideoStreamFailure.CDN_ROUTE_EXHAUSTED, rawFailure)

        else -> rawFailure
    }
}

private fun DepotRangeStream.BufferSnapshot.toAppSnapshot() =
    SteamContentSdkBufferSnapshot(
        position = position,
        targetBytes = targetBytes,
        prefetchOffset = prefetchOffset,
        prefetchBytes = prefetchBytes,
        generation = generation,
        cachedBytes = cachedBytes,
        contiguousCachedBytes = contiguousCachedBytes,
        cancelled = cancelled,
    )

/** The SDK task handle as [SteamContentSdkTask]. */
private class SteamContentSdkEngineTask(
    private val task: SteamDownloadTask,
) : SteamContentSdkTask {
    override fun status(): SteamContentSdkTaskStatus {
        val status = task.status()
        return SteamContentSdkTaskStatus(
            state = status.state,
            committedBytes = status.committedBytes,
            totalDecodedBytes = status.totalDecodedBytes,
            networkBytes = status.networkBytes,
            networkBytesPerSecond = status.networkBytesPerSecond,
            firstContentReceived = status.firstContentReceived,
            errorOrdinal = status.errorOrdinal,
        )
    }

    override fun pollEvent(timeoutMs: Int): SteamContentSdkTaskEvent? =
        task.pollEvent(timeoutMs)?.let { event -> SteamContentSdkTaskEvent(event.kind, event.value) }

    override fun lastError(): String? = task.lastError()

    override fun pause() = task.pause()

    override fun resume() = task.resume()

    override fun cancel() = task.cancel()

    override fun close() {
        task.close()
    }
}
