package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The single application-level owner of account-scoped SDK content-session generations.
 *
 * A lost generation is retired immediately: new callers wait for all of its leases to drain before one
 * replacement is opened. This preserves Steam's no-overlapping-account-sessions rule while coalescing concurrent
 * loss reports and replacement callers onto one epoch.
 */
@Singleton
internal class SteamContentSdkSessionBroker
    @Inject
    constructor(
        private val opener: SteamContentSdkSessionOpener,
    ) {
        private class Entry(
            val key: String,
            val generation: Long,
            val session: SteamContentSdkSession,
            var users: Int,
            var retired: Boolean = false,
            val drained: CompletableDeferred<Unit> = CompletableDeferred(),
        )

        internal class Lease private constructor(
            val session: SteamContentSdkSession,
            val generation: Long,
            private val reportLoss: () -> Unit,
            private val release: () -> Unit,
        ) : Closeable {
            private val closed = AtomicBoolean(false)
            private val lossReported = AtomicBoolean(false)

            /** Retires this account generation. Safe to call repeatedly or concurrently with another lease. */
            fun reportSessionLoss() {
                if (!closed.get() && lossReported.compareAndSet(false, true)) reportLoss()
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) release()
            }

            companion object {
                fun create(
                    session: SteamContentSdkSession,
                    generation: Long,
                    reportLoss: () -> Unit,
                    release: () -> Unit,
                ): Lease = Lease(session, generation, reportLoss, release)
            }
        }

        private data class Opened(val session: SteamContentSdkSession, val generation: Long)
        private class Opening(val result: Deferred<Opened>, var reservations: Int)
        private sealed interface Acquisition {
            data class Existing(val entry: Entry) : Acquisition
            data class OpeningSession(val opening: Opening) : Acquisition
            data class AwaitDrain(val drained: Deferred<Unit>) : Acquisition
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private val entries = mutableMapOf<String, Entry>()
        private val openings = mutableMapOf<String, Opening>()
        private val generations = mutableMapOf<String, Long>()

        suspend fun acquire(credential: SteamContentCredential): Lease {
            val key = credential.accountName.trim().lowercase(Locale.ROOT)
            require(key.isNotEmpty()) { "Steam account scope must not be empty" }
            while (true) {
                val acquisition = synchronized(lock) {
                    val entry = entries[key]
                    when {
                        entry != null && entry.retired -> Acquisition.AwaitDrain(entry.drained)
                        entry != null -> {
                            entry.users += 1
                            Acquisition.Existing(entry)
                        }
                        else -> Acquisition.OpeningSession(
                            openings.getOrPut(key) { startOpening(key, credential) }.also { it.reservations += 1 },
                        )
                    }
                }
                when (acquisition) {
                    is Acquisition.Existing -> return lease(acquisition.entry)
                    is Acquisition.AwaitDrain -> {
                        acquisition.drained.await()
                        continue
                    }
                    is Acquisition.OpeningSession -> {
                        val opening = acquisition.opening
                        val opened = try {
                            opening.result.await()
                        } catch (error: Throwable) {
                            abandonReservation(key, opening)
                            throw error
                        }
                        val entry = synchronized(lock) {
                            opening.reservations -= 1
                            check(opening.reservations >= 0) { "SDK session opening reservation underflow" }
                            if (opening.reservations == 0) openings.remove(key, opening)
                            val existing = entries[key]
                            when {
                                existing?.retired == true -> {
                                    // This waiter reserved the now-retired opening before another waiter reported
                                    // loss. Release that reservation, let the old epoch drain, then acquire again.
                                    closeIdleEntry(key)
                                    null
                                }
                                existing != null -> existing.also { it.users += 1 }
                                else -> Entry(key, opened.generation, opened.session, users = 1).also {
                                    entries[key] = it
                                }
                            }
                        }
                        if (entry == null) continue
                        return lease(entry)
                    }
                }
            }
        }

        private fun startOpening(key: String, credential: SteamContentCredential): Opening {
            val generation = (generations[key] ?: 0L) + 1L
            generations[key] = generation
            return Opening(scope.async { Opened(opener.open(credential), generation) }, reservations = 0).also { opening ->
                scope.launch {
                    val opened = runCatching { opening.result.await() }.getOrNull()
                    synchronized(lock) {
                        if (
                            openings[key] === opening &&
                            opening.reservations == 0 &&
                            entries[key] == null
                        ) {
                            openings.remove(key, opening)
                            runCatching { opened?.session?.close() }
                        }
                    }
                }
            }
        }

        private fun abandonReservation(key: String, opening: Opening) {
            synchronized(lock) {
                opening.reservations -= 1
                check(opening.reservations >= 0) { "SDK session opening reservation underflow" }
                if (opening.reservations == 0 && opening.result.isCompleted) openings.remove(key, opening)
                closeIdleEntry(key)
            }
        }

        private fun lease(entry: Entry): Lease =
            Lease.create(entry.session, entry.generation, { retire(entry) }, { release(entry) })

        private fun retire(entry: Entry) {
            synchronized(lock) {
                if (entries[entry.key] !== entry || entry.retired) return
                entry.retired = true
                closeIdleEntry(entry.key)
            }
        }

        private fun release(entry: Entry) {
            synchronized(lock) {
                if (entries[entry.key] !== entry) return
                check(entry.users > 0) { "SDK session lease released too many times" }
                entry.users -= 1
                closeIdleEntry(entry.key)
            }
        }

        private fun closeIdleEntry(key: String) {
            val entry = entries[key] ?: return
            val reserved = openings[key]?.reservations ?: 0
            if (entry.users != 0 || reserved != 0) return
            runCatching { entry.session.close() }
            entries.remove(key, entry)
            entry.drained.complete(Unit)
        }
    }
