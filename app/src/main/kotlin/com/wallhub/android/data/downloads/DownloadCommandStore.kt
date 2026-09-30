package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.DownloadCommandEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable command/receipt seam used by the one process download host. */
@Singleton
internal class DownloadCommandStore
    @Inject
    constructor(
        private val dao: DownloadCommandDao,
    ) {
        suspend fun acquireEngineEpoch(
            ownerId: String = UUID.randomUUID().toString(),
            now: Long = System.currentTimeMillis(),
        ): Long = dao.acquireEngineEpoch(ownerId, now).epoch

        suspend fun claimPending(
            epoch: Long,
            limit: Int = DEFAULT_BATCH_SIZE,
            now: Long = System.currentTimeMillis(),
        ): List<DownloadCommandEntity> =
            dao.pending(limit.coerceIn(1, MAX_BATCH_SIZE)).filter { command ->
                dao.claim(command.commandId, epoch, now) == 1
            }

        suspend fun acknowledge(
            commandId: String,
            epoch: Long,
            receiptSequence: Long,
            result: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean =
            dao.acknowledge(
                commandId = commandId,
                epoch = epoch,
                receiptSequence = receiptSequence,
                result = result,
                acknowledgedAt = now,
            ) == 1

        private companion object {
            const val DEFAULT_BATCH_SIZE = 32
            const val MAX_BATCH_SIZE = 128
        }
    }
