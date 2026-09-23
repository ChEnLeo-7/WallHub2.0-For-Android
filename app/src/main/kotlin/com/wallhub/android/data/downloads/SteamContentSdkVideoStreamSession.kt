package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopVideoStreamSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One SDK-served playback session: the app's video contract over a depot-backed range stream.
 *
 * The SDK session is what authorizes the ranges, so the stream and its session are one unit and closing
 * playback releases both, the stream first. Only the members playback actually needs are implemented;
 * `playbackBufferState`, `fullCacheState`, `startFullCache`, and `cancelFullCache` keep their contract
 * defaults, so with the SDK serving ranges there is no second source of truth for buffer or cache state.
 *
 * [contentLength] is the size the product already read for this item and is also the read ceiling, so a
 * read past the end answers zero - the same end-of-file answer the contract's other implementations give.
 */
internal class SteamContentSdkVideoStreamSession(
    private val session: SteamContentSdkSession,
    private val stream: SteamContentSdkRangeStream,
    override val title: String,
    override val fileName: String,
    override val contentLength: Long,
) : WorkshopVideoStreamSession {
    override suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(position >= 0L) { "Invalid SDK video read position" }
            require(length >= 0) { "Invalid SDK video read length" }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid SDK video read destination"
            }
            if (length == 0 || position >= contentLength) return@withContext 0
            stream.readAt(
                position = position,
                destination = destination,
                offset = destinationOffset,
                length = minOf(length.toLong(), contentLength - position).toInt(),
            )
        }

    override fun close() {
        try {
            stream.close()
        } finally {
            session.close()
        }
    }
}
