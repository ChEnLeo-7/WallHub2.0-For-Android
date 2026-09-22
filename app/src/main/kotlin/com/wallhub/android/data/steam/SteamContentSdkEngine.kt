package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.steamcontent.sdk.EngineCapability
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionMode
import io.steamcontent.sdk.SessionCapabilityBits
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SessionTransportKind
import io.steamcontent.sdk.SteamContentEngine
import io.steamcontent.sdk.SteamDownloadTask
import io.steamcontent.sdk.SteamSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform hook that lets the Steam Content SDK own Steam behavior on Android.
 *
 * The SDK deliberately takes only host-owned inputs; this class supplies them from the platform:
 *
 * - **trust** comes from the system store, which Android keeps as one PEM file per certificate, so the files
 *   are concatenated once and the same bundle then serves the CM route, the CDN route, and the chunk fetches;
 * - **paths** come from the application's own files directory, so every SDK-owned file stays inside the app's
 *   sandbox and no product directory is touched;
 * - **route** is whatever the host has configured (a local bridge endpoint and/or an HTTP proxy), because a
 *   network that blocks Steam directly needs one and the SDK must not guess it.
 *
 * Nothing here logs, returns, or stores a credential: the password and any Guard answer are passed to the SDK
 * for one operation and dropped by it, and the SDK keeps its own session state.
 */
@Singleton
class SteamContentSdkEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Route inputs the host may supply. Empty means "connect directly". */
        data class Route(
            val cmCandidates: String? = null,
            val cdnProxy: String? = null,
        )

        private val storageRoot: String by lazy {
            File(context.filesDir, "sc-sdk").apply { mkdirs() }.absolutePath
        }

        private val trustBundle: ByteArray by lazy { systemTrustBundle() }

        private var engine: SteamContentEngine? = null
        private var session: SteamSession? = null

        /** Reports whether the preview AAR's native library can be used at all on this device. */
        val available: Boolean =
            runCatching { SteamContentEngine.nativeAbiMajor() }
                .getOrNull() == SteamContentEngine.REQUIRED_ABI_MAJOR

        /**
         * Creates the engine and opens a session, or returns null when the SDK is unavailable.
         *
         * [route] is threaded into the engine settings, so a configuration change is a restart rather than a
         * mutation of a live engine.
         */
        @Synchronized
        fun open(route: Route = Route()): SteamSession? {
            if (!available) return null
            close()
            val created =
                SteamContentEngine.createWithSession(
                    SteamContentEngine.SessionOptions(
                        eventCapacity = 64,
                        trustRootsPem = trustBundle,
                        sessionMode = SessionMode.ACCOUNT,
                        transport = SessionTransportKind.STEAM3,
                        cmCandidates = route.cmCandidates,
                        storageRoot = storageRoot,
                        cdnProxy = route.cdnProxy,
                    )
                )
            created.start()
            engine = created
            return created.openSession().also { session = it }
        }

        /** Returns the open session, or null when the engine is closed. */
        @Synchronized
        fun currentSession(): SteamSession? = session

        /** Reports what the SDK says it can do on this device, for diagnostics and negotiation. */
        @Synchronized
        fun capabilities(): SteamContentEngine.Info? = engine?.info()

        /** True when the SDK owns both a session and the data plane. */
        @Synchronized
        fun ownsDataPlane(): Boolean {
            val info = engine?.info() ?: return false
            return info.supports(EngineCapability.SESSION) &&
                info.supports(EngineCapability.DOWNLOAD_CONTROL)
        }

        /**
         * Starts a download the SDK resolves and performs itself.
         *
         * The caller keeps the task; nothing in the returned handle exposes a Depot Key, a CDN token, or a path.
         */
        @Synchronized
        fun startDownload(appId: Long, depotId: Long, manifestId: Long = 0L): SteamDownloadTask? =
            engine?.startDownload(appId, depotId, manifestId)

        /**
         * Begins an account logon. The password is passed straight to the SDK and is not retained here.
         */
        @Synchronized
        fun logon(accountName: String, password: ByteArray): Boolean {
            val open = session ?: return false
            if (!open.capabilities().supports(SessionCapabilityBits.ACCOUNT_LOGIN)) return false
            open.beginAccountLogon(accountName.toByteArray(Charsets.UTF_8), password)
            return true
        }

        /** Submits one Guard answer. The answer is not retained here. */
        @Synchronized
        fun submitGuardAnswer(challengeId: ByteArray, answer: ByteArray): Boolean {
            val open = session ?: return false
            open.respondToChallenge(challengeId, answer)
            return true
        }

        @Synchronized
        fun state(): Int = session?.state() ?: SessionState.DISCONNECTED

        @Synchronized
        fun logout() {
            session?.logout()
        }

        @Synchronized
        fun close() {
            runCatching { session?.close() }
            runCatching { engine?.close() }
            session = null
            engine = null
        }

        /**
         * Concatenates the platform trust store into one PEM bundle.
         *
         * Android keeps system roots as individual **PEM** files; re-encoding them would produce a bundle with
         * no usable roots, which the SDK rejects as an argument error rather than trusting nothing silently.
         */
        private fun systemTrustBundle(): ByteArray {
            val directory = File("/system/etc/security/cacerts")
            val files =
                directory.listFiles()?.sortedBy { it.name }
                    ?: throw IllegalStateException("the platform trust store is unavailable")
            check(files.isNotEmpty()) { "the platform trust store is empty" }
            val bundle = StringBuilder()
            for (file in files) {
                bundle.append(file.readText(Charsets.US_ASCII)).append('\n')
            }
            return bundle.toString().toByteArray(Charsets.US_ASCII)
        }

        companion object {
            /** Session events the future adapter will render, named here so the mapping is in one place. */
            val OBSERVED_EVENTS: Set<Int> =
                setOf(
                    SessionEventKind.STATE_CHANGED,
                    SessionEventKind.AUTHENTICATION_CHALLENGE,
                    SessionEventKind.COMMAND_FAILED,
                    SessionEventKind.LOGGED_OUT,
                )
        }
    }
