package com.wallhub.prototype.mpkg

import com.wallhub.android.data.downloads.InsufficientDestinationSpaceException
import com.wallhub.android.data.downloads.classifyConversionFailure
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryWeightedResourcePipelineTest {
    @Test
    fun `wrapped budget failure excludes absolute paths and retains other failure classifications`() {
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 1, memoryBudgetBytes = 100)
        val error = assertFailsWith<ResourceMemoryBudgetException> {
            pipeline.execute(
                listOf(ResourceWork(0, 101, Unit, 101, ResourceBudgetContext("C:/private/asset.tex", ResourceStrategy.COPY, 12))),
                {},
            ) { _, _ -> error("must not run") }
        }
        val failure = classifyConversionFailure(IllegalStateException("C:/private/source", error))
        assertEquals("memory_budget", failure.reasonCode)
        assertTrue(failure.fallbackMessage.contains("resource=<unavailable>"))
        assertTrue(failure.fallbackMessage.contains("exclusiveBoundedPeakBytes=101"))
        assertTrue(!failure.fallbackMessage.contains("private"))
        assertEquals("format_limit", classifyConversionFailure(MpkgFormatLimitException("limit")).reasonCode)
        assertEquals("physical_disk", classifyConversionFailure(MpkgInsufficientSpaceException("full")).reasonCode)
        assertEquals("physical_disk", classifyConversionFailure(InsufficientDestinationSpaceException("reserved")).reasonCode)
        assertEquals("conversion_failed", classifyConversionFailure(IOException("source read failed")).reasonCode)
        assertEquals("scene_package_invalid", classifyConversionFailure(PkgFormatException("invalid")).reasonCode)
        assertEquals("project_invalid", classifyConversionFailure(IllegalArgumentException("invalid")).reasonCode)
    }
    @Test
    fun `two small resources overlap within budget and results retain archive order`() {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val release = CountDownLatch(1)
        val bothStarted = CountDownLatch(2)
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)
        val work = listOf(ResourceWork(0, 40, "first"), ResourceWork(1, 40, "second"))

        val outcome = AtomicReference<Result<List<String>>>()
        val thread =
            Thread {
                outcome.set(
                    runCatching {
                        pipeline.execute(work, {}) { item, _ ->
                            val active = running.incrementAndGet()
                            maximum.accumulateAndGet(active, ::maxOf)
                            bothStarted.countDown()
                            release.await()
                            running.decrementAndGet()
                            item.payload
                        }
                    },
                )
            }.apply { start() }

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
        release.countDown()
        thread.join(2_000)
        assertTrue(!thread.isAlive, "pipeline did not join its workers")
        assertEquals(listOf("first", "second"), outcome.get().getOrThrow())
        assertEquals(2, maximum.get())
    }

    @Test
    fun `combined overweight resources run one at a time`() {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)

        pipeline.execute(listOf(ResourceWork(0, 70, 1), ResourceWork(1, 70, 2)), {}) { item, _ ->
            maximum.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(25)
            running.decrementAndGet()
            item.payload
        }

        assertEquals(1, maximum.get())
    }

    @Test
    fun `first failure waits for uninterruptible sibling and preserves original error`() {
        val siblingStarted = CountDownLatch(1)
        val releaseSibling = CountDownLatch(1)
        val siblingExited = CountDownLatch(1)
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result =
                caller.submit<Unit> {
                    pipeline.execute(listOf(ResourceWork(0, 40, 0), ResourceWork(1, 40, 1)), {}) { item, _ ->
                        if (item.payload == 0) {
                            assertTrue(siblingStarted.await(2, TimeUnit.SECONDS))
                            throw IllegalStateException("original failure")
                        }
                        siblingStarted.countDown()
                        try {
                            while (releaseSibling.count > 0) {
                                try {
                                    releaseSibling.await()
                                } catch (_: InterruptedException) {
                                    // Models a bounded native call that does not cooperatively stop on interrupt.
                                }
                            }
                        } finally {
                            siblingExited.countDown()
                        }
                    }
                }

            assertTrue(siblingStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertTrue(!result.isDone, "pipeline returned while a sibling still owned CPU work")
            releaseSibling.countDown()
            val failure = assertFailsWith<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            assertEquals("original failure", failure.cause?.message)
            assertEquals(0, siblingExited.count)
        } finally {
            releaseSibling.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun `maximum shader is weighted for byte arrays strings rewrite and output`() {
        val weight = estimateNonTextureResourcePeakBytes("frag", MAX_SHADER_SOURCE_BYTES, ResourceStrategy.REWRITE_SHADER)

        assertEquals(MAX_SHADER_SOURCE_BYTES * 6L + 1024L * 1024L, weight)
        assertTrue(weight > MIN_RESOURCE_PIPELINE_WEIGHT_BYTES)
    }

    @Test
    fun `single resource larger than budget is rejected before execution`() {
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)
        var calls = 0

        assertFailsWith<IllegalArgumentException> {
            pipeline.execute(listOf(ResourceWork(0, 101, Unit)), {}) { _, _ -> calls += 1 }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `oversized resource whose internal peak exceeds the budget is rejected`() {
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)

        val error =
            assertFailsWith<IllegalArgumentException> {
                pipeline.execute(
                    listOf(ResourceWork(0, 101, Unit, exclusiveBoundedPeakBytes = 101)),
                    {},
                ) { _, _ -> error("must not run") }
            }

        assertEquals("Resource work exceeds the verified exclusive conversion memory bound", error.message)
    }

    @Test
    fun `safely bounded oversized resource executes exclusively`() {
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val oversizedStarted = CountDownLatch(1)
        val releaseOversized = CountDownLatch(1)
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)
        val work =
            listOf(
                ResourceWork(0, 101, "oversized", exclusiveBoundedPeakBytes = 80),
                ResourceWork(1, 20, "sibling"),
            )
        val outcome = AtomicReference<Result<List<String>>>()

        val thread =
            Thread {
                outcome.set(
                    runCatching {
                        pipeline.execute(work, {}) { item, _ ->
                            maximum.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            try {
                                if (item.payload == "oversized") {
                                    oversizedStarted.countDown()
                                    releaseOversized.await()
                                }
                                item.payload
                            } finally {
                                running.decrementAndGet()
                            }
                        }
                    },
                )
            }.apply { start() }

        assertTrue(oversizedStarted.await(2, TimeUnit.SECONDS))
        assertEquals(1, running.get())
        releaseOversized.countDown()
        thread.join(2_000)

        assertTrue(!thread.isAlive)
        assertEquals(listOf("oversized", "sibling"), outcome.get().getOrThrow())
        assertEquals(1, maximum.get(), "exclusive work overlapped a sibling")
    }

    @Test
    fun `parent cancellation interrupts admitted and waiting exclusive siblings and joins all work`() {
        val cancelled = AtomicBoolean()
        val active = AtomicInteger()
        val admittedStarted = CountDownLatch(1)
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)
        val outcome = AtomicReference<Result<List<Unit>>>()
        val caller =
            Thread {
                outcome.set(
                    runCatching {
                        pipeline.execute(
                            listOf(
                                ResourceWork(0, 80, Unit),
                                ResourceWork(1, 101, Unit, exclusiveBoundedPeakBytes = 80),
                            ),
                            { if (cancelled.get()) throw CancellationException("parent cancelled") },
                        ) { _, checkCancellation ->
                            active.incrementAndGet()
                            admittedStarted.countDown()
                            try {
                                while (true) {
                                    checkCancellation()
                                    Thread.sleep(10)
                                }
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    },
                )
            }.apply { start() }

        assertTrue(admittedStarted.await(2, TimeUnit.SECONDS))
        cancelled.set(true)
        caller.join(2_000)

        assertTrue(!caller.isAlive, "pipeline did not join cancelled workers")
        assertTrue(outcome.get().exceptionOrNull() is CancellationException)
        assertEquals(0, active.get(), "cancelled work survived the pipeline")
    }

    @Test
    fun `failed exclusive work releases the pipeline for a later execution`() {
        val pipeline = MemoryWeightedResourcePipeline(maxConcurrency = 2, memoryBudgetBytes = 100)

        val failure =
            assertFailsWith<IllegalStateException> {
                pipeline.execute(
                    listOf(ResourceWork(0, 101, Unit, exclusiveBoundedPeakBytes = 80)),
                    {},
                ) { _, _ -> throw IllegalStateException("transform failed") }
            }
        assertEquals("transform failed", failure.message)

        val result =
            pipeline.execute(listOf(ResourceWork(0, 20, "after")), {}) { item, _ ->
                item.payload
            }
        assertEquals(listOf("after"), result)
    }
}
