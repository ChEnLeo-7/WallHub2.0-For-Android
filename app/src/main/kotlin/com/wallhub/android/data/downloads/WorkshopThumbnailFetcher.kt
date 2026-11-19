package com.wallhub.android.data.downloads

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okio.buffer
import okio.source

internal data class WorkshopThumbnail(val workshopId: Long, val previewUrl: String?)

internal class WorkshopThumbnailFetcher(
    private val data: WorkshopThumbnail,
    private val options: Options,
    private val cache: SteamWorkshopThumbnailCache,
) : Fetcher {
    override suspend fun fetch(): SourceResult {
        val input = cache.open(data.workshopId, data.previewUrl) ?: throw IOException("Workshop cover unavailable")
        val context = currentCoroutineContext()
        val cancellation = context.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) input.close()
        }
        try {
            context.ensureActive()
            return SourceResult(
                source = ImageSource(input.source().buffer(), options.context),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (error: Throwable) {
            cancellation.dispose()
            input.close()
            throw error
        }
    }

    class Factory(private val cache: SteamWorkshopThumbnailCache) : Fetcher.Factory<WorkshopThumbnail> {
        override fun create(data: WorkshopThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            WorkshopThumbnailFetcher(data, options, cache)
    }
}
