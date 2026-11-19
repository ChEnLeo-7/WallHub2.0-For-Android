package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SubscriptionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamWorkshopAccountMutationConfirmationTest {
    @Test
    fun `unsubscribe succeeds only when the SDK acknowledgement and remote read are absent`() {
        assertEquals(
            SubscriptionState.NOT_SUBSCRIBED,
            confirmRemoteSubscriptionMutation(
                requested = false,
                acknowledged = false,
                observed = SubscriptionState.NOT_SUBSCRIBED,
            ),
        )
    }

    @Test
    fun `unsubscribe fails when the remote read is unknown`() {
        assertFailsWith<IllegalStateException> {
            confirmRemoteSubscriptionMutation(
                requested = false,
                acknowledged = false,
                observed = SubscriptionState.UNKNOWN,
            )
        }
    }

    @Test
    fun `unsubscribe fails when the remote read still reports subscribed`() {
        assertFailsWith<IllegalStateException> {
            confirmRemoteSubscriptionMutation(
                requested = false,
                acknowledged = false,
                observed = SubscriptionState.SUBSCRIBED,
            )
        }
    }

    @Test
    fun `unsubscribe fails when the SDK acknowledges another state`() {
        assertFailsWith<IllegalStateException> {
            confirmRemoteSubscriptionMutation(
                requested = false,
                acknowledged = true,
                observed = SubscriptionState.NOT_SUBSCRIBED,
            )
        }
    }
}
