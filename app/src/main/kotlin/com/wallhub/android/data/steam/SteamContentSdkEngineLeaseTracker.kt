package com.wallhub.android.data.steam

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps retired SDK engines alive only while content leases may still own their task or range handles. */
@Singleton
internal class SteamContentSdkEngineLeaseTracker
    @Inject
    constructor() {
        private val lock = Any()
        private var leases = 0
        private val idleActions = mutableListOf<() -> Unit>()

        fun acquire(): Closeable {
            synchronized(lock) { leases += 1 }
            val closed = AtomicBoolean(false)
            return Closeable {
                if (!closed.compareAndSet(false, true)) return@Closeable
                val actions =
                    synchronized(lock) {
                        check(leases > 0) { "Steam Content SDK engine lease underflow" }
                        leases -= 1
                        if (leases != 0) emptyList() else idleActions.toList().also { idleActions.clear() }
                    }
                actions.forEach { action -> runCatching(action) }
            }
        }

        fun whenIdle(action: () -> Unit) {
            val runNow =
                synchronized(lock) {
                    if (leases == 0) true else false.also { idleActions += action }
                }
            if (runNow) action()
        }
    }
