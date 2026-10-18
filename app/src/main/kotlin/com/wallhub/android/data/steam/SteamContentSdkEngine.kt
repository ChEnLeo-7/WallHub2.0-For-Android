package com.wallhub.android.data.steam

import android.content.Context
import android.util.Log
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import io.steamcontent.sdk.DepotRangeStream
import io.steamcontent.sdk.EngineCapability
import io.steamcontent.sdk.ManifestFileList
import io.steamcontent.sdk.PlayerProfiles
import io.steamcontent.sdk.SessionEventKind
import io.steamcontent.sdk.SessionMode
import io.steamcontent.sdk.SessionCapabilityBits
import io.steamcontent.sdk.SessionState
import io.steamcontent.sdk.SessionTransportKind
import io.steamcontent.sdk.SteamContentEngine
import io.steamcontent.sdk.SteamContentEngine.PlayerPlaytime
import io.steamcontent.sdk.SteamContentEngine.WorkshopAuthorRequest
import io.steamcontent.sdk.SteamContentEngine.WorkshopCommentsRequest
import io.steamcontent.sdk.SteamContentEngine.WorkshopDetailsRequest
import io.steamcontent.sdk.SteamContentEngine.WorkshopInteractionState
import io.steamcontent.sdk.SteamContentEngine.WorkshopPersonalRequest
import io.steamcontent.sdk.SteamContentEngine.WorkshopResolveResponse
import io.steamcontent.sdk.SteamContentEngine.WorkshopSearchRequest
import io.steamcontent.sdk.SteamDownloadTask
import io.steamcontent.sdk.SteamSession
import io.steamcontent.sdk.WorkshopOperation
import io.steamcontent.sdk.WorkshopPage
import io.steamcontent.sdk.WorkshopResult
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform hook that lets the Steam Content SDK own Steam behavior on Android.
 *
 * The SDK deliberately takes only host-owned inputs; this class supplies them from the platform:
 *
 * - **trust** comes from the platform's accepted issuers, which Android exposes as one `AndroidCAStore` entry
 *   per certificate, so they are collected and encoded once and the same bundle then serves the CM route, the
 *   CDN route, and the chunk fetches;
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
    internal constructor(
        @ApplicationContext private val context: Context,
        private val deviceSeed: SteamDeviceSeedStore,
        private val sessionMode: Int = SessionMode.ACCOUNT,
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
            val connectAttempts: Int = 0,
            val cdnProxy: String? = null,
            val cdnProxyAuthorization: String? = null,
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
        private val sessions = GenerationHandleOwner<SteamSession>()
        private var publishedSessionGeneration = 0L

        /** The route the live engine was created for, so a reopen can tell a reconnect from a config change. */
        private var engineRoute: Route? = null

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
            // A reopen for the same route keeps the engine and replaces only its session. Closing the engine
            // here - which is what this used to do unconditionally - dropped every task another holder had
            // started on it, because the SDK keeps tasks per engine and releases them with it: a download
            // that had borrowed this engine then polled a released handle and failed with
            // `invalid task handle`. The configuration is immutable per engine, so a route change is still a
            // restart.
            val existing = engine
            if (existing != null && canReuseEngine(engineRoute, route)) {
                sessions.closeCurrent()
                val opened = runCatching { existing.openSession() }.getOrNull() ?: return null
                val published = sessions.replace(opened).value
                publishedSessionGeneration += 1L
                return published
            }
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
                        sessionMode = sessionMode,
                        transport = route.transport,
                        connectAttempts = route.connectAttempts,
                        cmCandidates = route.cmCandidates,
                        storageRoot = storageRootPath,
                        cdnProxy = route.cdnProxy,
                        cdnProxyAuthorization = route.cdnProxyAuthorization,
                        cmProxy = route.cmProxy,
                        cmProxyAuthorization = route.cmProxyAuthorization,
                        // ADR 0010: the platform owns the device identity, and Steam's logons carry it.
                        deviceSeed = deviceSeed.seed(),
                    )
                )
            created.start()
            engine = created
            engineRoute = route
            val opened = created.openSession() ?: run {
                close()
                return null
            }
            val published = sessions.replace(opened).value
            publishedSessionGeneration += 1L
            return published
        }

        /** Opens a session and captures ownership of that exact generation before another opener can replace it. */
        @Synchronized
        internal fun openHandle(route: Route = Route()): GenerationHandleOwner.Handle<SteamSession>? {
            val opened = open(route) ?: return null
            return sessionHandle(opened)
        }

        /**
         * Releases the open session and keeps the engine.
         *
         * The engine owns the data plane: download tasks and range streams belong to it and the SDK releases
         * them only when the engine itself is closed. A session is dropped whenever the connection is lost or
         * an attempt ends, so it must be releasable on its own - otherwise losing the account connection
         * takes every running transfer with it.
         */
        @Synchronized
        fun closeSession() {
            sessions.closeCurrent()
        }

        /**
         * Returns an owner for the current session generation.
         *
         * The wrapper closes exactly this session. If another open replaces it first, closing this owner cannot
         * clear or close the replacement and never closes the process-wide engine.
         */
        @Synchronized
        internal fun sessionHandle(session: SteamSession): GenerationHandleOwner.Handle<SteamSession>? =
            sessions.currentHandle()?.takeIf { it.value === session }

        /** Returns the open session, or null when the engine is closed. */
        @Synchronized
        fun currentSession(): SteamSession? = sessions.currentValue()

        /** Opaque generation used to fence account cursors and one-shot recovery. */
        @Synchronized
        internal fun currentSessionGeneration(): Long? =
            sessions.currentHandle()?.let { publishedSessionGeneration }

        /** Reconnects only the session generation that actually refused the foreground request. */
        @Synchronized
        internal fun reconnectSessionIfCurrent(expectedGeneration: Long): Boolean {
            val handle = sessions.currentHandle() ?: return false
            if (publishedSessionGeneration != expectedGeneration) return true
            val reconnected = runCatching { handle.value.reconnect() }.getOrNull()?.succeeded == true
            if (reconnected) publishedSessionGeneration += 1L
            return reconnected
        }

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
            chunkConcurrency: Int = 0,
        ): SteamDownloadTask? =
            engine?.startDownload(appId, depotId, manifestId, destination, chunkConcurrency)

        /**
         * Resolves one Workshop item to the content identity a download accepts.
         *
         * The SDK performs the whole chain, so the app never reconstructs an app, depot, or manifest from
         * a public details response, and no credential or key crosses this call. A chain that does not
         * complete throws the SDK's typed failure, which carries no material.
         */
        @Synchronized
        fun resolveWorkshop(
            itemId: Long,
            appIdHint: Long = 0L,
            contentManifestIdHint: Long = 0L,
        ): WorkshopResolveResponse {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            val hasIdentityHint = appIdHint > 0L && contentManifestIdHint != 0L
            val appId = if (hasIdentityHint) appIdHint else WALLPAPER_ENGINE_APP_ID.toLong()
            val manifestId = if (hasIdentityHint) contentManifestIdHint else 0L
            return open.resolveWorkshop(itemId, appId, manifestId)
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
            val open = sessions.currentValue() ?: return false
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
            val open = sessions.currentValue() ?: return false
            if (!open.capabilities().supports(SessionCapabilityBits.RESTORE)) return false
            open.restore(accountName.toByteArray(Charsets.UTF_8), refreshToken)
            return true
        }

        /** Submits one Guard answer. The answer is not retained here. */
        @Synchronized
        fun submitGuardAnswer(challengeId: ByteArray, answer: ByteArray): Boolean {
            val open = sessions.currentValue() ?: return false
            open.respondToChallenge(challengeId, answer)
            return true
        }

        /**
         * Reads only the current generation and the native session's atomic state. Do not take the engine-wide
         * monitor here: other facade calls can hold it across CM network I/O, and a playback lease must be able
         * to decide whether the published session is borrowable without queuing behind that unrelated request.
         */
        fun state(): Int = sessions.currentValue()?.state() ?: SessionState.DISCONNECTED

        @Synchronized
        fun logout() {
            sessions.currentValue()?.logout()
        }

        @Synchronized
        fun close() {
            sessions.closeCurrent()
            runCatching { engine?.close() }
            engine = null
            engineRoute = null
        }

        /**
         * Resolves one page of browse or search results, read through [WorkshopPage].
         *
         * The page is a handle the caller owns and closes. The Workshop provider must be installed and must
         * advertise the operation, so an engine without one throws [IllegalStateException] rather than
         * answering an empty page.
         */
        @Synchronized
        fun workshopSearch(
            appId: Long,
            sort: Int,
            trendWindow: Int,
            pageSize: Int,
            pageOffset: Int,
            queryText: String?,
            contentTypes: List<Int>,
            tags: List<String>,
            excludedTags: List<String> = emptyList(),
            createdAfter: Long = 0L,
            createdBefore: Long = 0L,
            language: Int = 0,
            tagGroups: List<List<String>> = emptyList(),
        ): WorkshopPage {
            val open = openEngine(WorkshopOperation.SEARCH)
            return open.workshopSearch(
                WorkshopSearchRequest(
                    pageSize = pageSize,
                    sort = sort,
                    trendWindow = trendWindow,
                    pageOffset = pageOffset,
                    appId = appId,
                    queryText = queryText,
                    contentTypes = contentTypes,
                    tags = tags,
                    excludedTags = excludedTags,
                    createdAfter = createdAfter,
                    createdBefore = createdBefore,
                    language = language,
                    tagGroups = tagGroups,
                )
            )
        }

        /**
         * Resolves one page of an author's published items, read through [WorkshopPage].
         *
         * [authorId] is the account whose profile is open, never a credential. As with [workshopSearch], an
         * engine that cannot serve the read throws rather than answering an empty page.
         */
        @Synchronized
        fun workshopAuthorItems(
            authorId: Long,
            pageSize: Int,
            pageOffset: Int,
            contentTypes: List<Int>,
        ): WorkshopPage {
            val open = openEngine(WorkshopOperation.AUTHOR_ITEMS)
            return open.workshopAuthorItems(
                WorkshopAuthorRequest(
                    authorId = authorId,
                    pageSize = pageSize,
                    pageOffset = pageOffset,
                    contentTypes = contentTypes,
                )
            )
        }

        /**
         * Resolves one page of the signed-in account's own items, read through [WorkshopPage].
         *
         * [accountId] is both the account the list belongs to and the account the read is authorized as - an
         * identifier the caller already holds, never a credential. [list] is a `WorkshopPersonalList`
         * ordinal. [appId] scopes the read to one App's Workshop, which the service requires for a row to
         * carry its own tags and file type.
         */
        @Synchronized
        fun workshopPersonalItems(
            accountId: Long,
            list: Int,
            pageSize: Int,
            pageOffset: Int,
            appId: Long,
        ): WorkshopPage {
            val open = openEngine(WorkshopOperation.PERSONAL_ITEMS)
            return open.workshopPersonalItems(
                WorkshopPersonalRequest(
                    accountId = accountId,
                    list = list,
                    pageSize = pageSize,
                    pageOffset = pageOffset,
                    appId = appId,
                )
            )
        }

        /**
         * Resolves one page of one item's comments, read through [WorkshopPage].
         *
         * A comment page answers the comment field space rather than the summary one, which the returned
         * handle already routes. Comments take no authorization of their own, but the provider must still
         * advertise [WorkshopOperation.COMMENTS].
         */
        @Synchronized
        fun workshopComments(
            itemId: Long,
            sort: Int,
            pageSize: Int,
            pageOffset: Int,
        ): WorkshopPage {
            val open = openEngine(WorkshopOperation.COMMENTS)
            return open.workshopComments(
                WorkshopCommentsRequest(
                    itemId = itemId,
                    pageSize = pageSize,
                    sort = sort,
                    pageOffset = pageOffset,
                )
            )
        }

        /**
         * Resolves one Workshop item, returning a handle its fields are read through.
         *
         * The caller closes the handle. An engine without a Workshop provider throws
         * [IllegalStateException], exactly as the underlying call does, so a caller that treats the read as
         * optional translates it at its own boundary. [appId] is the App the item belongs to: the service
         * answers a details read within an App, and a read that names none can come back without the item.
         */
        @Synchronized
        fun openWorkshopItem(itemId: Long, appId: Long): WorkshopResult =
            openEngine(WorkshopOperation.DETAILS).openWorkshopItem(
                WorkshopDetailsRequest(itemId = itemId, appId = appId)
            )

        /** Resolves a bounded set of Workshop identities through one native/provider request. */
        @Synchronized
        fun openWorkshopItems(itemIds: LongArray, appId: Long) =
            openEngine(WorkshopOperation.DETAILS).openWorkshopItems(itemIds, appId)

        /**
         * Resolves a bounded set of account ids into a profile handle (ADR 0032).
         *
         * The accounts are read once and held by the returned handle, which the caller closes; the persona
         * and the avatar hash are then read one field at a time. The engine must report
         * [EngineCapability.PLAYER], so an engine without a player plane throws [IllegalStateException]
         * rather than answering an empty set a caller could mistake for "no persona".
         */
        @Synchronized
        fun openPlayerProfiles(accountIds: LongArray): PlayerProfiles {
            // Release diagnostics record only bounded cardinality: account ids and synthetic stack traces add
            // allocation/log I/O without helping latency attribution.
            Log.d("WallHubSdkPlayer", "openPlayerProfiles count=${accountIds.size}")
            return openPlayerEngine().openPlayerProfiles(accountIds)
        }

        /**
         * Reads one App's playtime for the signed-in account (ADR 0032).
         *
         * The caller never names an account; the session's own is the one read. The returned
         * [PlayerPlaytime.present] keeps "no record" distinct from "played zero minutes", and the engine
         * must report [EngineCapability.PLAYER].
         */
        @Synchronized
        fun playerPlaytime(appId: Long): PlayerPlaytime = openPlayerEngine().playerPlaytime(appId)

        /**
         * Sets or clears one item's subscription, answering with the confirmed state.
         *
         * [accountId] is the acting account, an identifier the caller already holds and never a credential.
         * The favourite half of an interaction is not part of this answer.
         */
        @Synchronized
        fun setSubscribed(
            itemId: Long,
            accountId: Long,
            enabled: Boolean,
        ): WorkshopInteractionState =
            openEngine(WorkshopOperation.SET_SUBSCRIBED).setSubscribed(itemId, accountId, enabled)

        /**
         * Sets or clears one item's favourite, answering with the confirmed state.
         *
         * The subscription half of an interaction is not part of this answer.
         */
        @Synchronized
        fun setFavorited(
            itemId: Long,
            accountId: Long,
            enabled: Boolean,
        ): WorkshopInteractionState =
            openEngine(WorkshopOperation.SET_FAVORITED).setFavorited(itemId, accountId, enabled)

        /**
         * Writes one comment to an item's public thread (ADR 0033).
         *
         * [ownerId] is the account that owns the thread, never a credential; the write is authorized as the
         * session's own account, and nothing about that account is sent here as a number. [text] is handed
         * to the SDK for one call and is not retained by this layer. The provider must advertise
         * [WorkshopOperation.POST_COMMENT].
         */
        @Synchronized
        fun postComment(
            itemId: Long,
            ownerId: Long,
            text: String,
        ) {
            openEngine(WorkshopOperation.POST_COMMENT).postComment(itemId, ownerId, text)
        }

        /**
         * Returns the open engine after checking that both the provider and this ABI advertise [operation].
         *
         * The gate is the SDK's own negotiation, so a read that is not implemented yet fails as a typed
         * [IllegalStateException] here instead of reaching native code that cannot serve it. A closed engine
         * reports the same way, which is also how the app learns that no Workshop provider is installed.
         */
        private fun openEngine(operation: Long): SteamContentEngine {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            val capabilities =
                open.workshopCapabilities()
                    ?: error("the Steam Content SDK engine has no Workshop provider")
            return open.takeIf { capabilities.providerSupports(operation) && capabilities.abiSupports(operation) }
                ?: error("the Steam Content SDK cannot serve Workshop operation $operation")
        }

        /**
         * Returns the open engine after checking the player family bit (ADR 0032).
         *
         * A session-backed player plane sets [EngineCapability.PLAYER], so an engine without one reports
         * the same unavailable reading a closed engine does instead of an empty profile set.
         */
        private fun openPlayerEngine(): SteamContentEngine {
            val open = engine ?: error("the Steam Content SDK engine is not open")
            return open.takeIf { it.info().supports(EngineCapability.PLAYER) }
                ?: error("the Steam Content SDK engine has no player plane")
        }

        /**
         * Encodes the platform's accepted issuers into one PEM bundle.
         *
         * The issuer list is the `AndroidCAStore` keystore entry set, not a scan of
         * `/system/etc/security/cacerts`: that directory holds only the system roots, while Android's own TLS
         * stack accepts the system store and the user-installed store together. A user certificate is what a
         * gateway or transparent proxy installs when it intercepts TLS, so a bundle built from the directory
         * alone left the SDK rejecting every intercepted handshake as an unknown issuer while the browser
         * reached the same site.
         *
         * Aliases are sorted so one build of the bundle is reproducible; reading them through the store also
         * lets the platform hand back whatever certificates it actually trusts instead of files this code
         * decodes itself.
         */
        private fun systemTrustBundle(): ByteArray {
            val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null) }
            val certificates =
                store.aliases().toList().sorted().mapNotNull { alias ->
                    (store.getCertificate(alias) as? X509Certificate)?.encoded
                }
            return trustBundlePem(certificates)
        }

        companion object {
            /** The platform trust store that holds the system roots and the user-installed certificates. */
            private const val ANDROID_CA_STORE = "AndroidCAStore"

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

/**
 * Wraps each certificate in [derCertificates] as its own PEM block, in the order given.
 *
 * The encoding lives here rather than beside the store read because it needs no Android API: the platform
 * supplies DER blobs and this half is what a JVM unit test can hold to the exact output. `java.util.Base64`
 * covers both targets, since `minSdk` is 26, so no `android.util.Base64` call is needed and the helper stays
 * callable from the JVM test source set.
 *
 * An empty store throws instead of returning an empty bundle: the SDK rejects an argument with no roots, and a
 * bundle that silently trusts nothing would turn a missing store into a handshake failure at the first
 * connection.
 */
internal fun trustBundlePem(derCertificates: List<ByteArray>): ByteArray {
    check(derCertificates.isNotEmpty()) { "the platform trust store is empty" }
    val encoder = Base64.getEncoder()
    val bundle = StringBuilder()
    for (certificate in derCertificates) {
        val body = encoder.encodeToString(certificate)
        bundle.append("-----BEGIN CERTIFICATE-----\n")
        // PEM bodies are wrapped at 64 characters, which the encoder does not do on its own; a parser that
        // requires that shape would otherwise reject a certificate whose encoding happened to run long.
        for (offset in body.indices step PEM_LINE_LENGTH) {
            bundle.append(body, offset, minOf(offset + PEM_LINE_LENGTH, body.length)).append('\n')
        }
        bundle.append("-----END CERTIFICATE-----\n")
    }
    return bundle.toString().toByteArray(Charsets.US_ASCII)
}

/** The line length PEM bodies use, in characters of Base64 text. */
private const val PEM_LINE_LENGTH = 64

/**
 * Whether a reopen may keep the engine that is already live.
 *
 * The engine's settings are immutable once it is created - transport, candidates, proxies and trust roots are
 * all baked in - so only a request for the same route may reuse it. Everything else is a restart, and a
 * restart is what drops the tasks the engine owns.
 */
internal fun canReuseEngine(
    openRoute: SteamContentSdkEngine.Route?,
    requested: SteamContentSdkEngine.Route,
): Boolean = openRoute != null && openRoute == requested
