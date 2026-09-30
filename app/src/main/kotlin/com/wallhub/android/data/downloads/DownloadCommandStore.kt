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

        suspend fun claimTask(
            taskId: String,
            epoch: Long,
            limit: Int = DEFAULT_BATCH_SIZE,
            now: Long = System.currentTimeMillis(),
        ): List<DownloadCommandEntity> =
            dao.pendingForTask(taskId, limit.coerceIn(1, MAX_BATCH_SIZE)).filter { command ->
                dao.claim(command.commandId, epoch, now) == 1
            }

        suspend fun receiptSequenceBase(
            taskId: String,
            epoch: Long,
        ): Long = dao.receiptSequenceBase(taskId, epoch) ?: 0L

        suspend fun acknowledge(
            command: DownloadCommandEntity,
            epoch: Long,
            receiptSequence: Long,
            result: String,
            quiesced: Boolean,
            now: Long = System.currentTimeMillis(),
        ): Boolean =
            dao.acknowledgeAndProject(
                command = command,
                epoch = epoch,
                receiptSequence = receiptSequence,
                result = result,
                acknowledgedAt = now,
                quiesced = quiesced,
            )

        private companion object {
            const val DEFAULT_BATCH_SIZE = 32
            const val MAX_BATCH_SIZE = 128
        }
    }
