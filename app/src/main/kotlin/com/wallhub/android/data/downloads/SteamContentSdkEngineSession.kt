package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.data.steam.SteamContentSdkRouteFactory
import io.steamcontent.sdk.DepotRangeStream
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SteamDownloadTask
import io.steamcontent.sdk.SteamSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the SDK session for a download from the platform hook, restored from the refresh token the app
 * already stores.
 *
 * This is the only class that touches the SDK's native session handle. The token arrives from the
 * existing credential boundary and goes straight into `restore`; it is not retained, logged, or copied
 * anywhere. A missing token, an unavailable platform route, or a refused restore all fail here with a
 * clear error, so the SDK path never silently falls back to the old backend.
 */
@Singleton
internal class SteamContentSdkEngineSessionOpener
    @Inject
    constructor(
        private val engine: SteamContentSdkEngine,
        private val routes: SteamContentSdkRouteFactory,
    ) : SteamContentSdkSessionOpener {
        override suspend fun open(credential: SteamContentCredential): SteamContentSdkSession =
            withContext(Dispatchers.IO) {
                val token = credential.refreshToken
                check(token.isNotBlank()) { "The stored Steam refresh token is empty" }
                val route =
                    routes.throughPlatformBridge()
                        ?: error("The platform's Steam route is unavailable; the Steam Content SDK path cannot start")
                val session = engine.open(route) ?: error("The Steam Content SDK is unavailable on this device")
                check(engine.restore(credential.accountName, token.toByteArray(Charsets.UTF_8))) {
                    "The Steam Content SDK session cannot restore a stored account"
                }
                awaitAuthenticated(session)
                SteamContentSdkEngineSession(engine)
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
                    error(
                        "The Steam Content SDK could not restore the stored account" +
                            (diagnostic?.let { ": $it" } ?: ""),
                    )
                }
                if (System.nanoTime() >= deadline) {
                    error("The Steam Content SDK session did not authenticate in time")
                }
            }
        }

        private companion object {
            const val SESSION_POLL_MS = 250
            const val AUTHENTICATION_TIMEOUT_NANOS = 30_000_000_000L
        }
    }

/** The engine-backed session a download holds for the length of one transfer. */
private class SteamContentSdkEngineSession(
    private val engine: SteamContentSdkEngine,
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
    ): SteamContentSdkTask {
        val task =
            engine.startDownload(appId, depotId, manifestId, destination)
                ?: error("The Steam Content SDK did not start a download task")
        return SteamContentSdkEngineTask(task)
    }

    override fun openRangeStream(
        appId: Long,
        depotId: Long,
        filePath: String,
        manifestId: Long,
    ): SteamContentSdkRangeStream =
        SteamContentSdkEngineRangeStream(
            engine.openRangeStream(
                appId = appId,
                depotId = depotId,
                filePath = filePath,
                manifestId = manifestId,
            ),
        )

    override fun close() {
        engine.close()
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
