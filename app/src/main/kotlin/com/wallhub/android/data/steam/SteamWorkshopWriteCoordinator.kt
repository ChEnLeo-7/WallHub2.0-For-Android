package com.wallhub.android.data.steam

import java.util.concurrent.atomic.AtomicLong

/**
 * Admits authenticated Workshop mutations as independent foreground operations.
 *
 * Every invocation receives a unique scheduler key. Unlike reads, two writes for the same item and operation
 * must never join: both represent user intent and both must reach Steam in call order. The shared request
 * generation owner admits each operation exactly once. A session change before admission or an ambiguous
 * transport failure escapes unchanged: authenticated writes are never automatically replayed.
 */
internal class SteamWorkshopWriteCoordinator(
    private val requests: SteamWorkshopReadCoordinator,
    private val recovery: SteamSessionRequestRecovery,
) {
    private val sequence = AtomicLong()

    suspend fun <T> write(
        kind: WorkshopReadKind,
        operation: () -> T,
    ): T {
        require(
            kind == WorkshopReadKind.SET_SUBSCRIBED ||
                kind == WorkshopReadKind.SET_FAVORITED ||
                kind == WorkshopReadKind.POST_COMMENT,
        ) { "Workshop write coordinator only accepts mutation kinds" }
        val requestId = sequence.getAndIncrement()
        return requests.read(
            key = "write:$requestId",
            kind = kind,
            priority = WorkshopReadPriority.FOREGROUND,
        ) {
            recovery.executeOnce(operation)
        }
    }
}
