package com.wallhub.android.data.steam

import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamVerificationMethod
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Exercises the SDK session backend against fakes: no device, no native library, no network.
 *
 * The tests deliberately embed no token or password fixture. Every secret is an opaque value this test
 * builds at run time, and the pass-through assertions compare the fake's captured bytes with the same
 * value the test supplied rather than with a literal.
 */
class SteamContentSdkSessionBackendTest {
    @Test
    fun `short account sessions accrue bounded exponential recovery debt`() {
        assertEquals(0L, steamAccountRecoveryDelayMs(0))
        assertEquals(1_000L, steamAccountRecoveryDelayMs(1))
        assertEquals(2_000L, steamAccountRecoveryDelayMs(2))
        assertEquals(4_000L, steamAccountRecoveryDelayMs(3))
        assertEquals(32_000L, steamAccountRecoveryDelayMs(6))
        assertEquals(60_000L, steamAccountRecoveryDelayMs(7))
        assertEquals(60_000L, steamAccountRecoveryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun aStoredTokenIsPassedToTheSdkExactlyOnce() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 3))
            fixture.storeCredential(credential)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(1, fixture.platform.openCalls)
            assertEquals(1, fixture.session.restores.size)
            val restore = fixture.session.restores.single()
            assertEquals(credential.accountName, restore.accountName)
            assertContentEquals(credential.refreshToken.toByteArray(Charsets.UTF_8), restore.refreshToken)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertTrue(backend.session.value.isConnected)
            assertEquals(0, fixture.session.closeCalls)
        }

    @Test
    fun authenticatedStateIsPublishedBeforeOptionalAccountProfileEnrichment() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential("account", opaqueValue(seed = 67)))
            fixture.session.accountProfile = SteamContentSdkAccountProfile("persona", "avatar")
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            runCurrent()

            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertNull(backend.session.value.personaName)
            advanceTimeBy(2_001L)
            runCurrent()
            assertEquals("persona", backend.session.value.personaName)
            assertEquals("avatar", backend.session.value.avatarUrl)
        }

    @Test
    fun aSuccessfulRestorePersistsTheTokenTheSdkRetained() =
        runTest {
            val fixture = Fixture()
            val presented = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 71))
            val retained = opaqueValue(seed = 89)
            fixture.storeCredential(presented)
            fixture.session.retainedToken = retained.toByteArray(Charsets.UTF_8)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // The authenticated edge of a restore is where a token the SDK rotated on use has to be stored, or
            // the next start re-presents a token Steam has already replaced. Exactly one open and one write.
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(1, fixture.session.restores.size)
            assertEquals(1, fixture.store.writeCalls)
            val stored =
                (fixture.store.payload as EncryptedStringReadResult.Value).value.let(::decodeSteamCredential)
            assertEquals(presented.accountName, stored.accountName)
            assertEquals(retained, stored.refreshToken)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun aRetainedTokenWriteThatReadsBackDifferentlyIsNotReportedAsStored() =
        runTest {
            val fixture = Fixture()
            val retained = opaqueValue(seed = 91)
            fixture.session.retainedToken = retained.toByteArray(Charsets.UTF_8)
            fixture.store.writeReplacement =
                encodeSteamCredential(
                    PersistedSteamCredential(accountName = "other", refreshToken = opaqueValue(seed = 93)),
                )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 95))
            advanceUntilIdle()

            assertEquals(1, fixture.store.writeCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertFalse(backend.session.value.hasStoredSession)
            val roundTrip = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            assertTrue(roundTrip.contains("credential_roundtrip match=false unchanged=false"), roundTrip.toString())
        }

    @Test
    fun aRestoreThatRetainsNoTokenDoesNotOverwriteTheStoredOne() =
        runTest {
            val fixture = Fixture()
            val presented = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 97))
            fixture.storeCredential(presented)
            // The SDK retains nothing this time. A blank result must never be written over a working credential.
            fixture.session.retainedToken = null
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertEquals(0, fixture.store.writeCalls)
            val stored =
                (fixture.store.payload as EncryptedStringReadResult.Value).value.let(::decodeSteamCredential)
            assertEquals(presented.refreshToken, stored.refreshToken)
        }

    @Test
    fun anUnchangedRetainedTokenDoesNotRewriteTheStoredOne() =
        runTest {
            val fixture = Fixture()
            val presented = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 101))
            fixture.storeCredential(presented)
            fixture.session.retainedToken = presented.refreshToken.toByteArray(Charsets.UTF_8)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // A healthy session does not rewrite encrypted storage on every start.
            assertEquals(0, fixture.store.writeCalls)
            val stored =
                (fixture.store.payload as EncryptedStringReadResult.Value).value.let(::decodeSteamCredential)
            assertEquals(presented.refreshToken, stored.refreshToken)
        }

    @Test
    fun aMissingStoredTokenFailsWithoutOpeningTheSdk() =
        runTest {
            val fixture = Fixture()
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(0, fixture.platform.openCalls)
            assertEquals(0, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertFalse(backend.session.value.hasStoredSession)
        }

    @Test
    fun anUnreadableStoredTokenFailsWithTheStorageMessage() =
        runTest {
            val fixture = Fixture()
            fixture.store.payload = EncryptedStringReadResult.Unreadable(IllegalStateException("key unavailable"))
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(0, fixture.platform.openCalls)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.sessionStorageUnavailable, backend.session.value.message)
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun anUnavailableSdkSessionFailsInsteadOfFallingBack() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 5)))
            fixture.platform.openFailure = IllegalStateException("the platform route is unavailable")
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(1, fixture.platform.openCalls)
            assertEquals(0, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.unavailable, backend.session.value.message)
        }

    @Test
    fun aLogonReachesTheSdkWithTheCallerSecretAndThenSignsIn() =
        runTest {
            val fixture = Fixture()
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            val secret = opaqueValue(seed = 7)

            backend.login("account", secret)
            advanceUntilIdle()

            assertEquals(1, fixture.session.logons.size)
            val logon = fixture.session.logons.single()
            assertEquals("account", logon.accountName)
            assertContentEquals(secret.toByteArray(Charsets.UTF_8), logon.secret)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun blankCredentialsFailWithoutOpeningTheSdk() =
        runTest {
            val fixture = Fixture()
            val backend = fixture.backend(this)

            backend.login("  ", "  ")
            advanceUntilIdle()

            assertEquals(0, fixture.platform.openCalls)
            assertEquals(0, fixture.session.logons.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.enterCredentials, backend.session.value.message)
        }

    @Test
    fun aCodeChallengeBecomesTheCodePhaseAndTheGuardAnswerReachesTheSdk() =
        runTest {
            val fixture = Fixture()
            val challengeId = opaqueValue(seed = 11).toByteArray(Charsets.UTF_8)
            fixture.session.pendingChallengeId = challengeId
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.EMAIL)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 13))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.WAITING_FOR_CODE, backend.session.value.phase)
            assertTrue(backend.session.value.requiresCode)
            assertEquals(SteamVerificationMethod.EMAIL_CODE, backend.session.value.verificationMethod)
            assertEquals(FakeMessages.enterEmailCode, backend.session.value.message)

            val code = opaqueValue(seed = 17)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            backend.submitSteamGuardCode(code)
            advanceUntilIdle()

            assertEquals(1, fixture.session.answers.size)
            assertContentEquals(challengeId, fixture.session.answers.single().challengeId)
            assertContentEquals(code.toByteArray(Charsets.UTF_8), fixture.session.answers.single().answer)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun aMobileCodeChallengeUsesTheAuthenticatorSpecificState() =
        runTest {
            val fixture = Fixture()
            fixture.session.pendingChallengeId = opaqueValue(seed = 18).toByteArray(Charsets.UTF_8)
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.MOBILE)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 20))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.WAITING_FOR_CODE, backend.session.value.phase)
            assertEquals(SteamVerificationMethod.MOBILE_AUTHENTICATOR_CODE, backend.session.value.verificationMethod)
            assertEquals(FakeMessages.enterMobileCode, backend.session.value.message)
        }

    @Test
    fun aRejectedGuardCodeKeepsTheMatchingVerificationPage() =
        runTest {
            val fixture = Fixture()
            fixture.session.pendingChallengeId = opaqueValue(seed = 22).toByteArray(Charsets.UTF_8)
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.EMAIL)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 24))
            advanceUntilIdle()
            fixture.session.events += SteamContentSdkSessionEvent.PendingFailed
            backend.submitSteamGuardCode(opaqueValue(seed = 26))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.WAITING_FOR_CODE, backend.session.value.phase)
            assertEquals(SteamVerificationMethod.EMAIL_CODE, backend.session.value.verificationMethod)
            assertEquals(FakeMessages.authRejected, backend.session.value.message)
        }

    @Test
    fun aGuardAnswerWithoutAPendingChallengeFails() =
        runTest {
            val fixture = Fixture()
            val backend = fixture.backend(this)

            backend.submitSteamGuardCode(opaqueValue(seed = 19))
            advanceUntilIdle()

            assertEquals(0, fixture.session.answers.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.noGuardRequest, backend.session.value.message)
        }

    @Test
    fun aDeviceChallengeDoesNotFailAndTheSessionStillCompletes() =
        runTest {
            val fixture = Fixture()
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.DEVICE_CONFIRMATION)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 23))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertEquals(0, fixture.session.answers.size)
        }

    @Test
    fun aManualGuardFallbackCannotReachTheSessionAfterANewerLoginStarts() =
        runTest {
            val fixture = Fixture()
            fixture.session.guardChoices = SteamContentSdkGuardChoice.EMAIL_CODE_BIT
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.DEVICE_CONFIRMATION)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            fixture.session.beforePoll = { poll ->
                if (poll == 2) {
                    backend.useManualSteamGuardFallback()
                    backend.login("new-account", opaqueValue(seed = 27))
                }
            }

            backend.login("old-account", opaqueValue(seed = 25))
            advanceUntilIdle()

            assertTrue(fixture.session.chosenGuards.isEmpty())
            assertEquals("new-account", fixture.session.logons.last().accountName)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun anAuthenticationFailurePublishesFailed() =
        runTest {
            val fixture = Fixture()
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 29))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.authRejected, backend.session.value.message)
            assertEquals(1, fixture.session.closeCalls)
        }

    @Test
    fun aTransportFailureOnLogonRetriesOnceWithLiveCandidatesAndTheSameSecret() =
        runTest {
            val fixture = Fixture()
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            val secret = opaqueValue(seed = 53)

            backend.login("account", secret)
            advanceUntilIdle()

            // One extra open, on live candidates, and one extra logon that re-presents the caller's own
            // secret: the first attempt's cached endpoint set is the input under suspicion, so the retry
            // never re-offers it, and no credential is stored to make that retry possible.
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(1, fixture.platform.liveOpenCalls)
            assertEquals(2, fixture.session.logons.size)
            assertEquals("account", fixture.session.logons.last().accountName)
            assertContentEquals(secret.toByteArray(Charsets.UTF_8), fixture.session.logons.last().secret)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun aTransportFailureOnLogonIsRetriedOnlyOnce() =
        runTest {
            val fixture = Fixture()
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 59))
            advanceUntilIdle()

            // The second transport failure is reported, not retried: one logon means one extra open at most.
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(1, fixture.platform.liveOpenCalls)
            assertEquals(2, fixture.session.logons.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.networkFailed, backend.session.value.message)
        }

    @Test
    fun aCredentialFailureOnLogonNeverRetriesOnLiveCandidates() =
        runTest {
            val fixture = Fixture()
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.login("account", opaqueValue(seed = 61))
            advanceUntilIdle()

            // A refused credential is not a route problem: it is reported once and never re-presented.
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(0, fixture.platform.liveOpenCalls)
            assertEquals(1, fixture.session.logons.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.authRejected, backend.session.value.message)
        }

    @Test
    fun aTransportFailureOnRestoreRetriesOnceWithLiveCandidates() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 47))
            fixture.storeCredential(credential)
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // Exactly one retry, and the retry is what asks the platform for a live endpoint set: the first
            // attempt's cached list is the input under suspicion, so it is never offered a second time. The
            // retry presents the same stored credential and its failure is the one the caller sees.
            assertEquals(1, fixture.platform.liveOpenCalls)
            assertEquals(2, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.restoreFailed, backend.session.value.message)
        }

    @Test
    fun aTransportFailureOnRestoreRetriesWhenTheSessionIsLeftDisconnected() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 67))
            fixture.storeCredential(credential)
            // The SDK leaves the session whose whole candidate pass failed in the disconnected state, not the
            // closed one. The attempt therefore has to end from the failure it already reported: reading that
            // state as "not over yet" is what left the shipped retry unreachable on the device.
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.DISCONNECTED
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            // Stopped before the recovery schedule's first retry rather than drained with advanceUntilIdle:
            // the schedule's retries would appear in the counts below as restores this test never asked for.
            // The backend is then closed, because a fixture that answers an exhausted event queue with
            // DISCONNECTED is one the backend is right to keep waiting on - so a recovery retry that reaches
            // that state would never end, and runTest waits for every coroutine the test started.
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(1, fixture.platform.liveOpenCalls)
            assertEquals(2, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.restoreFailed, backend.session.value.message)

            backend.close()
        }

    @Test
    fun aSignedOutAccountIsNotSignedBackInByTheRecoveryItHadScheduled() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 269)),
            )
            // The store refuses this sign-out, so the credential stays behind. That is the case that matters:
            // with nothing stored, a recovery retry left running can only publish SIGNED_OUT again, but with
            // the credential still there it reads it and signs the user back in after they signed out.
            fixture.store.clearFailure = IllegalStateException("the store refused the clear")
            repeat(2) {
                fixture.session.events +=
                    SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            }
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            runCurrent()
            val opensAfterTheFailedRestore = fixture.platform.openCalls
            assertTrue(
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .any { it.startsWith("session_recovery_scheduled") },
                "the sole backend owner must keep a bounded recovery plan after transient failure",
            )

            backend.logout()
            runCurrent()
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertEquals(FakeMessages.signOutIncomplete, backend.session.value.message)

            // Well past the first retry's five seconds, and past the whole schedule: the sign-out the user
            // asked for stands, and the retry the app had queued does not undo it.
            advanceTimeBy(600_000L)
            runCurrent()
            assertEquals(opensAfterTheFailedRestore, fixture.platform.openCalls)
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)

            backend.close()
        }

    @Test
    fun logoutWinsWhenAnInFlightRestoreFailsAndCredentialClearAlsoFails() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 273)),
            )
            fixture.store.clearFailure = IllegalStateException("the store refused the clear")
            repeat(2) {
                fixture.session.events +=
                    SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            }
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            lateinit var backend: SteamContentSdkSessionBackend
            var raced = false
            backend =
                fixture.backend(this) {
                    if (!raced) {
                        raced = true
                        backend.logout()
                    }
                }

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            runCurrent()

            val opensAfterLogout = fixture.platform.openCalls
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertEquals(FakeMessages.signOutIncomplete, backend.session.value.message)
            assertTrue(backend.session.value.hasStoredSession)

            advanceTimeBy(600_000L)
            runCurrent()
            assertEquals(opensAfterLogout, fixture.platform.openCalls)
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            backend.close()
        }

    @Test
    fun anExpiredSessionClearsTheStoredCredential() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 31)))
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.SESSION_EXPIRED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.EXPIRED, backend.session.value.phase)
            assertEquals(1, fixture.store.clearCalls)
            // A credential rejection is not a route problem: it never earns the live-candidate retry.
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(0, fixture.platform.liveOpenCalls)
        }

    @Test
    fun theStoredTokenIsReadBackAndComparedWithTheOneTheSdkRetained() =
        runTest {
            val fixture = Fixture()
            val presented = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 109))
            fixture.storeCredential(presented)
            fixture.session.retainedToken = opaqueValue(seed = 113).toByteArray(Charsets.UTF_8)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // A store that saves one token and loads another is invisible in the phase, and the failure it
            // causes lands on the next start. The read-back reports that outcome, never either value.
            val roundTrip =
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .single { it.startsWith("credential_roundtrip") }
            assertEquals("credential_roundtrip match=true unchanged=false", roundTrip)
            assertFalse(roundTrip.contains(presented.refreshToken))
        }

    @Test
    fun aClassifiedFailureRecordsTheDiagnosticTheSdkReported() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 127)))
            fixture.session.lastFailure =
                "authentication failed during AccessTokenLogon cause=RemoteResult (result=Some(5))"
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // The class alone cannot say whether a stored token was refused or the attempt never reached the
            // service; the SDK's own line names the step and the remote result code, and it has to survive the
            // one hop from the platform hook into the diagnostic record unchanged.
            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            val details = fixture.diagnostics.events.mapNotNull { it.attributes["sdk_detail"] }.single()
            assertEquals(fixture.session.lastFailure, details)
            assertTrue(stages.contains("attempt failure class=2 stage=restore"))
            // The class is the credential one, and this is a restore, so the stored credential is refused
            // rather than merely un-attempted: it is cleared and reported as expired.
            assertEquals(SteamSessionPhase.EXPIRED, backend.session.value.phase)
        }

    @Test
    fun logoutCallsTheSdkAndClearsTheStoredSession() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 37)))
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            backend.restorePersistedSession()
            advanceUntilIdle()

            backend.logout()
            advanceUntilIdle()

            assertEquals(1, fixture.session.logoutCalls)
            assertEquals(1, fixture.session.closeCalls)
            assertEquals(1, fixture.store.clearCalls)
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
        }

    @Test
    fun onDemandRecoveryRequestedDuringLogoutDoesNotRestoreAfterClearFailure() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 301)))
            fixture.store.clearFailure = IllegalStateException("commit failed")
            val backend = fixture.backend(this)

            backend.logout()
            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(0, fixture.platform.openCalls)
            assertEquals(0, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun explicitLoginRequestedDuringLogoutStillRunsAfterTeardown() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "old", refreshToken = opaqueValue(seed = 303)))
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.logout()
            backend.login("new", opaqueValue(seed = 305))
            advanceUntilIdle()

            assertEquals(1, fixture.session.logons.size)
            assertEquals("new", fixture.session.logons.single().accountName)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun closeReleasesTheSdkSession() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 41)))
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            backend.restorePersistedSession()
            advanceUntilIdle()

            backend.close()

            assertEquals(1, fixture.session.closeCalls)
        }

    @Test
    fun noCredentialReachesTheDiagnostics() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 43))
            fixture.storeCredential(credential)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            val recorded =
                fixture.diagnostics.events.joinToString(separator = "\n") { event ->
                    event.message + " " + event.attributes.values.joinToString(separator = ",")
                }
            assertTrue(fixture.diagnostics.events.isNotEmpty())
            assertFalse(recorded.contains(credential.refreshToken))
        }

    @Test
    fun theHostDrivesMaintenanceAndHandsARefusedRebuildToTheAttemptPath() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 11))
            fixture.storeCredential(credential)
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
            val backend = fixture.backend(this, maintenanceIntervalMs = 1_000L)

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)

            // One tick drains the socket and sends the heartbeat that is due. The SDK runs no loop of its own,
            // so nothing else would ever do either, and an undrained socket is one the peer closes.
            advanceTimeBy(1_001L)
            assertTrue(fixture.session.pumps.isNotEmpty())
            assertTrue(fixture.session.maintenanceSteps.isNotEmpty())
            assertTrue(fixture.session.maintenanceAutoReconnect.none { it })
            assertEquals(
                listOf("maintain", "pump"),
                fixture.session.maintenanceActions.take(2),
                "a due heartbeat must be written before waiting for an inbound frame at the peer deadline",
            )
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)

            fixture.session.maintenanceStep = SteamContentSdkMaintenanceStep.IDLE
            val pumpsBeforeIdleTick = fixture.session.pumps.size
            advanceTimeBy(1_001L)
            assertEquals(
                pumpsBeforeIdleTick,
                fixture.session.pumps.size,
                "an idle maintenance decision must not repeatedly read and disturb a quiet CM socket",
            )

            // The connection is gone and an in-place rebuild is refused. The loss is reported and recovery is
            // handed to the attempt path, which re-opens the session rather than leaving a signed-in claim
            // over a dead socket. CLOSED is the state a re-opened session that cannot authenticate reports, so
            // the handed-off attempt ends by itself and this test does not need to cancel it.
            fixture.diagnostics.events.clear()
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.CLOSED
            fixture.session.reconnectFailure = SteamContentSdkSessionFailure.PROTOCOL_ADAPTER
            advanceTimeBy(997L)
            runCurrent()

            val recoveryPhases =
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .filter { it.startsWith("phase_") }
            assertTrue(recoveryPhases.contains("phase_restorable"), recoveryPhases.toString())
            assertFalse(recoveryPhases.contains("phase_signed_in"), recoveryPhases.toString())
            assertEquals(0, fixture.session.reconnects)
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
            assertTrue(backend.session.value.hasStoredSession)
            assertFalse(backend.session.value.isConnected)

            // The owner, not a foreground request, starts the next bounded restore attempt.
            val opensBeforeRecovery = fixture.platform.openCalls
            advanceTimeBy(999L)
            runCurrent()
            assertEquals(opensBeforeRecovery, fixture.platform.openCalls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(opensBeforeRecovery + 1, fixture.platform.openCalls)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            backend.close()
        }

    @Test
    fun aStableSessionLossStillSchedulesItsFirstRecoveryAfterOneSecond() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 271)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
            var nowNanos = 1_000_000_000L
            val backend = fixture.backend(this, maintenanceIntervalMs = 1_000L, nowNanos = { nowNanos })

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            nowNanos += 61_000_000_000L
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.CLOSED
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
            assertTrue(
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .contains("session_recovery_scheduled delayMs=1000 retryDebt=1"),
            )
            assertEquals(1, fixture.platform.openCalls)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2, fixture.platform.openCalls)
            backend.close()
        }

    @Test
    fun aFailedStateReadAfterReconnectCannotLeaveTheBackendSignedIn() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 13)),
            )
            fixture.session.events +=
                SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
            val backend = fixture.backend(this, maintenanceIntervalMs = 1_000L)

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)

            fixture.session.stateAfterEvents = SteamContentSdkSessionState.CLOSED
            fixture.session.failStateAfterReconnect = true
            advanceTimeBy(1_001L)
            runCurrent()

            assertEquals(0, fixture.session.reconnects)
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
            assertFalse(backend.session.value.isConnected)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(
                2,
                fixture.platform.openCalls,
                "the backend-owned recovery plan must reopen exactly one replacement",
            )
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            backend.close()
        }

    @Test
    fun closingTheBackendStopsTheMaintenanceLoop() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 233)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
            val backend = fixture.backend(this, maintenanceIntervalMs = 1_000L)

            backend.restorePersistedSession()
            advanceTimeBy(1_001L)
            assertTrue(fixture.session.maintenanceSteps.isNotEmpty())

            backend.close()
            val stepsWhenClosed = fixture.session.maintenanceSteps.size
            advanceTimeBy(5_000L)

            // The loop drives the session this closed, so it has to stop with it rather than tick against a
            // closed session until the next interval notices.
            assertEquals(stepsWhenClosed, fixture.session.maintenanceSteps.size)
        }

    @Test
    fun transientRestoreRoundsKeepOneBoundedRecoveryPlanAfterRepeatedFailure() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 251)),
            )
            repeat(24) {
                fixture.session.events +=
                    SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            }
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(1, fixture.platform.liveOpenCalls)
            assertTrue(
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .contains("session_recovery_scheduled delayMs=1000 retryDebt=1"),
            )

            // Foreground/business/registry signals join the not-before plan; none may spend the retry early.
            repeat(3) { backend.restorePersistedSession() }
            runCurrent()
            assertEquals(1, fixture.platform.openCalls)
            assertTrue(
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .contains("recovery_backoff_joined"),
            )

            // The first delayed round still gets exactly one plain and one live-candidate attempt.
            advanceTimeBy(998L)
            runCurrent()
            assertEquals(1, fixture.platform.openCalls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, fixture.platform.openCalls)
            assertEquals(2, fixture.platform.liveOpenCalls)
            assertTrue(
                fixture.diagnostics.events
                    .mapNotNull { it.attributes["stage"] }
                    .contains("session_recovery_scheduled delayMs=2000 retryDebt=2"),
            )

            // A second failed round leaves a third plan; it does not wait for another business request.
            advanceTimeBy(1_999L)
            runCurrent()
            assertEquals(2, fixture.platform.openCalls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(3, fixture.platform.openCalls)
            assertEquals(3, fixture.platform.liveOpenCalls)
            backend.close()
        }

    @Test
    fun theLiveCandidateRetryAnnouncesItselfInsteadOfLeavingFailedStanding() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 257)),
            )
            repeat(4) {
                fixture.session.events +=
                    SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.NETWORK)
            }
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            // Stops before the recovery schedule's first retry, so what is left is the two attempts of one
            // restore and nothing else.
            advanceTimeBy(1L)
            runCurrent()

            // The device reading this answers: the first restore published FAILED and then kept a live-candidate
            // retry connecting for six more seconds. Every reader of the phase - the screens, and the download
            // path deciding whether to open a session of its own - was told nobody was signing in while somebody
            // was; a download that acted on that opened its own route over the retry that was working, the retry
            // then failed, and the app sat signed out for 7m36s.
            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            val retryAt = stages.indexOf("restore_retry_live_candidates")
            assertTrue(retryAt >= 0, "the live-candidate retry was never announced: $stages")
            assertEquals(
                "phase_signing_in",
                stages.drop(retryAt).firstOrNull { it.startsWith("phase_") },
                "the retry left FAILED standing while it was still connecting: $stages",
            )
        }

    @Test
    fun theAttemptGateIsTakenWithTheRequestRatherThanWithThePhase() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 263)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            assertFalse(backend.isAttemptInFlight(), "an idle backend reported an attempt")

            backend.restorePersistedSession()
            // The gate is taken synchronously with the request; the first phase is not published until the
            // coroutine runs. That gap is the one the download path has to respect, and it is why the gate is
            // what it asks instead of the phase.
            assertTrue(backend.isAttemptInFlight(), "the gate was not held by the attempt it had just started")
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)

            advanceUntilIdle()
            assertFalse(backend.isAttemptInFlight(), "the gate stayed held after the attempt finished")
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun aGuardAnswerInFlightIsCancelledByANewerSignIn() =
        runTest {
            val fixture = Fixture()
            fixture.session.pendingChallengeId = opaqueValue(seed = 191).toByteArray(Charsets.UTF_8)
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.EMAIL)
            val backend = fixture.backend(this)
            backend.login("account", opaqueValue(seed = 193))
            advanceUntilIdle()
            assertEquals(SteamSessionPhase.WAITING_FOR_CODE, backend.session.value.phase)

            // The answer is now a tracked attempt, so the sign-in the user asked for next cancels it instead of
            // racing it. A coroutine of its own - the shape this used to have - could not be cancelled by
            // anything, which is how an answer kept reaching the SDK after its session was gone.
            backend.submitSteamGuardCode(opaqueValue(seed = 197))
            backend.login("account", opaqueValue(seed = 199))
            advanceUntilIdle()

            assertEquals(0, fixture.session.answers.size)
            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            assertTrue(stages.contains("attempt_superseded"))
        }

    @Test
    fun aSignOutAfterAGuardAnswerStillEndsSignedOut() =
        runTest {
            val fixture = Fixture()
            fixture.session.pendingChallengeId = opaqueValue(seed = 211).toByteArray(Charsets.UTF_8)
            fixture.session.events +=
                SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.EMAIL)
            val backend = fixture.backend(this)
            backend.login("account", opaqueValue(seed = 223))
            advanceUntilIdle()

            // Which of the two is dispatched first is the dispatcher's business; what has to hold is that the
            // sign-out is what stands and the stored sign-in is gone.
            backend.submitSteamGuardCode(opaqueValue(seed = 227))
            backend.logout()
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertEquals(1, fixture.store.clearCalls)
        }

    @Test
    fun anUnrevokedServiceLogoutKeepsReportingTheStoredCredential() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 199)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.LoggedOut(revoked = false)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // The service ended the session without revoking the credential: the phase says the session is over
            // and the flag says what is stored, which is what the next launch will act on.
            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertTrue(backend.session.value.hasStoredSession)
            assertEquals(0, fixture.store.clearCalls)
        }

    @Test
    fun aRefusedStoredCredentialIsClearedAndReportedExpired() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 149)),
            )
            // The service itself answered about the token, which is the only thing that makes it dead.
            fixture.session.lastFailure =
                "authentication failed during AccessTokenLogon cause=RemoteResult (result=Some(15))"
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // The service refused this token, so replaying it on every start and every library refresh is what
            // the app used to do forever. It is cleared once and reported as expired.
            assertEquals(SteamSessionPhase.EXPIRED, backend.session.value.phase)
            assertEquals(FakeMessages.credentialsExpired, backend.session.value.message)
            assertEquals(1, fixture.store.clearCalls)
            assertFalse(backend.session.value.hasStoredSession)
        }

    @Test
    fun aRouteFailureDoesNotDeleteTheStoredCredential() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 239)),
            )
            // The device reading this pins: a route failure wears the authentication class but says nothing
            // about the credential - `result=None`, because no service ever saw it. Clearing on the class
            // alone deleted a working account over a route hiccup.
            fixture.session.lastFailure =
                "authentication failed during AccessTokenLogon cause=CmRoute (result=None)"
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(0, fixture.store.clearCalls)
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun aCredentialFailureWithNoDetailAtAllKeepsTheStoredCredential() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 241)),
            )
            fixture.session.lastFailure = null
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            backend.restorePersistedSession()
            advanceUntilIdle()

            // No evidence that anything judged the credential: keeping it costs one refused restore later,
            // and deleting it costs the user a full sign-in.
            assertEquals(0, fixture.store.clearCalls)
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun aRefusedLogonLeavesTheStoredCredentialAlone() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "stored", refreshToken = opaqueValue(seed = 151)),
            )
            fixture.session.events +=
                SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
            val backend = fixture.backend(this)

            // A refused logon is a wrong password for the account being typed, not a verdict on what is stored:
            // clearing there would delete a working account because someone mistyped another one.
            backend.login("other", opaqueValue(seed = 157))
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(0, fixture.store.clearCalls)
        }

    @Test
    fun aSignOutThatCouldNotClearTheStoreDoesNotClaimItDid() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 163)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            fixture.store.clearFailure = IllegalStateException("commit failed")
            val backend = fixture.backend(this)
            backend.restorePersistedSession()
            advanceUntilIdle()

            backend.logout()
            advanceUntilIdle()

            assertEquals(SteamSessionPhase.SIGNED_OUT, backend.session.value.phase)
            assertEquals(FakeMessages.signOutIncomplete, backend.session.value.message)
            // The credential really is still there, and the state now says so: without this the next launch
            // would sign the user back in after a sign-out that reported success.
            assertTrue(backend.session.value.hasStoredSession)
        }

    @Test
    fun aLostConnectionWithAStoredCredentialIsRestorableRatherThanSignedOut() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 167)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)
            backend.restorePersistedSession()
            advanceUntilIdle()
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            val opensAfterSignIn = fixture.platform.openCalls

            // The peer side of the connection is gone: the next foreground finds the live session closed.
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.CLOSED
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            backend.onAppForegrounded()
            advanceUntilIdle()

            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            // Not a sign-out: the credential is still stored, the session is restored again, and that restore is
            // also what reopens the SDK engine every Workshop read in the app goes through.
            assertTrue(stages.contains("phase_restorable"))
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertTrue(fixture.platform.openCalls > opensAfterSignIn)
        }

    @Test
    fun aSignInRequestedWhileAnAttemptRunsReplacesItInsteadOfBeingDropped() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 173)),
            )
            fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
            val backend = fixture.backend(this)

            // The restore takes the gate before it runs a single step, so the sign-in below really does arrive
            // while an attempt is in progress - the case that used to vanish without a trace.
            backend.restorePersistedSession()
            backend.login("account", opaqueValue(seed = 179))
            advanceUntilIdle()

            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            assertTrue(stages.contains("attempt_superseded"))
            assertEquals(0, fixture.session.restores.size)
            assertEquals(1, fixture.session.logons.size)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun concurrentRecoveryTriggersCoalesceOntoTheRunningAttempt() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 181)),
            )
            val backend = fixture.backend(this)

            // Foreground, business and timer signals all enter the same recovery gate.
            backend.restorePersistedSession()
            backend.restorePersistedSession()
            backend.restorePersistedSession()
            advanceUntilIdle()

            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            assertTrue(stages.contains("recovery_coalesced"))
            assertEquals(1, fixture.platform.openCalls)
        }

    @Test
    fun `rate limit blocks all new authentication after foreground and restart without deleting token`() =
        runTest {
            val fixture = Fixture()
            val credential = PersistedSteamCredential("account", opaqueValue(seed = 13))
            fixture.storeCredential(credential)
            fixture.session.events += SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.RATE_LIMITED)
            val backend = fixture.backend(this)
            backend.restorePersistedSession()
            runCurrent()
            backend.onAppForegrounded()
            backend.restorePersistedSession()
            backend.login("account", opaqueValue(seed = 15))
            runCurrent()
            backend.close()
            val restarted = fixture.backend(this)
            restarted.restorePersistedSession()
            runCurrent()
            assertEquals(SteamSessionPhase.RESTORABLE, restarted.session.value.phase)
            assertTrue(restarted.session.value.hasStoredSession)
            restarted.login("account", opaqueValue(seed = 17))
            runCurrent()
            assertEquals(1, fixture.platform.openCalls)
            assertEquals(0, fixture.platform.liveOpenCalls)
            assertEquals(0, fixture.store.clearCalls)
            assertEquals(credential, fixture.credentialStore.load())
            restarted.close()
        }

    @Test
    fun `remote result 84 holds admission until local cooldown expires`() = runTest {
        val fixture = Fixture()
        fixture.storeCredential(PersistedSteamCredential("account", opaqueValue(seed = 19)))
        fixture.session.lastFailure = "authentication failed cause=RemoteResult (result=Some(84))"
        fixture.session.events += SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.AUTHENTICATION_FAILED)
        val backend = fixture.backend(this)
        backend.restorePersistedSession()
        runCurrent()
        fixture.nowMillis += ACCOUNT_RATE_LIMIT_COOLDOWN_MS - 1
        advanceTimeBy(ACCOUNT_RATE_LIMIT_COOLDOWN_MS - 1)
        runCurrent()
        assertEquals(1, fixture.platform.openCalls)
        fixture.nowMillis++
        fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fixture.platform.openCalls)
        assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        backend.close()
    }

    @Test
    fun `failed durable cooldown clear blocks restore until storage recovers`() = runTest {
        val fixture = Fixture()
        fixture.storeCredential(PersistedSteamCredential("account", opaqueValue(seed = 23)))
        fixture.admissionStore.limited = true
        fixture.admissionStore.failWrites = true
        val backend = fixture.backend(this)
        backend.restorePersistedSession()
        runCurrent()
        fixture.nowMillis += ACCOUNT_RATE_LIMIT_COOLDOWN_MS
        advanceTimeBy(ACCOUNT_RATE_LIMIT_COOLDOWN_MS)
        runCurrent()
        assertEquals(0, fixture.platform.openCalls)
        fixture.admissionStore.failWrites = false
        fixture.session.events += SteamContentSdkSessionEvent.StateChanged(SteamContentSdkSessionState.AUTHENTICATED)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, fixture.platform.openCalls)
        backend.close()
    }

    @Test
    fun `fresh login reports admission storage failure without opening a session`() = runTest {
        val fixture = Fixture()
        fixture.admissionStore.failReads = true
        val backend = fixture.backend(this)
        backend.login("account", opaqueValue(seed = 25))
        runCurrent()
        assertEquals(0, fixture.platform.openCalls)
        assertEquals(FakeMessages.sessionStorageUnavailable, backend.session.value.message)
        backend.close()
    }

    @Test
    fun `failed rate limit marker write is retried before cooldown recovery`() = runTest {
        val fixture = Fixture()
        fixture.storeCredential(PersistedSteamCredential("account", opaqueValue(seed = 27)))
        fixture.admissionStore.failWrites = true
        fixture.session.events += SteamContentSdkSessionEvent.CommandFailed(SteamContentSdkSessionFailure.RATE_LIMITED)
        val backend = fixture.backend(this)
        backend.restorePersistedSession()
        runCurrent()
        assertEquals(1, fixture.platform.openCalls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(FakeMessages.sessionStorageUnavailable, backend.session.value.message)
        assertEquals(1, fixture.platform.openCalls)
        fixture.admissionStore.failWrites = false
        fixture.nowMillis += 120_000
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(fixture.admissionStore.limited)
        assertEquals(1, fixture.platform.openCalls)
        backend.close()
    }

    @Test
    fun `foreground and recovery leave an active Guard challenge intact`() = runTest {
        val fixture = Fixture()
        fixture.session.pendingChallengeId = byteArrayOf(1)
        fixture.session.events += SteamContentSdkSessionEvent.AuthenticationChallenge(SteamContentSdkChallengeKind.EMAIL)
        val backend = fixture.backend(this)
        backend.login("account", opaqueValue(seed = 21))
        runCurrent()
        backend.onAppForegrounded()
        backend.restorePersistedSession()
        runCurrent()
        assertEquals(1, fixture.platform.openCalls)
        assertEquals(SteamSessionPhase.WAITING_FOR_CODE, backend.session.value.phase)
        backend.submitSteamGuardCode("12345")
        runCurrent()
        assertEquals(1, fixture.session.answers.size)
        backend.close()
    }

    private class Fixture {
        val session = FakeSdkSession()
        val platform = FakeSdkPlatform(session)
        val store = FakeEncryptedStringStore()
        val credentialStore = EncryptedSteamCredentialStore(store)
        val diagnostics = RecordingDiagnostics()
        var nowMillis = 1_000_000L
        val admissionStore = object : SteamAccountAdmissionStore {
            var limited = false
            var failWrites = false
            var failReads = false
            override fun readLimited(): Boolean {
                check(!failReads)
                return limited
            }
            override fun writeLimited(limited: Boolean) {
                check(!failWrites)
                this.limited = limited
            }
        }

        fun backend(
            scope: CoroutineScope,
            maintenanceIntervalMs: Long = 0L,
            nowNanos: () -> Long = System::nanoTime,
            beforeRecoveryPlanPublish: () -> Unit = {},
        ): SteamContentSdkSessionBackend =
            SteamContentSdkSessionBackend(
                platform = platform,
                credentialStore = credentialStore,
                messages = FakeMessages,
                diagnostics = diagnostics,
                scope = scope,
                admissionStore = admissionStore,
                cooldownNowMillis = { nowMillis },
                // Zero by default: a maintenance loop is never idle, so the tests about logon and restore
                // leave the schedule to the one test that is about it.
                maintenanceIntervalMs = maintenanceIntervalMs,
                nowNanos = nowNanos,
                beforeRecoveryPlanPublish = beforeRecoveryPlanPublish,
            )

        fun storeCredential(credential: PersistedSteamCredential) {
            store.payload = EncryptedStringReadResult.Value(encodeSteamCredential(credential))
        }
    }

    private class FakeEncryptedStringStore : EncryptedStringStore {
        var payload: EncryptedStringReadResult = EncryptedStringReadResult.Missing
        var clearCalls = 0
            private set
        var writeCalls = 0
            private set

        /** Stands for a preferences commit that failed: the record is still there afterwards. */
        var clearFailure: Throwable? = null
        /** Lets a fake durable store acknowledge a write but expose a different read-back. */
        var writeReplacement: String? = null

        override fun read(): EncryptedStringReadResult = payload

        override fun write(value: String) {
            writeCalls += 1
            payload = EncryptedStringReadResult.Value(writeReplacement ?: value)
        }

        override fun clear() {
            clearFailure?.let { throw it }
            clearCalls += 1
            payload = EncryptedStringReadResult.Missing
        }
    }

    private class FakeSdkPlatform(
        private val session: FakeSdkSession,
    ) : SteamContentSdkPlatform {
        var openCalls = 0
        var liveOpenCalls = 0
        var openFailure: Throwable? = null

        override suspend fun open(): SteamContentSdkPlatformSession? {
            openCalls += 1
            return openSession()
        }

        override suspend fun openWithLiveCandidates(): SteamContentSdkPlatformSession? {
            liveOpenCalls += 1
            return openSession()
        }

        /**
         * The two entry points count separately on purpose: a live-candidate open is the retry's own route, so
         * an `openCalls` that also counted it could not tell "one attempt, one retry" from "two plain attempts".
         */
        private fun openSession(): SteamContentSdkPlatformSession? {
            openFailure?.let { throw it }
            return session
        }
    }

    private class FakeSdkSession : SteamContentSdkPlatformSession {
        val events = ArrayDeque<SteamContentSdkSessionEvent>()
        override fun authorizationGeneration(): Long = 1L
        val logons = mutableListOf<LogonCall>()
        val restores = mutableListOf<RestoreCall>()
        val answers = mutableListOf<ChallengeAnswerCall>()
        var pendingChallengeId: ByteArray? = null

        /**
         * What the SDK retains after the attempt, as [SteamContentSdkPlatformSession.refreshToken] returns it.
         *
         * The real session holds the token from its own last authentication step, so a test that wants the
         * authenticated edge to observe one sets this before the backend runs.
         */
        var retainedToken: ByteArray? = null
        var logoutCalls = 0
            private set
        var closeCalls = 0
            private set
        var logonSupported = true
        var restoreSupported = true
        var stateAfterEvents: SteamContentSdkSessionState = SteamContentSdkSessionState.CLOSED
        var failStateAfterReconnect = false
        private var stateFailurePending = false

        override fun supportsAccountLogon(): Boolean = logonSupported

        override fun supportsRestore(): Boolean = restoreSupported

        override fun state(): SteamContentSdkSessionState {
            if (stateFailurePending) {
                stateFailurePending = false
                error("the SDK could not report reconnect state")
            }
            return stateAfterEvents
        }

        var beforePoll: ((Int) -> Unit)? = null
        private var pollCalls = 0

        override fun pollEvent(timeoutMs: Int): SteamContentSdkSessionEvent? {
            pollCalls += 1
            beforePoll?.invoke(pollCalls)
            return events.removeFirstOrNull()
        }

        /** Every maintenance tick the host asked for, so a test can assert the schedule is really driven. */
        val maintenanceSteps = mutableListOf<SteamContentSdkMaintenanceStep>()
        val maintenanceAutoReconnect = mutableListOf<Boolean>()
        val pumps = mutableListOf<SteamContentSdkPumpOutcome>()
        val maintenanceActions = mutableListOf<String>()
        var reconnects = 0
            private set

        /** Typed failure returned by reconnect; NONE stands for a usable replacement connection. */
        var reconnectFailure = SteamContentSdkSessionFailure.NONE
        var maintenanceStep = SteamContentSdkMaintenanceStep.HEARTBEAT_SENT

        override fun maintain(autoReconnect: Boolean): SteamContentSdkMaintenance =
            SteamContentSdkMaintenance(maintenanceStep).also {
                maintenanceActions += "maintain"
                maintenanceAutoReconnect += autoReconnect
                maintenanceSteps += it.step
            }

        override fun pump(): SteamContentSdkPump =
            SteamContentSdkPump(SteamContentSdkPumpOutcome.IDLE).also {
                maintenanceActions += "pump"
                pumps += it.outcome
            }

        override fun reconnect(): SteamContentSdkReconnect {
            reconnects += 1
            if (reconnectFailure == SteamContentSdkSessionFailure.NONE) {
                stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
                stateFailurePending = failStateAfterReconnect
                failStateAfterReconnect = false
            }
            return SteamContentSdkReconnect(failure = reconnectFailure)
        }

        /** The profile the engine reports for the signed-in account; null stands for one it cannot answer. */
        var accountProfile: SteamContentSdkAccountProfile? = null

        override fun accountProfile(): SteamContentSdkAccountProfile? = accountProfile

        /** What the service said this account may confirm with, and what the host last selected. */
        var guardChoices: Long = 0L
        val chosenGuards = mutableListOf<Int>()

        override fun guardChoices(): Long = guardChoices

        override fun chooseGuard(choice: Int) {
            chosenGuards += choice
        }

        override fun challengeId(): ByteArray? = pendingChallengeId

        override fun refreshToken(): ByteArray? = retainedToken?.copyOf()

        /**
         * What [SteamContentSdkPlatformSession.lastError] returns for the attempt.
         *
         * The real session holds the SDK's own redacted diagnostic of its last failure, so a test that wants a
         * failure reading to name a cause sets this before the backend runs.
         */
        var lastFailure: String? = null

        override fun lastError(): String? = lastFailure

        override fun beginAccountLogon(
            accountName: String,
            password: ByteArray,
        ) {
            logons += LogonCall(accountName, password.copyOf())
        }

        override fun restore(
            accountName: String,
            refreshToken: ByteArray,
        ) {
            restores += RestoreCall(accountName, refreshToken.copyOf())
        }

        override fun respondToChallenge(
            challengeId: ByteArray,
            answer: ByteArray,
        ) {
            answers += ChallengeAnswerCall(challengeId.copyOf(), answer.copyOf())
        }

        override fun logout() {
            logoutCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class LogonCall(
        val accountName: String,
        val secret: ByteArray,
    )

    private class RestoreCall(
        val accountName: String,
        val refreshToken: ByteArray,
    )

    private class ChallengeAnswerCall(
        val challengeId: ByteArray,
        val answer: ByteArray,
    )

    private class RecordingDiagnostics : DiagnosticRepository {
        val events = mutableListOf<DiagnosticEvent>()

        override suspend fun record(event: DiagnosticEvent) {
            events += event
        }

        override suspend fun readRecent(limit: Int): List<DiagnosticEvent> = events.takeLast(limit)

        override suspend fun exportRedactedText(): String = events.joinToString(separator = "\n") { it.message }

        override suspend fun clear() {
            events.clear()
        }
    }

    private object FakeMessages : SteamContentSdkSessionMessages {
        override val restoringSession = "restoring-session"
        override val connecting = "connecting"
        override val requestingLogin = "requesting-login"
        override val enterCredentials = "enter-credentials"
        override val checkingLoginMethod = "checking-login-method"
        override val enterEmailCode = "enter-email-code"
        override val enterMobileCode = "enter-mobile-code"
        override val confirmMobile = "confirm-mobile"
        override val codeSubmitted = "code-submitted"
        override val signedIn = "signed-in"
        override val signedOut = "signed-out"
        override val signOutIncomplete = "sign-out-incomplete"
        override val credentialsExpired = "credentials-expired"
        override val sessionStorageUnavailable = "session-storage-unavailable"
        override val restoreFailed = "restore-failed"
        override val loginTimeout = "login-timeout"
        override val unavailable = "unavailable"
        override val authRejected = "auth-rejected"
        override val networkFailed = "network-failed"
        override val challengeUnsupported = "challenge-unsupported"
        override val noGuardRequest = "no-guard-request"
        override val noManualLogin = "no-manual-login"
    }

    private companion object {
        /** Builds an opaque value at run time, so no token or password fixture is embedded in this file. */
        fun opaqueValue(seed: Int): String =
            List(24) { index -> ('a' + (seed + index) % 26) }.joinToString(separator = "")
    }
}
