package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverFriendAccessTest {
    @Test
    fun `logged out friend results show login action without loading or pagination`() {
        val access =
            friendDiscoverAccess(
                sort = WorkshopSort.FRIENDS_FAVORITES,
                session = SteamSessionState(phase = SteamSessionPhase.SIGNED_OUT),
            )

        assertEquals(FriendDiscoverAccess.LOGIN_REQUIRED, access)
        val state = DiscoverResultsState(initialized = true).withFriendAccess(access)
        assertTrue(state.requiresSteamLogin)
        assertFalse(state.isLoading)
        assertFalse(state.hasMore)
    }

    @Test
    fun `restorable friend results request owner restore instead of becoming anonymous browse`() {
        val access =
            friendDiscoverAccess(
                sort = WorkshopSort.FRIENDS_CREATED,
                session =
                    SteamSessionState(
                        phase = SteamSessionPhase.RESTORABLE,
                        hasStoredSession = true,
                    ),
            )

        assertEquals(FriendDiscoverAccess.RESTORE_ACCOUNT, access)
        val state = DiscoverResultsState(initialized = true).withFriendAccess(access)
        assertTrue(state.isRestoringSteamSession)
        assertFalse(state.requiresSteamLogin)
        assertFalse(state.hasMore)
    }

    @Test
    fun `signed in friend results may use account pagination`() {
        val access =
            friendDiscoverAccess(
                sort = WorkshopSort.FRIENDS_FAVORITES,
                session = SteamSessionState(phase = SteamSessionPhase.SIGNED_IN, isConnected = true),
            )

        assertEquals(FriendDiscoverAccess.ACCOUNT_READY, access)
        val state = DiscoverResultsState(initialized = true).withFriendAccess(access)
        assertFalse(state.requiresSteamLogin)
        assertFalse(state.isRestoringSteamSession)
        assertTrue(state.hasMore)
    }
}
