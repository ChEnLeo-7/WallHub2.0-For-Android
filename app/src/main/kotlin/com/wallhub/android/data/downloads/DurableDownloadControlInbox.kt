package com.wallhub.android.data.downloads

import android.os.SystemClock
import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.model.DownloadAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Low-rate durable-command projection reader used while a native transfer is active. */
internal class DurableDownloadControlInbox(
    private val taskDao: FormalTaskRecordDao,
    private val commandDao: DownloadCommandDao,
    private val taskId: String,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutex = Mutex()
    private var cachedControl = SteamDownloadControl.CONTINUE
    private var nextRefreshAt = 0L

    suspend fun current(): SteamDownloadControl =
        mutex.withLock {
            val now = nowMs()
            if (now >= nextRefreshAt) {
                val task = taskDao.find(taskId)
                val command =
                    if (task?.executorVersion == "NEW") {
                        commandDao.latestPendingForTask(taskId)?.command
                    } else {
                        task?.requestedAction
                    }
                cachedControl =
                    when (command) {
                        DownloadAction.PAUSE.name -> SteamDownloadControl.PAUSE
                        DownloadAction.CANCEL.name -> SteamDownloadControl.CANCEL
                        else -> SteamDownloadControl.CONTINUE
                    }
                nextRefreshAt = now + CONTROL_RECOVERY_POLL_MS
            }
            cachedControl
        }

    private companion object {
        const val CONTROL_RECOVERY_POLL_MS = 300L
    }
}
