package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.steamcontent.sdk.DepotRangeStream
import io.steamcontent.sdk.EngineCapability
import io.steamcontent.sdk.ManifestFileList
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionMode
import io.steamcontent.sdk.SessionCapabilityBits
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SessionTransportKind
import io.steamcontent.sdk.SteamContentEngine
import io.steamcontent.sdk.SteamContentEngine.WorkshopResolveResponse
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
 *   sandbox. The root is [storageRoot], the directory the SDK resolves a relative download destination
 *   against, which is what lets the app name its existing per-item directory instead of introducing one;
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
        /**
         * Route inputs the host may supply. Empty means "connect directly".
         *
         * [cmProxy] is an HTTP proxy for the CM transport, for a network that passes no Steam CM traffic
         * directly; [cmProxyAuthorization] is that proxy's `Proxy-Authorization` value, taken from the
         * platform's own store and never logged. [extraTrustRootPem] is a certificate the host trusts **for
         * this route only** - the root of its own TLS-terminating bridge - so a route that needs an
         * interception root does not widen trust for every other connection.
         */
        data class Route(
            val cmCandidates: String? = null,
            val cdnProxy: String? = null,
            val cmProxy: String? = null,
            val cmProxyAuthorization: String? = null,
            val transport: Int = SessionTransportKind.STEAM3,
            val extraTrustRootPem: String? = null,
        )

        /**
         * The root the SDK resolves a relative download destination against.
         *
         * The app's per-item download directories already live under `<filesDir>/wallhub-workshop`, and
         * the SDK accepts only a safe relative destination under its storage root. Using the application
         * files directory as that root lets a download name the existing per-item directory rather than
         * introduce a second layout; the SDK's own private staging (`.sc-staging`) and journal therefore
         * sit beside the app's files, and content still lands in the directory the app already reads.
         */
        val storageRoot: File by lazy {
            runCatching { context.filesDir.canonicalFile }.getOrElse { context.filesDir.absoluteFile }
        }

        private val storageRootPath: String by lazy { storageRoot.absolutePath }

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
            val roots =
                if (route.extraTrustRootPem.isNullOrEmpty()) {
                    trustBundle
                } else {
                    // One bundle still serves CM, CDN, and chunk fetches; the extra root is appended, never
                    // substituted, so the platform store remains the baseline.
                    trustBundle + route.extraTrustRootPem.toByteArray(Charsets.US_ASCII)
                }
            val created =
                SteamContentEngine.createWithSession(
                    SteamContentEngine.SessionOptions(
                        eventCapacity = 64,
                        trustRootsPem = roots,
                        sessionMode = SessionMode.ACCOUNT,
                        transport = route.transport,
                        cmCandidates = route.cmCandidates,
                        storageRoot = storageRootPath,
                        cdnProxy = route.cdnProxy,
                        cmProxy = route.cmProxy,
                        cmProxyAuthorization = route.cmProxyAuthorization,
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
         * [destination] names a safe relative directory under [storageRoot] so the published files land in
         * the host's own layout; null accepts the SDK's own naming. The caller keeps the task; nothing in
         * the returned handle exposes a Depot Key, a CDN token, or a path.
         */
        @Synchronized
        fun startDownload(
            appId: Long,
            depotId: Long,
            manifestId: Long = 0L,
            destination: String? = null,
        ): SteamDownloadTask? = engine?.startDownload(appId, depotId, manifestId, destination)

        /**
         * Resolves one Workshop item to the content identity a download accepts.
         *
         * The SDK performs the whole chain, so the app never reconstructs an app, depot, or manifest from
         * a public details response, and no credential or key crosses this call. A chain that does not
         * complete throws the SDK's typed failure, which carries no material.
         */
        @Synchronized
        fun resolveWorkshop(itemId: Long): WorkshopResolveResponse {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            return open.resolveWorkshop(itemId)
        }

        /**
         * Lists the files of the decoded manifest for one content identity.
         *
         * The SDK decodes the manifest with the Depot Key, so this is the only way to name a file of an item
         * whose public details do not name one, and the only way at all when the manifest's file names are
         * encrypted. The paths come back exactly as the manifest states them, which is what the range stream
         * accepts, and the caller owns the returned list.
         */
        @Synchronized
        fun openManifestFiles(
            appId: Long,
            depotId: Long,
            manifestId: Long = 0L,
        ): ManifestFileList {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            return open.openManifestFiles(appId, depotId, manifestId)
        }

        /**
         * Opens a range stream over one file of a resolved content identity.
         *
         * Resolving, authorizing, and serving the ranges all happen inside the SDK: the app hands in the
         * app, depot, manifest, and the in-manifest file path, and receives a handle that serves any range
         * of that file through the same chunk source and cache the download plane uses. No Depot Key, CDN
         * token, or URL crosses this call, and the caller owns the returned handle.
         */
        @Synchronized
        fun openRangeStream(
            appId: Long,
            depotId: Long,
            filePath: String,
            manifestId: Long = 0L,
            cacheCapacityBytes: Long = 0L,
            retryLimit: Int = 0,
        ): DepotRangeStream {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            return open.openRangeStream(
                appId = appId,
                depotId = depotId,
                filePath = filePath,
                manifestId = manifestId,
                cacheCapacityBytes = cacheCapacityBytes,
                retryLimit = retryLimit,
            )
        }

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

        /**
         * Restores a session from the platform's persisted refresh token.
         *
         * This is the adapter's normal path: the app already holds a Steam refresh token from its own login, and
         * the SDK takes the token for one operation instead of asking the user for a password again. The token
         * is not retained here, not logged, and not written anywhere.
         */
        @Synchronized
        fun restore(
            accountName: String,
            refreshToken: ByteArray,
        ): Boolean {
            val open = session ?: return false
            if (!open.capabilities().supports(SessionCapabilityBits.RESTORE)) return false
            open.restore(accountName.toByteArray(Charsets.UTF_8), refreshToken)
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
