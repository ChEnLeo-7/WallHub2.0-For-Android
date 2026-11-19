package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCounts
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns the process count generation so a late account response cannot republish after invalidation. */
internal class AccountWorkshopCountStateOwner(
    private val beforePublish: () -> Unit = {},
) {
    private val generation = AtomicLong()
    val state = MutableStateFlow(AccountWorkshopCounts())

    @Synchronized
    fun begin(accountName: String): Long =
        generation.incrementAndGet().also {
            state.value = AccountWorkshopCounts(accountName = accountName, isLoading = true, isValid = true)
        }

    @Synchronized
    fun complete(
        token: Long,
        accountName: String,
        subscriptions: Int?,
        favorites: Int?,
    ): Boolean =
        publish(token) {
            AccountWorkshopCounts(
                accountName = accountName,
                subscriptions = subscriptions,
                favorites = favorites,
                isValid = true,
            )
        }

    @Synchronized
    fun fail(token: Long, accountName: String): Boolean =
        publish(token) { AccountWorkshopCounts(accountName = accountName, isValid = true) }

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        state.value = AccountWorkshopCounts()
    }

    private inline fun publish(
        token: Long,
        value: () -> AccountWorkshopCounts,
    ): Boolean {
        if (generation.get() != token) return false
        beforePublish()
        state.value = value()
        return true
    }
}
