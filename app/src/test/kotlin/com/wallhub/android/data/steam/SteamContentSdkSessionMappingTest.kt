package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamSessionPhase
import io.steamcontent.sdk.ChallengeKind
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionFailure
import io.steamcontent.sdk.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SDK reports ordinals; the app does not. These tests hold every mirrored ordinal to a named value and
 * hold an ordinal this build has never seen to a named unknown instead of a wrong guess.
 */
class SteamContentSdkSessionMappingTest {
    @Test
    fun everyKnownSessionStateOrdinalMapsToANamedState() {
        val expected =
            mapOf(
                SessionState.DISCONNECTED to SteamContentSdkSessionState.DISCONNECTED,
                SessionState.CONNECTING to SteamContentSdkSessionState.CONNECTING,
                SessionState.CONNECTED to SteamContentSdkSessionState.CONNECTED,
                SessionState.AUTHENTICATING to SteamContentSdkSessionState.AUTHENTICATING,
                SessionState.AUTHENTICATED to SteamContentSdkSessionState.AUTHENTICATED,
                SessionState.RECONNECTING to SteamContentSdkSessionState.RECONNECTING,
                SessionState.CLOSED to SteamContentSdkSessionState.CLOSED,
            )
        expected.forEach { (ordinal, state) ->
            assertEquals(state, steamContentSdkSessionState(ordinal), "state ordinal $ordinal")
        }
        assertEquals(
            SteamContentSdkSessionState.UNKNOWN,
            steamContentSdkSessionState(Int.MIN_VALUE),
        )
        assertEquals(SteamContentSdkSessionState.UNKNOWN, steamContentSdkSessionState(99))
    }

    @Test
    fun everyKnownChallengeKindMapsToANamedKind() {
        val expected =
            mapOf(
                ChallengeKind.NONE to SteamContentSdkChallengeKind.NONE,
                ChallengeKind.EMAIL to SteamContentSdkChallengeKind.EMAIL,
                ChallengeKind.MOBILE to SteamContentSdkChallengeKind.MOBILE,
                ChallengeKind.DEVICE_CONFIRMATION to SteamContentSdkChallengeKind.DEVICE_CONFIRMATION,
                ChallengeKind.QR to SteamContentSdkChallengeKind.QR,
            )
        expected.forEach { (ordinal, kind) ->
            assertEquals(kind, steamContentSdkChallengeKind(ordinal), "challenge ordinal $ordinal")
        }
        assertEquals(SteamContentSdkChallengeKind.UNKNOWN, steamContentSdkChallengeKind(-1))
        assertEquals(SteamContentSdkChallengeKind.UNKNOWN, steamContentSdkChallengeKind(99))
    }

    @Test
    fun everyKnownFailureOrdinalMapsToANamedFailure() {
        val expected =
            mapOf(
                SessionFailure.NONE to SteamContentSdkSessionFailure.NONE,
                SessionFailure.INVALID_INPUT to SteamContentSdkSessionFailure.INVALID_INPUT,
                SessionFailure.AUTHENTICATION_FAILED to SteamContentSdkSessionFailure.AUTHENTICATION_FAILED,
                SessionFailure.NETWORK to SteamContentSdkSessionFailure.NETWORK,
                SessionFailure.UNSUPPORTED to SteamContentSdkSessionFailure.UNSUPPORTED,
                SessionFailure.SESSION_EXPIRED to SteamContentSdkSessionFailure.SESSION_EXPIRED,
                SessionFailure.CANCELLED to SteamContentSdkSessionFailure.CANCELLED,
                SessionFailure.INTERNAL to SteamContentSdkSessionFailure.INTERNAL,
                SessionFailure.PROTOCOL_ADAPTER to SteamContentSdkSessionFailure.PROTOCOL_ADAPTER,
            )
        expected.forEach { (ordinal, failure) ->
            assertEquals(failure, steamContentSdkSessionFailure(ordinal), "failure ordinal $ordinal")
        }
        assertEquals(SteamContentSdkSessionFailure.UNKNOWN, steamContentSdkSessionFailure(-1L))
        assertEquals(SteamContentSdkSessionFailure.UNKNOWN, steamContentSdkSessionFailure(Long.MAX_VALUE))
    }

    @Test
    fun reconnectSucceedsOnlyWhenTheTypedFailureIsNone() {
        assertTrue(SteamContentSdkReconnect().succeeded)
        SteamContentSdkSessionFailure.entries
            .filterNot { it == SteamContentSdkSessionFailure.NONE }
            .forEach { failure ->
                assertFalse(
                    SteamContentSdkReconnect(failure = failure).succeeded,
                    "failure $failure",
                )
            }
    }

    @Test
    fun everyKnownEventKindResolvesToAnEventWithoutARawOrdinal() {
        assertEquals(
            SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED),
            steamContentSdkSessionEvent(SessionEventKind.STATE_CHANGED, SessionState.AUTHENTICATED.toLong()),
        )
        assertEquals(
            SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.MOBILE),
            steamContentSdkSessionEvent(
                SessionEventKind.AUTHENTICATION_CHALLENGE,
                ChallengeKind.MOBILE.toLong(),
            ),
        )
        assertEquals(
            SteamContentSdkSessionEvent.PendingFailed,
            steamContentSdkSessionEvent(SessionEventKind.PENDING_FAILED, 0L),
        )
        assertEquals(
            SteamContentSdkSessionEvent.LoggedOut(revoked = true),
            steamContentSdkSessionEvent(SessionEventKind.LOGGED_OUT, 1L),
        )
        assertEquals(
            SteamContentSdkSessionEvent.LoggedOut(revoked = false),
            steamContentSdkSessionEvent(SessionEventKind.LOGGED_OUT, 0L),
        )
        assertEquals(
            SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.SESSION_EXPIRED),
            steamContentSdkSessionEvent(
                SessionEventKind.COMMAND_FAILED,
                SessionFailure.SESSION_EXPIRED,
            ),
        )
        assertEquals(
            SteamContentSdkSessionEvent.Heartbeat,
            steamContentSdkSessionEvent(SessionEventKind.HEARTBEAT, 2L),
        )
        assertEquals(SteamContentSdkSessionEvent.Unknown, steamContentSdkSessionEvent(99, 0L))
    }

    @Test
    fun aChallengeKindDistinguishesACodePromptFromADeviceConfirmation() {
        assertEquals(
            SteamSessionPhase.WAITING_FOR_CODE,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.EMAIL),
        )
        assertEquals(
            SteamSessionPhase.WAITING_FOR_CODE,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.MOBILE),
        )
        assertEquals(
            SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.DEVICE_CONFIRMATION),
        )
        assertEquals(
            SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.QR),
        )
        assertEquals(
            SteamSessionPhase.SIGNING_IN,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.NONE),
        )
        assertEquals(
            SteamSessionPhase.FAILED,
            steamContentSdkChallengePhase(SteamContentSdkChallengeKind.UNKNOWN),
        )
    }

    @Test
    fun aFailureOrdinalKeepsNotSignedInChallengeAndFailedDistinguishable() {
        assertEquals(
            SteamSessionPhase.SIGNED_OUT,
            steamContentSdkFailurePhase(SteamContentSdkSessionFailure.CANCELLED),
        )
        assertEquals(
            SteamSessionPhase.EXPIRED,
            steamContentSdkFailurePhase(SteamContentSdkSessionFailure.SESSION_EXPIRED),
        )
        assertEquals(
            SteamSessionPhase.SIGNING_IN,
            steamContentSdkFailurePhase(SteamContentSdkSessionFailure.NONE),
        )
        listOf(
            SteamContentSdkSessionFailure.INVALID_INPUT,
            SteamContentSdkSessionFailure.AUTHENTICATION_FAILED,
            SteamContentSdkSessionFailure.NETWORK,
            SteamContentSdkSessionFailure.UNSUPPORTED,
            SteamContentSdkSessionFailure.INTERNAL,
            SteamContentSdkSessionFailure.PROTOCOL_ADAPTER,
            SteamContentSdkSessionFailure.UNKNOWN,
        ).forEach { failure ->
            assertEquals(SteamSessionPhase.FAILED, steamContentSdkFailurePhase(failure), "failure $failure")
        }
    }

    @Test
    fun anAuthenticatedStateAloneMeansSignedIn() {
        assertEquals(
            SteamSessionPhase.SIGNED_IN,
            steamContentSdkSessionPhase(SteamContentSdkSessionState.AUTHENTICATED),
        )
        assertEquals(
            SteamSessionPhase.SIGNING_IN,
            steamContentSdkSessionPhase(SteamContentSdkSessionState.AUTHENTICATING),
        )
        assertEquals(
            SteamSessionPhase.SIGNED_OUT,
            steamContentSdkSessionPhase(SteamContentSdkSessionState.DISCONNECTED),
        )
        assertEquals(
            SteamSessionPhase.FAILED,
            steamContentSdkSessionPhase(SteamContentSdkSessionState.UNKNOWN),
        )
        assertIs<SteamContentSdkSessionState>(SteamContentSdkSessionState.CLOSED)
    }

    @Test
    fun `only a remote result authorises deleting the stored credential`() {
        // The device reading: a CM route failure arrives with the same authentication class as a refusal, and
        // clearing on that class deleted a working account.
        assertTrue(serviceRefusedCredential("authentication failed during AccessTokenLogon cause=RemoteResult (result=Some(15))"))
        assertFalse(serviceRefusedCredential("authentication failed during AccessTokenLogon cause=CmRoute (result=None)"))
        assertFalse(serviceRefusedCredential("authentication failed during AccessTokenLogon cause=RemoteClosed (result=None)"))
        assertFalse(serviceRefusedCredential(""))
        assertFalse(serviceRefusedCredential(null))
    }

    @Test
    fun `only an attempt in progress makes a download wait`() {
        // The predicate decides whether a download waits for the account session instead of opening a competing
        // one, so it must cover exactly the phases that mean "an attempt is running".
        assertTrue(SteamSessionPhase.SIGNING_IN.isAttemptInFlight())
        assertTrue(SteamSessionPhase.WAITING_FOR_CODE.isAttemptInFlight())
        assertTrue(SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION.isAttemptInFlight())
        assertFalse(SteamSessionPhase.SIGNED_IN.isAttemptInFlight())
        assertFalse(SteamSessionPhase.SIGNED_OUT.isAttemptInFlight())
        assertFalse(SteamSessionPhase.FAILED.isAttemptInFlight())
        assertFalse(SteamSessionPhase.RESTORABLE.isAttemptInFlight())
        assertFalse(SteamSessionPhase.EXPIRED.isAttemptInFlight())
    }
}
