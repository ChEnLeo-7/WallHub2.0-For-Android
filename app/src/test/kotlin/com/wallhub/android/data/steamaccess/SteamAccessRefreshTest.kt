package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.SteamAccessPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamAccessRefreshTest {
    @Test fun `enabled refresh starts resolving and disabled refresh stays disabled`() {
        assertEquals(SteamAccessPhase.RESOLVING, steamAccessRefreshStartPhase(enabled = true))
        assertEquals(SteamAccessPhase.DISABLED, steamAccessRefreshStartPhase(enabled = false))
    }

    @Test fun `refresh failure without a usable route is failed not ready`() {
        assertEquals(
            SteamAccessPhase.FAILED,
            steamAccessRefreshFailurePhase(IOException("resolution failed"), hasUsableRoute = false),
        )
        assertEquals(
            SteamAccessPhase.DEGRADED,
            steamAccessRefreshFailurePhase(IllegalStateException("cleanup failed"), hasUsableRoute = true),
        )
    }

    @Test fun `refresh failure boundary never consumes cancellation`() {
        assertFailsWith<CancellationException> {
            steamAccessRefreshFailurePhase(CancellationException("cancelled"), hasUsableRoute = false)
        }
    }

    @Test fun `refresh gate admits one attempt until the running attempt finishes`() {
        val gate = SteamAccessRefreshGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }
}
