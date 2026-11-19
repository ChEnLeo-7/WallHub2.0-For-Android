package com.wallhub.android.feature.setup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the setup page's one connection-check task.
 *
 * A tap is a statement about the egress the user is looking at now, so a newer tap supersedes the running
 * pass rather than joining it: two passes would each open six requests and the slower one could still
 * publish a snapshot for a superseded egress. Leaving the page cancels the pass outright.
 */
internal class SetupNetworkCheckCoordinator(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var pass = 0L

    val isRunning: Boolean
        get() = job?.isActive == true

    fun launch(block: suspend () -> Unit) {
        val current: Long
        synchronized(this) {
            job?.cancel()
            current = ++pass
        }
        val started =
            scope.launch {
                try {
                    block()
                } finally {
                    // Only the pass that still owns the slot may clear it; a superseded one must not.
                    synchronized(this@SetupNetworkCheckCoordinator) {
                        if (pass == current) job = null
                    }
                }
            }
        synchronized(this) {
            if (pass == current) job = started else started.cancel()
        }
    }

    fun cancel() {
        synchronized(this) {
            pass += 1
            job?.cancel()
            job = null
        }
    }
}
