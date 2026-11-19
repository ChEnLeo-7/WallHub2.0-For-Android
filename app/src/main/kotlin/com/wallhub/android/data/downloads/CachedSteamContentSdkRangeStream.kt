package com.wallhub.android.data.downloads

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore

/** Adds a bounded persistent page cache without taking ownership of Steam transport or decryption. */
internal class CachedSteamContentSdkRangeStream(
    private val delegate: SteamContentSdkRangeStream,
    private val cache: SteamVideoStreamCache,
    private val contentLength: Long,
    private val pageSize: Int = PAGE_SIZE_BYTES,
) : SteamContentSdkRangeStream {
    private val generation = AtomicLong(0L)
    private val generationLock = Any()
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commitSlots = Semaphore(MAX_PENDING_COMMITS)
    private val commitJobs = mutableSetOf<Job>()
    private var closed = false

    init {
        require(pageSize > 0)
    }

    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(position >= 0L)
        require(length >= 0)
        require(offset >= 0 && offset <= destination.size - length)
        if (length == 0 || position >= contentLength) return 0

        val readGeneration = generation.get()
        var cursor = position
        val boundedLength = minOf(length.toLong(), contentLength - position).toInt()
        var destinationOffset = offset
        var remaining = boundedLength
        var totalRead = 0
        val protectedOffsets = mutableSetOf<Long>()
        try {
            while (remaining > 0) {
                if (generation.get() != readGeneration) throw CancellationException("SDK range read superseded by seek")
                val pageOffset = cursor / pageSize * pageSize
                val pageSliceOffset = (cursor - pageOffset).toInt()
                val pageLength = minOf(pageSize.toLong(), contentLength - pageOffset).toInt()
                val requested = minOf(remaining, pageLength - pageSliceOffset)
                runBlocking(Dispatchers.IO) { cache.protectChunkOffset(pageOffset) }
                protectedOffsets += pageOffset
                val cached =
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            cache.readPageSliceInto(
                                pageOffset = pageOffset,
                                pageLength = pageLength,
                                pageSliceOffset = pageSliceOffset,
                                destination = destination,
                                destinationOffset = destinationOffset,
                                length = requested,
                            )
                        }
                    }.getOrDefault(false)

                if (generation.get() != readGeneration) throw CancellationException("SDK range read superseded by seek")
                val copied =
                    if (cached) {
                        requested
                    } else {
                        readUncachedPage(
                            readGeneration = readGeneration,
                            pageOffset = pageOffset,
                            pageLength = pageLength,
                            pageSliceOffset = pageSliceOffset,
                            destination = destination,
                            destinationOffset = destinationOffset,
                            requested = requested,
                        )
                    }
                if (generation.get() != readGeneration) throw CancellationException("SDK range read superseded by seek")
                if (copied > 0) {
                    cursor += copied
                    destinationOffset += copied
                    remaining -= copied
                    totalRead += copied
                }
                if (copied != requested) remaining = 0
            }
            return totalRead
        } finally {
            protectedOffsets.forEach { pageOffset -> cache.unprotectChunkOffset(pageOffset) }
        }
    }

    private fun readUncachedPage(
        readGeneration: Long,
        pageOffset: Long,
        pageLength: Int,
        pageSliceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        requested: Int,
    ): Int {
        val page = ByteArray(pageLength)
        var filled = 0
        while (filled < pageLength) {
            if (generation.get() != readGeneration) throw CancellationException("SDK range read superseded by seek")
            val read = delegate.readAt(pageOffset + filled, page, filled, pageLength - filled)
            if (read <= 0) break
            filled += read
        }
        if (generation.get() != readGeneration) throw CancellationException("SDK range read superseded by seek")
        if (filled == pageLength) {
            enqueueCommit(readGeneration, pageOffset, page)
        }
        if (pageSliceOffset >= filled) return 0
        val copied = minOf(requested, filled - pageSliceOffset)
        System.arraycopy(page, pageSliceOffset, destination, destinationOffset, copied)
        return copied
    }

    private fun enqueueCommit(readGeneration: Long, pageOffset: Long, page: ByteArray) {
        synchronized(generationLock) {
            if (closed || generation.get() != readGeneration || !commitSlots.tryAcquire()) return
            val job =
                commitScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val shouldCommit = synchronized(generationLock) {
                            generation.get() == readGeneration && !closed
                        }
                        if (shouldCommit) {
                            try {
                                cache.commitPage(pageOffset, page)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Cache persistence is best-effort; the delegate remains authoritative.
                            }
                        }
                    } finally {
                        commitSlots.release()
                    }
                }
            commitJobs += job
            job.invokeOnCompletion { synchronized(generationLock) { commitJobs.remove(job) } }
            job.start()
        }
    }

    override fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand): SteamContentSdkBufferSnapshot =
        delegate.updatePlaybackDemand(demand)

    override fun bufferSnapshot(): SteamContentSdkBufferSnapshot = delegate.bufferSnapshot()

    override fun telemetry(): SteamContentSdkStreamTelemetry = delegate.telemetry()

    override fun cdnProvider() = delegate.cdnProvider()

    override fun currentCdnHost(): String? = delegate.currentCdnHost()

    override fun seek(position: Long): Long =
        synchronized(generationLock) {
            generation.incrementAndGet()
            delegate.seek(position)
        }

    override fun cancelPlaybackDemand() = delegate.cancelPlaybackDemand()

    override fun cancel() {
        generation.incrementAndGet()
        delegate.cancel()
    }

    override fun close() {
        val pending =
            synchronized(generationLock) {
                if (closed) return
                closed = true
                generation.incrementAndGet()
                commitJobs.toList()
            }
        try {
            delegate.close()
        } finally {
            runBlocking {
                pending.joinAll()
                commitScope.cancel()
            }
            cache.close()
        }
    }

    private companion object {
        const val PAGE_SIZE_BYTES = 256 * 1024
        const val MAX_PENDING_COMMITS = 2
    }
}
