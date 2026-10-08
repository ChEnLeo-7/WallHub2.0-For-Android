package com.wallhub.android.data.steam

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamWorkshopReadCoordinatorTest {
    @Test
    fun `bounded scheduler starts foreground before queued background`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = backgroundScope)
        val releaseActive = CompletableDeferred<Unit>()
        val activeEntered = CompletableDeferred<Unit>()
        val foregroundEntered = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val active = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.read("active", WorkshopReadKind.DETAILS, WorkshopReadPriority.BACKGROUND) {
                activeEntered.complete(Unit)
                releaseActive.await()
                "active"
            }
        }
        activeEntered.await()
        val background = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.read("background", WorkshopReadKind.SEARCH, WorkshopReadPriority.BACKGROUND) {
                order += "background"
                "background"
            }
        }
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.read("foreground", WorkshopReadKind.COMMENTS, WorkshopReadPriority.FOREGROUND) {
                order += "foreground"
                foregroundEntered.complete(Unit)
                "foreground"
            }
        }

        releaseActive.complete(Unit)
        foregroundEntered.await()
        active.await()
        foreground.await()
        background.await()

        assertEquals(listOf("foreground", "background"), order)
    }

    @Test
    fun `same key joins one read and returns its result`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 2, scope = backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = async {
            coordinator.read("detail-42", WorkshopReadKind.DETAILS, WorkshopReadPriority.FOREGROUND) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "answer"
            }
        }
        entered.await()
        val second = async {
            coordinator.read<String>("detail-42", WorkshopReadKind.DETAILS, WorkshopReadPriority.BACKGROUND) {
                error("single-flight joiner must not run")
            }
        }
        yield()
        release.complete(Unit)

        assertEquals("answer", first.await())
        assertEquals("answer", second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `Discover invalidation epoch prevents public read from joining an older flight`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 2, scope = backgroundScope)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val operation = "detail:42"

        val beforeInvalidation = async {
            coordinator.read(
                publicWorkshopReadKey(cacheEpoch = 0L, operation = operation),
                WorkshopReadKind.DETAILS,
                WorkshopReadPriority.FOREGROUND,
            ) {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                "stale"
            }
        }
        firstEntered.await()

        val afterInvalidation = async {
            coordinator.read(
                publicWorkshopReadKey(cacheEpoch = 1L, operation = operation),
                WorkshopReadKind.DETAILS,
                WorkshopReadPriority.FOREGROUND,
            ) {
                calls.incrementAndGet()
                secondEntered.complete(Unit)
                "fresh"
            }
        }

        secondEntered.await()
        assertEquals("fresh", afterInvalidation.await())
        assertEquals(2, calls.get())
        releaseFirst.complete(Unit)
        assertEquals("stale", beforeInvalidation.await())
    }

    @Test
    fun `cancelling one joiner preserves shared read for another caller`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        val owner = async {
            coordinator.read("same", WorkshopReadKind.DETAILS, WorkshopReadPriority.FOREGROUND) {
                entered.complete(Unit)
                try {
                    release.await()
                    7
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        entered.await()
        val joiner = async {
            coordinator.read("same", WorkshopReadKind.DETAILS, WorkshopReadPriority.FOREGROUND) { 8 }
        }
        yield()
        joiner.cancelAndJoin()
        assertFalse(cancelled.isCompleted)

        release.complete(Unit)
        assertEquals(7, owner.await())
    }

    @Test
    fun `cancelling the last caller cancels its running read`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 1, scope = backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val caller = async {
            coordinator.read("only", WorkshopReadKind.SEARCH, WorkshopReadPriority.BACKGROUND) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }

        entered.await()
        caller.cancelAndJoin()
        cancelled.await()
    }

    @Test
    fun `replacement with same key waits for cancelled operation to retire`() = runTest {
        val coordinator = SteamWorkshopReadCoordinator(maxConcurrent = 2, scope = backgroundScope)
        val firstEntered = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            coordinator.read("same", WorkshopReadKind.DETAILS, WorkshopReadPriority.FOREGROUND) {
                firstEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cancellationObserved.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        firstEntered.await()
        first.cancel()
        cancellationObserved.await()

        val replacement = async {
            coordinator.read("same", WorkshopReadKind.DETAILS, WorkshopReadPriority.FOREGROUND) {
                secondEntered.complete(Unit)
                "replacement"
            }
        }
        yield()
        assertFalse(secondEntered.isCompleted)

        releaseCleanup.complete(Unit)
        first.join()
        assertEquals("replacement", replacement.await())
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `timing callback exposes only sanitized operation metadata`() = runTest {
        val events = mutableListOf<WorkshopReadTiming>()
        var now = 10L
        val coordinator =
            SteamWorkshopReadCoordinator(
                maxConcurrent = 1,
                scope = backgroundScope,
                monotonicNanos = { now.also { now += 5L } },
                onTiming = events::add,
            )

        assertEquals(
            "ok",
            coordinator.read("secret-item-id", WorkshopReadKind.PLAYTIME, WorkshopReadPriority.FOREGROUND) {
                "ok"
            },
        )

        assertEquals(1, events.size)
        assertEquals(WorkshopReadKind.PLAYTIME, events.single().kind)
        assertEquals(WorkshopReadPriority.FOREGROUND, events.single().priority)
        assertEquals(WorkshopReadOutcome.SUCCEEDED, events.single().outcome)
        assertTrue(events.single().queueNanos >= 0L)
        assertTrue(events.single().runNanos >= 0L)
        assertFalse(events.single().toString().contains("secret-item-id"))
    }
}
