package com.wallhub.prototype.mpkg

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ResourceBudgetContext(
    val relativePath: String,
    val strategy: ResourceStrategy?,
    val sourceBytes: Long,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

class ResourceMemoryBudgetException(
    message: String,
    val estimatedPeakBytes: Long,
    val memoryBudgetBytes: Long,
    val exclusiveBoundedPeakBytes: Long?,
    val context: ResourceBudgetContext?,
) : IllegalArgumentException(message) {
    fun diagnostic(): String {
        val path = context?.relativePath?.replace('\\', '/')?.takeIf {
            !it.startsWith('/') && !it.contains(':') && it.split('/').none { part -> part == ".." } &&
                it.none { char -> char.isISOControl() }
        } ?: "<unavailable>"
        val dimensions = if (context?.imageWidth != null && context.imageHeight != null) {
            "${context.imageWidth}x${context.imageHeight}"
        } else {
            "unknown"
        }
        return "resource=$path strategy=${context?.strategy ?: "unknown"} sourceBytes=${context?.sourceBytes ?: "unknown"} " +
            "dimensions=$dimensions estimatedPeakBytes=$estimatedPeakBytes memoryBudgetBytes=$memoryBudgetBytes " +
            "exclusiveBoundedPeakBytes=${exclusiveBoundedPeakBytes ?: "none"}"
    }
}

data class ResourceWork<T>(
    val ordinal: Int,
    val estimatedPeakBytes: Long,
    val payload: T,
    /** Verified upper bound for an internally chunked transform that must own the whole outer budget. */
    val exclusiveBoundedPeakBytes: Long? = null,
    val budgetContext: ResourceBudgetContext? = null,
)

/** Bounded resource execution; scheduling, byte admission, cancellation and ordered assembly stay behind one interface. */
class MemoryWeightedResourcePipeline(
    private val maxConcurrency: Int,
    private val memoryBudgetBytes: Long,
) {
    init {
        require(maxConcurrency in 1..3) { "Resource concurrency must be between one and three" }
        require(memoryBudgetBytes > 0L) { "Resource memory budget must be positive" }
    }

    fun <T, R> execute(
        work: List<ResourceWork<T>>,
        checkCancellation: () -> Unit,
        transform: (ResourceWork<T>, () -> Unit) -> R,
    ): List<R> {
        work.forEach { item ->
            require(item.ordinal >= 0 && item.estimatedPeakBytes > 0L) {
                "Resource work exceeds the conversion memory budget"
            }
            val boundedPeak = item.exclusiveBoundedPeakBytes
            val budgetMessage =
                if (boundedPeak == null && item.estimatedPeakBytes > memoryBudgetBytes) {
                    "Resource work exceeds the conversion memory budget"
                } else if (boundedPeak != null && (item.estimatedPeakBytes <= memoryBudgetBytes || boundedPeak !in 1..memoryBudgetBytes)) {
                    "Resource work exceeds the verified exclusive conversion memory bound"
                } else {
                    null
                }
            if (budgetMessage != null) {
                throw ResourceMemoryBudgetException(
                    budgetMessage, item.estimatedPeakBytes, memoryBudgetBytes, boundedPeak, item.budgetContext,
                )
            }
        }
        require(work.map(ResourceWork<T>::ordinal).distinct().size == work.size) { "Resource ordinals must be unique" }
        if (work.isEmpty()) return emptyList()

        val nextThread = AtomicInteger()
        val executor =
            Executors.newFixedThreadPool(maxConcurrency) { runnable ->
                Thread(runnable, "wallhub-mpkg-${nextThread.incrementAndGet()}").apply { isDaemon = false }
            }
        val completion = ExecutorCompletionService<IndexedProduct<R>>(executor)
        val admission = WeightedAdmission(memoryBudgetBytes)
        val futures = ArrayList<Future<IndexedProduct<R>>>(work.size)
        var failure: Throwable? = null
        try {
            work.forEach { item ->
                futures +=
                    completion.submit {
                        val admissionBytes = if (item.exclusiveBoundedPeakBytes == null) item.estimatedPeakBytes else memoryBudgetBytes
                        admission.acquire(admissionBytes)
                        try {
                            val workerCancellation = {
                                if (Thread.currentThread().isInterrupted) throw CancellationException("Resource worker interrupted")
                                checkCancellation()
                            }
                            workerCancellation()
                            IndexedProduct(item.ordinal, transform(item, workerCancellation))
                        } finally {
                            admission.release(admissionBytes)
                        }
                    }
            }
            val products = ArrayList<IndexedProduct<R>>(work.size)
            while (products.size < work.size) {
                checkCancellation()
                val completed = completion.poll(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                try {
                    products += completed.get()
                } catch (error: Throwable) {
                    failure = error.cause ?: error
                    break
                }
            }
            failure?.let { throw it }
            return products.sortedBy(IndexedProduct<R>::ordinal).map(IndexedProduct<R>::value)
        } catch (error: Throwable) {
            failure = error
            futures.forEach { it.cancel(true) }
            throw error
        } finally {
            if (failure != null) futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            var interruptedWhileJoining = false
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(WORKER_JOIN_POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interruptedWhileJoining = true
                    executor.shutdownNow()
                }
            }
            if (interruptedWhileJoining) Thread.currentThread().interrupt()
        }
    }

    private data class IndexedProduct<R>(
        val ordinal: Int,
        val value: R,
    )
}

private class WeightedAdmission(
    private val budgetBytes: Long,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var activeBytes = 0L

    fun acquire(bytes: Long) {
        lock.withLock {
            while (activeBytes > budgetBytes - bytes) changed.await()
            activeBytes += bytes
        }
    }

    fun release(bytes: Long) {
        lock.withLock {
            activeBytes -= bytes
            check(activeBytes >= 0L) { "Resource memory admission underflow" }
            changed.signalAll()
        }
    }
}

private const val CANCELLATION_POLL_MILLIS = 50L
private const val WORKER_JOIN_POLL_MILLIS = 100L
