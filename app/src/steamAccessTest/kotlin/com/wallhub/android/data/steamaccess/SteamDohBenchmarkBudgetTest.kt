package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full DoH benchmark is a user-triggered maintenance action, not a request-path activity. It still
 * must be bounded: a fixed endpoint budget, a fixed concurrency, and batches that stop when the total
 * budget is spent. Before this contract it submitted every configured endpoint (30 by default) at once.
 */
class SteamDohBenchmarkBudgetTest {
    @Test
    fun `the benchmark never plans more endpoints than its budget`() {
        val configured = (1..30).map { "https://doh$it.example/dns-query" }

        val batches = steamDohBenchmarkBatches(configured)

        assertEquals(DOH_BENCHMARK_MAX_ENDPOINTS, batches.flatten().size)
        assertEquals(configured.take(DOH_BENCHMARK_MAX_ENDPOINTS), batches.flatten())
    }

    @Test
    fun `no batch exceeds the concurrency limit`() {
        val configured = (1..30).map { "https://doh$it.example/dns-query" }

        val batches = steamDohBenchmarkBatches(configured)

        assertTrue(batches.all { it.size <= DOH_BENCHMARK_CONCURRENCY })
        assertEquals((DOH_BENCHMARK_MAX_ENDPOINTS + DOH_BENCHMARK_CONCURRENCY - 1) / DOH_BENCHMARK_CONCURRENCY, batches.size)
    }

    @Test
    fun `duplicates are removed and an empty catalog plans no work`() {
        assertEquals(emptyList(), steamDohBenchmarkBatches(emptyList()))
        val duplicated = listOf("https://a.example/dns-query", "https://a.example/dns-query")
        assertEquals(listOf(listOf("https://a.example/dns-query")), steamDohBenchmarkBatches(duplicated))
    }

    @Test
    fun `a single endpoint still produces exactly one batch`() {
        assertEquals(
            listOf(listOf("https://solo.example/dns-query")),
            steamDohBenchmarkBatches(listOf("https://solo.example/dns-query")),
        )
    }
}
