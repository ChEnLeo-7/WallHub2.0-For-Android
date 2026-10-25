package com.wallhub.android.data.steam

import java.util.concurrent.atomic.AtomicLong

/**
 * Admits authenticated Workshop mutations as independent foreground operations.
 *
 * Every invocation receives a unique scheduler key. Unlike reads, two writes for the same item and operation
 * must never join: both represent user intent and both must reach Steam in call order. The shared request
 * recovery captures the session generation before each operation, reconnects once when that generation is no
 * longer usable, and replays only that operation. Any failure after that bounded recovery escapes unchanged.
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
            recovery.execute(operation)
        }
    }
}
