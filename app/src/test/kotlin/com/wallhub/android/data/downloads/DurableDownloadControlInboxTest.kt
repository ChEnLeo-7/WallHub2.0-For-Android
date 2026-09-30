package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.DownloadCommandEntity
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableDownloadControlInboxTest {
    @Test
    fun `new executor reads durable command instead of compatibility projection`() = runTest {
        val task = task(executorVersion = "NEW", requestedAction = null)
        val command = command("CANCEL")
        val inbox = DurableDownloadControlInbox(taskDao(task), commandDao(command), task.taskId) { 1L }

        assertEquals(SteamDownloadControl.CANCEL, inbox.current())
    }

    @Test
    fun `legacy executor keeps compatibility projection during drain`() = runTest {
        val task = task(executorVersion = "LEGACY", requestedAction = "PAUSE")
        val inbox = DurableDownloadControlInbox(taskDao(task), commandDao(null), task.taskId) { 1L }

        assertEquals(SteamDownloadControl.PAUSE, inbox.current())
    }

    private fun task(
        executorVersion: String,
        requestedAction: String?,
    ) =
        FormalTaskRecordEntity(
            taskId = "task",
            workshopId = 1,
            title = "task",
            type = "scene",
            status = "DOWNLOADING",
            requestedAction = requestedAction,
            createdAt = 1,
            updatedAt = 1,
            executorVersion = executorVersion,
        )

    private fun command(value: String) =
        DownloadCommandEntity(
            commandId = "command",
            taskId = "task",
            revision = 1,
            attempt = 0,
            command = value,
            createdAt = 1,
        )

    private fun taskDao(task: FormalTaskRecordEntity): FormalTaskRecordDao =
        proxy(FormalTaskRecordDao::class.java) { method ->
            when (method) {
                "find" -> task
                else -> error("Unexpected task DAO call $method")
            }
        }

    private fun commandDao(command: DownloadCommandEntity?): DownloadCommandDao =
        proxy(DownloadCommandDao::class.java) { method ->
            when (method) {
                "latestPendingForTask" -> command
                else -> error("Unexpected command DAO call $method")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        answer: (String) -> Any?,
    ): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answer(method.name) } as T
}
