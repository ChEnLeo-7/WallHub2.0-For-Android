package com.wallhub.android.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCoverPrefetchFlowTest {
    private fun viewport(last: Int, budget: HomeCoverPrefetchBudget = HomeCoverPrefetchBudget.UNMETERED) =
        HomeCoverViewport(last, 30, 2, false, budget)

    @Test
    fun `same visible row does not cancel pending prefetch`() = runTest {
        val events = MutableSharedFlow<HomeCoverViewport>()
        val completed = mutableListOf<Int>()
        val job = backgroundScope.launch { events.collectHomeCoverPrefetch { completed += it.lastIndex } }
        runCurrent()
        events.emit(viewport(5))
        advanceTimeBy(60)
        events.emit(viewport(5))
        advanceTimeBy(61)
        runCurrent()
        assertEquals(listOf(5), completed)
        job.cancel()
    }

    @Test
    fun `cancelled in flight batch can resume at the same frontier`() = runTest {
        val events = MutableSharedFlow<HomeCoverViewport>()
        val started = mutableListOf<Int>()
        var block = true
        val job = backgroundScope.launch {
            events.collectHomeCoverPrefetch {
                started += it.lastIndex
                if (block) awaitCancellation()
            }
        }
        runCurrent()
        events.emit(viewport(5))
        advanceTimeBy(121)
        runCurrent()
        events.emit(viewport(5, HomeCoverPrefetchBudget.DISABLED))
        runCurrent()
        block = false
        events.emit(viewport(5))
        advanceTimeBy(121)
        runCurrent()
        assertEquals(listOf(5, 5), started)
        job.cancel()
    }

    @Test
    fun `rapid row changes prefetch only the latest row`() = runTest {
        val events = MutableSharedFlow<HomeCoverViewport>()
        val completed = mutableListOf<Int>()
        val job = backgroundScope.launch { events.collectHomeCoverPrefetch { completed += it.lastIndex } }
        runCurrent()
        events.emit(viewport(5))
        advanceTimeBy(60)
        events.emit(viewport(7))
        advanceTimeBy(121)
        runCurrent()
        assertEquals(listOf(7), completed)
        job.cancel()
    }
}
