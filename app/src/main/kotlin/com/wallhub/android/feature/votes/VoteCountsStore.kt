package com.wallhub.android.feature.votes

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-only observations, never persisted totals or a reason to start scanning. */
data class VoteCounts(val up: Int, val down: Int, val complete: Boolean)

@Singleton
class VoteCountsStore @Inject constructor() {
    private val mutableCounts = MutableStateFlow<Map<String, VoteCounts>>(emptyMap())
    val counts: StateFlow<Map<String, VoteCounts>> = mutableCounts.asStateFlow()
    private val mutableCountsByAccountName = MutableStateFlow<Map<String, VoteCounts>>(emptyMap())
    val countsByAccountName: StateFlow<Map<String, VoteCounts>> = mutableCountsByAccountName.asStateFlow()
    private var accountName: String? = null

    @Synchronized
    fun bindAccount(name: String?) {
        if (accountName != name) {
            accountName = name
            clear()
        }
    }

    @Synchronized
    fun publish(steamId: String, counts: VoteCounts) {
        mutableCounts.value = mutableCounts.value + (steamId to counts)
        accountName?.let { mutableCountsByAccountName.value = mapOf(it to counts) }
    }

    @Synchronized
    fun clear() {
        mutableCounts.value = emptyMap()
        mutableCountsByAccountName.value = emptyMap()
    }
}
