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

    private class Fixture {
        val session = FakeSdkSession()
        val platform = FakeSdkPlatform(session)
        val store = FakeEncryptedStringStore()
        val credentialStore = EncryptedSteamCredentialStore(store)
        val diagnostics = RecordingDiagnostics()

        fun backend(scope: CoroutineScope): SteamContentSdkSessionBackend =
            SteamContentSdkSessionBackend(
                platform = platform,
                credentialStore = credentialStore,
                messages = FakeMessages,
                diagnostics = diagnostics,
                scope = scope,
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

        override fun read(): EncryptedStringReadResult = payload

        override fun write(value: String) {
            writeCalls += 1
            payload = EncryptedStringReadResult.Value(value)
        }

        override fun clear() {
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
            openFailure?.let { throw it }
            return session
        }

        override suspend fun openWithLiveCandidates(): SteamContentSdkPlatformSession? {
            liveOpenCalls += 1
            return open()
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

        override fun challengeId(): ByteArray? = pendingChallengeId

        override fun refreshToken(): ByteArray? = retainedToken?.copyOf()

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
