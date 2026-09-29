package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.data.steam.SteamContentSdkLiveSession
import com.wallhub.android.data.steam.SteamContentSdkRouteFactory
import com.wallhub.android.data.steam.isAttemptInFlight
import io.steamcontent.sdk.DepotRangeStream
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionFailure
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SteamDownloadTask
import io.steamcontent.sdk.SteamSession
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the SDK session for a download from the platform hook, restored from the refresh token the app
 * already stores.
 *
 * When the session domain already holds an authenticated session for the same account, this borrows it:
 * the peer refuses a second live session for one account, and a restore into a competing one is exactly
 * what it refuses. Otherwise it opens its own session and restores from the stored token.
 *
 * This is the only class that touches the SDK's native session handle. The token arrives from the
 * existing credential boundary and goes straight into `restore`; it is not retained, logged, or copied
 * anywhere. A missing token, an unavailable platform route, or a refused restore all fail here with a
 * clear error, so the SDK path never silently falls back to the old backend.
 *
 * A restore that fails at the transport layer - the class the SDK reports when its whole candidate pass
 * failed to connect - is retried exactly once against a candidate list read live from the directory. That
 * is the only outcome that can mean the platform's cached endpoint set is the stale input, and one retry
 * is the whole of the policy: there is no loop, and the credential presented is the same stored token.
 */
@Singleton
internal class SteamContentSdkEngineSessionOpener
    @Inject
    constructor(
        private val engine: SteamContentSdkEngine,
        private val routes: SteamContentSdkRouteFactory,
        private val sessionRepository: Provider<SteamSessionRepository>,
    ) : SteamContentSdkSessionOpener {
        override suspend fun open(credential: SteamContentCredential): SteamContentSdkSession =
            withContext(Dispatchers.IO) {
                val token = credential.refreshToken
                check(token.isNotBlank()) { "The stored Steam refresh token is empty" }
                // The session domain may already own an authenticated session for this account. The peer
                // refuses a second live SDK session for one account, so this path borrows that session
                // instead of restoring a competing one - and then must not close it. The one line this
                // logs is whether it borrowed, so a device run can separate "the restore was refused"
                // from "no restore was attempted".
                val live = SteamContentSdkLiveSession.authenticated(credential.accountName)
                if (live != null) {
                    Log.i(TAG, "content session reused=true")
                    return@withContext SteamContentSdkEngineSession(live, ownsEngine = false)
                }
                // No live session, but the account session may be mid-attempt. Opening our own then asks for a
                // second live session for the same account, which the peer refuses - the device reading showed
                // exactly that, as `protocol adapter failed`, while a paused task resumed into a session
                // rebuild. Waiting for the attempt to settle is what keeps the two planes from competing.
                val recovered = awaitAttemptToSettle(credential.accountName)
                if (recovered != null) {
                    Log.i(TAG, "content session reused=after-recovery")
                    return@withContext SteamContentSdkEngineSession(recovered, ownsEngine = false)
                }
                Log.i(TAG, "content session reused=false")
                return@withContext try {
                    restoreOnPlatformRoute(credential.accountName, token, refreshLive = false)
                } catch (transportFailure: SteamContentSdkTransportFailure) {
                    // A transport-class failure is the one outcome that puts the candidate list itself under
                    // suspicion. The platform hands the engine its own cached endpoint set, and the engine
                    // treats a host list as authoritative, so nothing else would ever question it. This asks
                    // for a list read from the directory and tries exactly once more with the same stored
                    // credential; a second failure reports the first, and kSteam is never a fallback.
                    try {
                        restoreOnPlatformRoute(credential.accountName, token, refreshLive = true)
                    } catch (_: SteamContentSdkTransportFailure) {
                        throw transportFailure
                    }
                }
            }

        /**
         * Waits, for a bounded time, for an in-flight account attempt to produce a session this path can borrow.
         *
         * Returns null when no attempt was in flight, when the attempt settled without a session, or when the
         * wait ran out - in every one of those cases the caller falls back to restoring its own session, which is
         * what a download does when the session domain is not holding one.
         */
        private suspend fun awaitAttemptToSettle(accountName: String): SteamContentSdkEngine? {
            if (!sessionRepository.get().session.value.phase.isAttemptInFlight()) return null
            Log.i(TAG, "content session waiting for the account attempt to settle")
            val deadline = System.nanoTime() + RECOVERY_WAIT_NANOS
            while (System.nanoTime() < deadline) {
                delay(RECOVERY_POLL_MS)
                SteamContentSdkLiveSession.authenticated(accountName)?.let { return it }
                if (!sessionRepository.get().session.value.phase.isAttemptInFlight()) return null
            }
            return null
        }

        /**
         * Opens the engine on the platform route and restores one stored account.
         *
         * [refreshLive] asks the route for an endpoint set read from the directory instead of the platform's
         * cached one, which is what the single retry after a transport failure uses.
         */
        private suspend fun restoreOnPlatformRoute(
            accountName: String,
            token: String,
            refreshLive: Boolean,
        ): SteamContentSdkEngineSession {
            val route =
                routes.platformRoute(refreshLive = refreshLive)
                    ?: error("The platform's Steam route is unavailable; the Steam Content SDK path cannot start")
            val session = engine.open(route) ?: error("The Steam Content SDK is unavailable on this device")
            check(engine.restore(accountName, token.toByteArray(Charsets.UTF_8))) {
                "The Steam Content SDK session cannot restore a stored account"
            }
            awaitAuthenticated(session)
            return SteamContentSdkEngineSession(engine)
        }

        private suspend fun awaitAuthenticated(session: SteamSession) {
            val deadline = System.nanoTime() + AUTHENTICATION_TIMEOUT_NANOS
            while (true) {
                when (engine.state()) {
                    SessionState.AUTHENTICATED -> return
                    SessionState.CLOSED -> error("The Steam Content SDK session closed before it authenticated")
                }
                val event = session.pollEvent(SESSION_POLL_MS)
                if (event?.kind == SessionEventKind.COMMAND_FAILED) {
                    val diagnostic = runCatching { session.lastError() }.getOrNull()?.takeIf(String::isNotBlank)
                    val message =
                        "The Steam Content SDK could not restore the stored account" +
                            (diagnostic?.let { ": $it" } ?: "")
                    // The SDK reports a failed connect pass as the transport class; only that class can mean
                    // the endpoint list the platform supplied is the stale input, so only it earns the retry.
                    if (event.value == SessionFailure.NETWORK) throw SteamContentSdkTransportFailure(message)
                    error(message)
                }
                if (System.nanoTime() >= deadline) {
                    error("The Steam Content SDK session did not authenticate in time")
                }
            }
        }

        private companion object {
            const val TAG = "WallHubSteamSession"
            const val SESSION_POLL_MS = 250
            const val AUTHENTICATION_TIMEOUT_NANOS = 30_000_000_000L
        }
    }

/**
 * A restore that failed in the transport layer rather than in the credential layer.
 *
 * The SDK reports a failed candidate pass with the same "protocol adapter failed" text whatever the
 * underlying cause, so this type is what separates "the route did not connect" from "the peer refused the
 * account": only the former is worth one retry against a live endpoint list, and the latter must never be
 * retried or treated as a route problem.
 */
private class SteamContentSdkTransportFailure(
    message: String,
) : IllegalStateException(message)

/**
 * The engine-backed session a download holds for the length of one transfer.
 *
 * [ownsEngine] is false when the session was borrowed from the session domain's already-authenticated
 * engine: the download must not close an engine it does not own, or it would end the UI's session.
 */
private class SteamContentSdkEngineSession(
    private val engine: SteamContentSdkEngine,
    private val ownsEngine: Boolean = true,
) : SteamContentSdkSession {
    override val storageRoot: File
        get() = engine.storageRoot

    override fun resolveWorkshop(itemId: Long): SteamContentSdkResolvedItem {
        val response = engine.resolveWorkshop(itemId)
        return SteamContentSdkResolvedItem(
            itemId = response.itemId,
            appId = response.appId,
            depotId = response.depotId,
            manifestId = response.manifestId,
            resolved = response.isResolved,
        )
    }

    override fun startDownload(
        appId: Long,
        depotId: Long,
        manifestId: Long,
        destination: String?,
        chunkConcurrency: Int,
    ): SteamContentSdkTask {
        val task =
            engine.startDownload(
                appId = appId,
                depotId = depotId,
                manifestId = manifestId,
                destination = destination,
                chunkConcurrency = chunkConcurrency,
            ) ?: error("The Steam Content SDK did not start a download task")
        return SteamContentSdkEngineTask(task)
    }

    override fun manifestFiles(
        appId: Long,
        depotId: Long,
        manifestId: Long,
    ): List<SteamContentSdkManifestFile> =
        engine.openManifestFiles(appId, depotId, manifestId).use { list ->
            (0 until list.size()).map { index ->
                val file = list.file(index)
                SteamContentSdkManifestFile(
                    path = list.path(index),
                    size = file.size,
                    flags = file.flags,
                )
            }
        }

    override fun openRangeStream(
        appId: Long,
        depotId: Long,
        filePath: String,
        manifestId: Long,
        cacheCapacityBytes: Long,
    ): SteamContentSdkRangeStream =
        SteamContentSdkEngineRangeStream(
            engine.openRangeStream(
                appId = appId,
                depotId = depotId,
                filePath = filePath,
                manifestId = manifestId,
                cacheCapacityBytes = cacheCapacityBytes,
            ),
        )

    override fun close() {
        if (ownsEngine) engine.close()
    }
}

/** The SDK range-stream handle as [SteamContentSdkRangeStream]. */
private class SteamContentSdkEngineRangeStream(
    private val stream: DepotRangeStream,
) : SteamContentSdkRangeStream {
    override fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int = stream.readAt(position, destination, offset, length)

    override fun close() {
        stream.close()
    }
}

/** The SDK task handle as [SteamContentSdkTask]. */
private class SteamContentSdkEngineTask(
    private val task: SteamDownloadTask,
) : SteamContentSdkTask {
    override fun status(): SteamContentSdkTaskStatus {
        val status = task.status()
        return SteamContentSdkTaskStatus(
            state = status.state,
            committedBytes = status.committedBytes,
            totalDecodedBytes = status.totalDecodedBytes,
            errorOrdinal = status.errorOrdinal,
        )
    }

    override fun pollEvent(timeoutMs: Int): SteamContentSdkTaskEvent? =
        task.pollEvent(timeoutMs)?.let { event -> SteamContentSdkTaskEvent(event.kind, event.value) }

    override fun lastError(): String? = task.lastError()

    override fun pause() = task.pause()

    override fun resume() = task.resume()

    override fun cancel() = task.cancel()

    override fun close() {
        task.close()
    }
}

/** How long a download waits for an in-flight account attempt before restoring its own session. */
private const val RECOVERY_WAIT_NANOS = 20_000_000_000L

/** How often that wait re-checks whether the account session has come back. */
private const val RECOVERY_POLL_MS = 250L
