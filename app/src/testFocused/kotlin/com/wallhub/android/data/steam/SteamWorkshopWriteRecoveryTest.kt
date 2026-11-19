package com.wallhub.android.data.steam

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamWorkshopWriteRecoveryTest {
    @Test
    fun `ambiguous authenticated write failure is never replayed`() =
        runTest {
            val session = RecordingRecoverableSession()
            val recovery =
                SteamSessionRequestRecovery(
                    session = session,
                    classify = { SteamRequestRecoveryCause.CONNECTION_FAILED_BEFORE_ANSWER },
                )
            var attempts = 0

            assertFailsWith<IllegalStateException> {
                recovery.executeOnce {
                    attempts += 1
                    error("the connection failed before the service answered")
                }
            }

            assertEquals(1, attempts)
            assertEquals(0, session.reconnects)
        }
}

private class RecordingRecoverableSession : RecoverableSteamSession {
    private val current = SteamSessionGeneration(7L)
    var reconnects = 0

    override fun generation(): SteamSessionGeneration = current

    override fun <T> executeIfCurrent(expected: SteamSessionGeneration, operation: () -> T): T {
        check(expected == current)
        return operation()
    }

    override suspend fun reconnectIfCurrent(expected: SteamSessionGeneration): Boolean {
        reconnects += 1
        return true
    }
}
