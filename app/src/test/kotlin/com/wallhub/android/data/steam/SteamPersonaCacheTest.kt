package com.wallhub.android.data.steam

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamPersonaCacheTest {
    @Test
    fun `positive entries remain cached for twenty minutes`() = runTest {
        var now = 0L
        var calls = 0
        val profile = SteamProfile(displayName = "Ada")
        val cache =
            SteamPersonaCache(
                clockMillis = { now },
                loader = SteamPersonaLoader {
                    calls += 1
                    mapOf(1L to profile)
                },
                scope = backgroundScope,
            )

        assertEquals(profile, cache.get(1L))
        now = 20 * 60 * 1_000L - 1
        assertEquals(profile, cache.get(1L))
        assertEquals(1, calls)

        now += 1
        assertEquals(profile, cache.get(1L))
        assertEquals(2, calls)
    }

    @Test
    fun `missing personas remain negatively cached for ninety seconds`() = runTest {
        var now = 0L
        var calls = 0
        val cache =
            SteamPersonaCache(
                clockMillis = { now },
                loader = SteamPersonaLoader {
                    calls += 1
                    emptyMap()
                },
                scope = backgroundScope,
            )

        assertEquals(null, cache.get(9L))
        now = 90_000L - 1
        assertEquals(null, cache.get(9L))
        assertEquals(1, calls)

        now += 1
        assertEquals(null, cache.get(9L))
        assertEquals(2, calls)
    }

    @Test
    fun `least recently used entry is evicted when the cache reaches its bound`() = runTest {
        val calls = mutableListOf<Set<Long>>()
        val cache =
            SteamPersonaCache(
                maxEntries = 2,
                loader = SteamPersonaLoader { ids ->
                    calls += ids
                    ids.associateWith { SteamProfile(displayName = "profile-$it") }
                },
                scope = backgroundScope,
            )

        cache.getAll(listOf(1L, 2L))
        cache.get(1L)
        cache.get(3L)
        cache.get(2L)

        assertEquals(listOf(setOf(1L, 2L), setOf(3L), setOf(2L)), calls)
    }

    @Test
    fun `one request de-duplicates ids into a single loader batch`() = runTest {
        val batches = mutableListOf<Set<Long>>()
        val cache =
            SteamPersonaCache(
                loader = SteamPersonaLoader { ids ->
                    batches += ids
                    ids.associateWith { SteamProfile(displayName = it.toString()) }
                },
                scope = backgroundScope,
            )

        val result = cache.getAll(listOf(2L, 1L, 2L, 1L))

        assertEquals(setOf(1L, 2L), result.keys)
        assertEquals(listOf(setOf(2L, 1L)), batches)
    }

    @Test
    fun `overlapping requests coalesce their shared in-flight id`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val batches = mutableListOf<Set<Long>>()
        val cache =
            SteamPersonaCache(
                loader = SteamPersonaLoader { ids ->
                    batches += ids
                    if (1L in ids) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    ids.associateWith { SteamProfile(displayName = it.toString()) }
                },
                scope = backgroundScope,
            )

        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getAll(listOf(1L, 2L)) }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.getAll(listOf(2L, 3L)) }
        yield()
        releaseFirst.complete(Unit)

        assertEquals(setOf(1L, 2L), first.await().keys)
        assertEquals(setOf(2L, 3L), second.await().keys)
        assertEquals(1, batches.count { 2L in it })
        assertEquals(2, batches.size)
    }

    @Test
    fun `cancelling one waiter preserves shared loading for the remaining waiter`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loaderFinished = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val profile = SteamProfile(displayName = "Shared")
        val cache =
            SteamPersonaCache(
                loader = SteamPersonaLoader {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    try {
                        release.await()
                        mapOf(1L to profile)
                    } finally {
                        loaderFinished.complete(Unit)
                    }
                },
                scope = backgroundScope,
            )

        val first = async { cache.get(1L) }
        entered.await()
        val cancelledWaiter = async { cache.get(1L) }
        yield()
        cancelledWaiter.cancelAndJoin()
        assertFalse(loaderFinished.isCompleted)

        release.complete(Unit)
        assertEquals(profile, first.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancelling the final waiter cancels the loader and does not cache a miss`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val loaderCancelled = CompletableDeferred<Unit>()
        var calls = 0
        val cache =
            SteamPersonaCache(
                loader = SteamPersonaLoader { ids ->
                    calls += 1
                    if (calls == 1) {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            loaderCancelled.complete(Unit)
                        }
                    }
                    ids.associateWith { SteamProfile(displayName = it.toString()) }
                },
                scope = backgroundScope,
            )

        val waiter = async { cache.get(1L) }
        entered.await()
        waiter.cancelAndJoin()
        loaderCancelled.await()

        assertEquals(SteamProfile("1"), cache.get(1L))
        assertEquals(2, calls)
    }

    @Test
    fun `empty lookup does not invoke the loader`() = runTest {
        var called = false
        val cache =
            SteamPersonaCache(
                loader = SteamPersonaLoader {
                    called = true
                    emptyMap()
                },
                scope = backgroundScope,
            )

        assertTrue(cache.getAll(emptyList()).isEmpty())
        assertFalse(called)
    }
}
