package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steam.SteamContentSdkEngineLeaseTracker
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The single application-level owner of shared SDK content-session leases.
 *
 * Callers know only how to acquire an account-scoped lease. Opening, sharing, reference counting and final close
 * remain inside this module, so downloads and range playback cannot build competing session-lifetime policies.
 */
@Singleton
internal class SteamContentSdkSessionBroker
    @Inject
    constructor(
        private val opener: SteamContentSdkSessionOpener,
        private val engineLeases: SteamContentSdkEngineLeaseTracker,
    ) {
        private class Entry(
            val key: String,
            val session: SteamContentSdkSession,
            var users: Int,
        )

        internal class Lease private constructor(
            val session: SteamContentSdkSession,
            private val release: () -> Unit,
        ) : Closeable {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (closed.compareAndSet(false, true)) release()
            }

            companion object {
                fun create(
                    session: SteamContentSdkSession,
                    release: () -> Unit,
                ): Lease = Lease(session, release)
            }
        }

        private class Opening(
            val result: Deferred<SteamContentSdkSession>,
            var reservations: Int,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private val entries = mutableMapOf<String, Entry>()
        private val openings = mutableMapOf<String, Opening>()

        suspend fun acquire(credential: SteamContentCredential): Lease {
            val key = credential.accountName.trim().lowercase(Locale.ROOT)
            require(key.isNotEmpty()) { "Steam account scope must not be empty" }
            val opening =
                synchronized(lock) {
                    entries[key]?.let { entry ->
                        entry.users += 1
                        return lease(entry)
                    }
                    openings.getOrPut(key) {
                        startOpening(key, credential)
                    }.also { it.reservations += 1 }
                }
            val opened =
                try {
                    opening.result.await()
                } catch (error: Throwable) {
                    abandonReservation(key, opening)
                    throw error
                }
            val entry =
                synchronized(lock) {
                    opening.reservations -= 1
                    check(opening.reservations >= 0) { "SDK session opening reservation underflow" }
                    entries[key]?.also { existing ->
                        existing.users += 1
                    } ?: Entry(key, opened, users = 1).also { created ->
                        entries[key] = created
                    }.also {
                        if (opening.reservations == 0) openings.remove(key, opening)
                    }
                }
            return lease(entry)
        }

        private fun startOpening(
            key: String,
            credential: SteamContentCredential,
        ): Opening =
            Opening(scope.async { opener.open(credential) }, reservations = 0).also { opening ->
                scope.launch {
                    val opened = runCatching { opening.result.await() }.getOrNull()
                    synchronized(lock) {
                        if (opening.reservations == 0 && entries[key] == null) {
                            openings.remove(key, opening)
                            runCatching { opened?.close() }
                        }
                    }
                }
            }

        private fun abandonReservation(
            key: String,
            opening: Opening,
        ) {
            synchronized(lock) {
                opening.reservations -= 1
                check(opening.reservations >= 0) { "SDK session opening reservation underflow" }
                if (opening.reservations == 0 && opening.result.isCompleted) openings.remove(key, opening)
                closeIdleEntry(key)
            }
        }

        private fun lease(entry: Entry): Lease {
            val engineLease = engineLeases.acquire()
            return Lease.create(entry.session) {
                try {
                    release(entry)
                } finally {
                    engineLease.close()
                }
            }
        }

        private fun release(entry: Entry) {
            synchronized(lock) {
                val current = entries[entry.key]
                if (current !== entry) return
                check(entry.users > 0) { "SDK session lease released too many times" }
                entry.users -= 1
                closeIdleEntry(entry.key)
            }
        }

        private fun closeIdleEntry(key: String) {
            val entry = entries[key] ?: return
            val reserved = openings[key]?.reservations ?: 0
            if (entry.users != 0 || reserved != 0) return
            // Steam refuses overlapping account sessions. Keep the account lock until close completes,
            // then remove the entry so a subsequent acquire cannot open while this session is still live.
            runCatching { entry.session.close() }
            entries.remove(key, entry)
            openings.remove(key)
        }
    }
