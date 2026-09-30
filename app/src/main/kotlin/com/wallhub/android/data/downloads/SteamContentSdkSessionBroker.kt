package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

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

        private val lock = Any()
        private val entries = mutableMapOf<String, Entry>()
        private val openings = mutableMapOf<String, CompletableDeferred<Unit>>()

        suspend fun acquire(credential: SteamContentCredential): Lease {
            val key = credential.accountName.trim().lowercase(Locale.ROOT)
            require(key.isNotEmpty()) { "Steam account scope must not be empty" }
            while (true) {
                var existing: Entry? = null
                var waiting: CompletableDeferred<Unit>? = null
                var producer: CompletableDeferred<Unit>? = null
                synchronized(lock) {
                    existing = entries[key]
                    if (existing != null) {
                        existing!!.users += 1
                    } else {
                        waiting = openings[key]
                        if (waiting == null) {
                            producer = CompletableDeferred()
                            openings[key] = producer!!
                        }
                    }
                }
                existing?.let { return lease(it) }
                waiting?.let {
                    it.await()
                    continue
                }

                val signal = requireNotNull(producer)
                try {
                    val opened = opener.open(credential)
                    val entry = Entry(key, opened, users = 1)
                    synchronized(lock) {
                        openings.remove(key, signal)
                        entries[key] = entry
                    }
                    signal.complete(Unit)
                    return lease(entry)
                } catch (error: Throwable) {
                    synchronized(lock) { openings.remove(key, signal) }
                    signal.completeExceptionally(error)
                    throw error
                }
            }
        }

        private fun lease(entry: Entry): Lease = Lease.create(entry.session) { release(entry) }

        private fun release(entry: Entry) {
            val close =
                synchronized(lock) {
                    val current = entries[entry.key]
                    if (current !== entry) return
                    check(entry.users > 0) { "SDK session lease released too many times" }
                    entry.users -= 1
                    if (entry.users == 0) {
                        entries.remove(entry.key)
                        true
                    } else {
                        false
                    }
                }
            if (close) runCatching { entry.session.close() }
        }
    }
