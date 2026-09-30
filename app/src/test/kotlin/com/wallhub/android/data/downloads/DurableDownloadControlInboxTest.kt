package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.DownloadCommandEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableDownloadControlInboxTest {
    @Test
    fun `control is read only from the durable command inbox`() = runTest {
        val inbox = DurableDownloadControlInbox(commandDao(command("CANCEL")), "task") { 1L }

        assertEquals(SteamDownloadControl.CANCEL, inbox.current())
    }

    private fun command(value: String) =
        DownloadCommandEntity(
            commandId = "command",
            taskId = "task",
            revision = 1,
            attempt = 0,
            command = value,
            createdAt = 1,
        )

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
