package com.wallhub.android.data.steam

import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
            assertEquals(0, fixture.session.closeCalls)
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
            advanceUntilIdle()

            assertEquals(1, fixture.platform.liveOpenCalls)
            assertEquals(2, fixture.session.restores.size)
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
            assertEquals(FakeMessages.restoreFailed, backend.session.value.message)
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
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)

            // The connection is gone and an in-place rebuild is refused. The loss is reported and recovery is
            // handed to the attempt path, which re-opens the session rather than leaving a signed-in claim
            // over a dead socket. CLOSED is the state a re-opened session that cannot authenticate reports, so
            // the handed-off attempt ends by itself and this test does not need to cancel it.
            fixture.session.stateAfterEvents = SteamContentSdkSessionState.CLOSED
            fixture.session.reconnectSucceeds = false
            advanceTimeBy(1_001L)

            assertEquals(1, fixture.session.reconnects)
            assertTrue(
                fixture.platform.openCalls >= 2,
                "a refused rebuild hands recovery to the attempt path, which re-opens the session",
            )
            assertEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
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
    fun aTransientRestoreFailureIsRetriedAndTheScheduleIsBounded() =
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
            // Explicit steps rather than advanceUntilIdle: the schedule is time-based, and a test that drains
            // everything hides whether a retry was scheduled at all.
            advanceTimeBy(1L)
            runCurrent()
            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            // The device reading this answers: a single restore attempt failed, and the app then sat in FAILED -
            // signed out, with downloads failing - until a human retried four minutes later. The stage carries
            // its parameters, so this matches the name rather than the whole line.
            assertTrue(
                stages.any { it.startsWith("session_recovery_scheduled") },
                "no retry was scheduled: $stages",
            )
            val openCallsAfterFirstAttempt = fixture.platform.openCalls

            // The first retry is five seconds out, and it does run.
            advanceTimeBy(5_001L)
            runCurrent()
            assertTrue(
                fixture.platform.openCalls > openCallsAfterFirstAttempt,
                "the scheduled recovery attempt did not run",
            )

            // The whole schedule is 5s + 15s + 30s; past it nothing more is asked for, so a network that is
            // really down is reported rather than hammered.
            advanceTimeBy(60_000L)
            runCurrent()
            val afterSchedule = fixture.platform.openCalls
            assertTrue(
                stages.any { it.startsWith("session_recovery_exhausted") } ||
                    fixture.diagnostics.events
                        .mapNotNull { it.attributes["stage"] }
                        .any { it.startsWith("session_recovery_exhausted") },
                "the schedule never ended",
            )
            advanceTimeBy(120_000L)
            runCurrent()
            assertEquals(afterSchedule, fixture.platform.openCalls)
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
    fun aRestoreRequestedWhileAnAttemptRunsIsQueuedRatherThanDropped() =
        runTest {
            val fixture = Fixture()
            fixture.storeCredential(
                PersistedSteamCredential(accountName = "account", refreshToken = opaqueValue(seed = 181)),
            )
            val backend = fixture.backend(this)

            // Neither attempt can authenticate: the fake reports the session closed with no events queued.
            backend.restorePersistedSession()
            backend.restorePersistedSession()
            advanceUntilIdle()

            val stages = fixture.diagnostics.events.mapNotNull { it.attributes["stage"] }
            assertTrue(stages.contains("attempt_queued"))
            // Both ran, which is the whole point: the second request waited for the gate instead of being lost.
            assertEquals(2, fixture.platform.openCalls)
        }

    private class Fixture {
        val session = FakeSdkSession()
        val platform = FakeSdkPlatform(session)
        val store = FakeEncryptedStringStore()
        val credentialStore = EncryptedSteamCredentialStore(store)
        val diagnostics = RecordingDiagnostics()

        fun backend(
            scope: CoroutineScope,
            maintenanceIntervalMs: Long = 0L,
        ): SteamContentSdkSessionBackend =
            SteamContentSdkSessionBackend(
                platform = platform,
                credentialStore = credentialStore,
                messages = FakeMessages,
                diagnostics = diagnostics,
                scope = scope,
                // Zero by default: a maintenance loop is never idle, so the tests about logon and restore
                // leave the schedule to the one test that is about it.
                maintenanceIntervalMs = maintenanceIntervalMs,
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

        override fun read(): EncryptedStringReadResult = payload

        override fun write(value: String) {
            writeCalls += 1
            payload = EncryptedStringReadResult.Value(value)
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

        override fun supportsAccountLogon(): Boolean = logonSupported

        override fun supportsRestore(): Boolean = restoreSupported

        override fun state(): SteamContentSdkSessionState = stateAfterEvents

        override fun pollEvent(timeoutMs: Int): SteamContentSdkSessionEvent? = events.removeFirstOrNull()

        /** Every maintenance tick the host asked for, so a test can assert the schedule is really driven. */
        val maintenanceSteps = mutableListOf<SteamContentSdkMaintenanceStep>()
        val pumps = mutableListOf<SteamContentSdkPumpOutcome>()
        var reconnects = 0
            private set

        /** Whether a rebuild succeeds; false stands for a connection that cannot be re-established. */
        var reconnectSucceeds = true

        override fun maintain(autoReconnect: Boolean): SteamContentSdkMaintenance =
            SteamContentSdkMaintenance(SteamContentSdkMaintenanceStep.HEARTBEAT_SENT).also {
                maintenanceSteps += it.step
            }

        override fun pump(): SteamContentSdkPump =
            SteamContentSdkPump(SteamContentSdkPumpOutcome.IDLE).also { pumps += it.outcome }

        override fun reconnect(): SteamContentSdkReconnect {
            reconnects += 1
            if (!reconnectSucceeds) error("the retained token was refused")
            stateAfterEvents = SteamContentSdkSessionState.AUTHENTICATED
            return SteamContentSdkReconnect()
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
        override val enterGuardCode = "enter-guard-code"
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
