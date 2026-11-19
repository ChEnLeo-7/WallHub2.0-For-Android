package com.wallhub.android.data.steam

import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SteamSessionReadinessGateTest {
    @Test
    fun `stored session opens once only after current network becomes ready`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 1L, ready = false)
            val platform = RecordingSessionPlatform()
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            backend.onAppForegrounded()
            runCurrent()
            assertEquals(0, platform.prepareCalls)
            assertEquals(0, platform.openCalls)

            readiness.emit(generation = 1L, ready = true)
            runCurrent()
            assertEquals(1, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)

            backend.restorePersistedSession()
            backend.onAppForegrounded()
            readiness.emit(generation = 1L, ready = true)
            runCurrent()
            assertEquals(1, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
        }

    @Test
    fun `restore request can consume a ready generation before collector dispatch`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 20L, ready = false)
            val platform = RecordingSessionPlatform()
            val backend = sessionBackend(platform, readiness)
            runCurrent()

            readiness.emit(generation = 21L, ready = true)
            backend.restorePersistedSession()
            runCurrent()

            assertEquals(1, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun `automatic repeated restore still deduplicates a ready generation`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 5L, ready = true)
            val platform = RecordingSessionPlatform().apply { preparedRoute = null }
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()
            val callsAfterFirstRestore = platform.prepareCalls
            backend.restorePersistedSession()
            runCurrent()

            assertEquals(callsAfterFirstRestore, platform.prepareCalls)
            assertEquals(1, callsAfterFirstRestore)
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
        }

    @Test
    fun `real background return probes once while duplicate foreground signal joins`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 3L, ready = true)
            val platform = RecordingSessionPlatform()
            val backend = sessionBackend(platform, readiness)
            backend.restorePersistedSession()
            runCurrent()

            backend.onAppBackgrounded()
            backend.onAppForegrounded()
            backend.onAppForegrounded()
            runCurrent()

            assertEquals(2, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun `failed Steam route probe never opens SDK and stays restorable`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 4L, ready = true)
            val platform = RecordingSessionPlatform().apply { preparedRoute = null }
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()

            assertEquals(1, platform.prepareCalls)
            assertEquals(0, platform.openCalls)
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
            assertEquals(true, backend.session.value.hasStoredSession)
        }

    @Test
    fun `explicit reconnect retries failed route on same ready generation after automatic retries end`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 1L, ready = true)
            val platform = RecordingSessionPlatform().apply { preparedRoute = null }
            val backend = sessionBackend(platform, readiness)
            backend.restorePersistedSession()
            runCurrent()
            // Let the bounded automatic retry plan finish without changing the network generation.
            advanceTimeBy(600_000L)
            runCurrent()
            val failedProbeCalls = platform.prepareCalls
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
            assertEquals(0, platform.openCalls)

            platform.preparedRoute = TestPreparedRoute
            backend.restorePersistedSession()
            runCurrent()
            assertEquals(failedProbeCalls, platform.prepareCalls)

            backend.reconnectPersistedSession()
            runCurrent()
            assertEquals(failedProbeCalls + 1, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun `explicit reconnect publishes busy state before route probe completes`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 3L, ready = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val platform =
                RecordingSessionPlatform().apply {
                    preparedRoute = null
                    prepareBehavior = {
                        if (prepareCalls == 1) null else {
                            entered.complete(Unit)
                            release.await()
                            TestPreparedRoute
                        }
                    }
                }
            val backend = sessionBackend(platform, readiness)
            backend.restorePersistedSession()
            runCurrent()
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)

            backend.reconnectPersistedSession()
            runCurrent()
            entered.await()
            assertEquals(SteamSessionPhase.SIGNING_IN, backend.session.value.phase)

            release.complete(Unit)
            runCurrent()
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun `duplicate explicit reconnect joins active route probe`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 2L, ready = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val platform =
                RecordingSessionPlatform().apply {
                    preparedRoute = null
                    prepareBehavior = {
                        if (prepareCalls == 1) null else {
                            entered.complete(Unit)
                            release.await()
                            TestPreparedRoute
                        }
                    }
                }
            val backend = sessionBackend(platform, readiness)
            backend.restorePersistedSession()
            runCurrent()
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)

            backend.reconnectPersistedSession()
            runCurrent()
            entered.await()
            backend.reconnectPersistedSession()
            runCurrent()
            assertEquals(2, platform.prepareCalls)
            release.complete(Unit)
            runCurrent()

            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    @Test
    fun `prepared route open null stays restorable`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 6L, ready = true)
            val platform = RecordingSessionPlatform().apply { preparedOpenAvailable = false }
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()

            assertEquals(1, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(0, platform.restoreCalls)
            assertEquals(SteamSessionPhase.RESTORABLE, backend.session.value.phase)
        }

    @Test
    fun `user login cancels route probe and cancelled restore cannot publish failure`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 8L, ready = true)
            val probeEntered = CompletableDeferred<Unit>()
            val probeCancelled = CompletableDeferred<Unit>()
            val platform =
                RecordingSessionPlatform().apply {
                    prepareBehavior = {
                        probeEntered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            probeCancelled.complete(Unit)
                        }
                    }
                }
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()
            probeEntered.await()

            backend.login("new-account", "password")
            runCurrent()

            probeCancelled.await()
            assertEquals(1, platform.userOpenCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertNotEquals(SteamSessionPhase.FAILED, backend.session.value.phase)
        }

    @Test
    fun `late authenticated restore result cannot overwrite user login`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 9L, ready = true)
            val platform = RecordingSessionPlatform()
            lateinit var backend: SteamContentSdkSessionBackend
            platform.beforeRestoreEvent = { backend.login("new-account", "password") }
            backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()

            assertEquals(1, platform.restoreCalls)
            assertEquals(1, platform.userOpenCalls)
            assertEquals(1, platform.authenticatedPublications)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
            assertEquals("new-account", backend.session.value.accountName)
        }

    @Test
    fun `old network generation probe cannot open or publish over replacement generation`() =
        runTest {
            val readiness = FakeNetworkReadiness(generation = 10L, ready = true)
            val oldProbeEntered = CompletableDeferred<Unit>()
            val oldProbeCancelled = CompletableDeferred<Unit>()
            val platform =
                RecordingSessionPlatform().apply {
                    prepareBehavior = {
                        oldProbeEntered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            oldProbeCancelled.complete(Unit)
                        }
                    }
                }
            val backend = sessionBackend(platform, readiness)

            backend.restorePersistedSession()
            runCurrent()
            oldProbeEntered.await()

            readiness.emit(generation = 11L, ready = true)
            platform.prepareBehavior = { TestPreparedRoute }
            runCurrent()
            oldProbeCancelled.await()

            assertEquals(2, platform.prepareCalls)
            assertEquals(1, platform.openCalls)
            assertEquals(1, platform.restoreCalls)
            assertEquals(SteamSessionPhase.SIGNED_IN, backend.session.value.phase)
        }

    private fun kotlinx.coroutines.test.TestScope.sessionBackend(
        platform: RecordingSessionPlatform,
        readiness: SteamSessionNetworkReadinessSource,
    ): SteamContentSdkSessionBackend =
        SteamContentSdkSessionBackend(
            platform = platform,
            credentialStore =
                EncryptedSteamCredentialStore(
                    MemoryEncryptedStringStore(
                        encodeSteamCredential(PersistedSteamCredential("account", "refresh-token")),
                    ),
                ),
            messages = TestSessionMessages,
            diagnostics = NoOpDiagnostics,
            scope = backgroundScope,
            admissionStore = MemoryAdmissionStore,
            maintenanceIntervalMs = 0L,
            networkReadiness = readiness,
        )
}

private class FakeNetworkReadiness(
    generation: Long,
    ready: Boolean,
) : SteamSessionNetworkReadinessSource {
    private val mutableState = MutableStateFlow(SteamSessionNetworkReadiness(generation, ready))
    override val state = mutableState

    fun emit(
        generation: Long,
        ready: Boolean,
    ) {
        mutableState.value = SteamSessionNetworkReadiness(generation, ready)
    }
}

private data object TestPreparedRoute : SteamContentSdkPreparedRoute

private class RecordingSessionPlatform : SteamContentSdkPlatform {
    var prepareCalls = 0
    var openCalls = 0
    var restoreCalls = 0
    var userOpenCalls = 0
    var authenticatedPublications = 0
    var preparedRoute: SteamContentSdkPreparedRoute? = TestPreparedRoute
    var preparedOpenAvailable = true
    var beforeRestoreEvent: () -> Unit = {}
    var prepareBehavior: suspend () -> SteamContentSdkPreparedRoute? = { preparedRoute }

    override suspend fun prepareLiveRoute(): SteamContentSdkPreparedRoute? {
        prepareCalls += 1
        return prepareBehavior()
    }

    override suspend fun openPrepared(route: SteamContentSdkPreparedRoute): SteamContentSdkPlatformSession? {
        check(route === TestPreparedRoute)
        openCalls += 1
        return if (preparedOpenAvailable) {
            RecordingPlatformSession(
                onRestore = { restoreCalls += 1 },
                beforeEvent = { beforeRestoreEvent() },
                onPublish = { authenticatedPublications += 1 },
            )
        } else {
            null
        }
    }

    override suspend fun open(): SteamContentSdkPlatformSession {
        userOpenCalls += 1
        return RecordingPlatformSession(
            onRestore = {},
            onLogin = {},
            onPublish = { authenticatedPublications += 1 },
        )
    }

    override suspend fun openWithLiveCandidates(): SteamContentSdkPlatformSession = error("automatic restore must consume a prepared route")
}

private class RecordingPlatformSession(
    private val onRestore: () -> Unit,
    private val onLogin: () -> Unit = {},
    private val beforeEvent: () -> Unit = {},
    private val onPublish: () -> Unit = {},
) : SteamContentSdkPlatformSession {
    private var state = SteamContentSdkSessionState.DISCONNECTED
    private var event: SteamContentSdkSessionEvent? = null

    override fun publishAuthenticated(): Boolean {
        onPublish()
        return true
    }

    override fun supportsAccountLogon(): Boolean = true
    override fun supportsRestore(): Boolean = true
    override fun state(): SteamContentSdkSessionState = state
    override fun pollEvent(timeoutMs: Int): SteamContentSdkSessionEvent? {
        if (event != null) beforeEvent()
        return event.also { event = null }
    }
    override fun maintain(autoReconnect: Boolean) = SteamContentSdkMaintenance(SteamContentSdkMaintenanceStep.IDLE)
    override fun pump() = SteamContentSdkPump(SteamContentSdkPumpOutcome.IDLE)
    override fun reconnect() = SteamContentSdkReconnect()
    override fun accountProfile(): SteamContentSdkAccountProfile? = null
    override fun guardChoices(): Long = 0L
    override fun chooseGuard(choice: Int) = Unit
    override fun challengeId(): ByteArray? = null
    override fun refreshToken(): ByteArray = "refresh-token".toByteArray()
    override fun lastError(): String? = null
    override fun beginAccountLogon(accountName: String, password: ByteArray) {
        onLogin()
        state = SteamContentSdkSessionState.AUTHENTICATED
        event = SteamContentSdkSessionEvent.StateChanged(state)
    }

    override fun restore(accountName: String, refreshToken: ByteArray) {
        onRestore()
        state = SteamContentSdkSessionState.AUTHENTICATED
        event = SteamContentSdkSessionEvent.StateChanged(state)
    }

    override fun respondToChallenge(challengeId: ByteArray, answer: ByteArray) = Unit
    override fun logout() = Unit
    override fun close() = Unit
}

private class MemoryEncryptedStringStore(
    initial: String?,
) : EncryptedStringStore {
    private var value = initial
    override fun read(): EncryptedStringReadResult = value?.let(EncryptedStringReadResult::Value) ?: EncryptedStringReadResult.Missing
    override fun write(value: String) { this.value = value }
    override fun clear() { value = null }
}

private data object MemoryAdmissionStore : SteamAccountAdmissionStore {
    private var limited = false
    override fun readLimited(): Boolean = limited
    override fun writeLimited(limited: Boolean) { this.limited = limited }
}

private data object NoOpDiagnostics : DiagnosticRepository {
    override suspend fun record(event: DiagnosticEvent) = Unit
    override suspend fun readRecent(limit: Int): List<DiagnosticEvent> = emptyList()
    override suspend fun exportRedactedText(): String = ""
    override suspend fun clear() = Unit
}

private data object TestSessionMessages : SteamContentSdkSessionMessages {
    override val restoringSession = "restoring"
    override val connecting = "connecting"
    override val requestingLogin = "requesting"
    override val enterCredentials = "enter credentials"
    override val checkingLoginMethod = "checking"
    override val enterEmailCode = "email code"
    override val enterMobileCode = "mobile code"
    override val confirmMobile = "confirm"
    override val codeSubmitted = "submitted"
    override val signedIn = "signed in"
    override val signedOut = "signed out"
    override val signOutIncomplete = "sign out incomplete"
    override val credentialsExpired = "expired"
    override val sessionStorageUnavailable = "storage unavailable"
    override val restoreFailed = "restore failed"
    override val loginTimeout = "login timeout"
    override val unavailable = "unavailable"
    override val authRejected = "rejected"
    override val networkFailed = "network failed"
    override val challengeUnsupported = "challenge unsupported"
    override val noGuardRequest = "no guard"
    override val noManualLogin = "no manual login"
}
