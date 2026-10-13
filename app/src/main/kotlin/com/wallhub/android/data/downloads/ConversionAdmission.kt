package com.wallhub.android.data.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap

/**
 * Races conversion admission against the same process-local signal published after the durable
 * cancellation intent is stored. Cancelling the admission child removes it from whichever gate it
 * is awaiting; no polling or second recovery worker is needed.
 */
internal object ConversionAdmission {
    sealed interface Outcome<out T>

    data class Admitted<T>(val value: T) : Outcome<T>

    data object Cancelled : Outcome<Nothing>

    suspend fun <T> await(
        taskId: String,
        block: suspend () -> T,
    ): Outcome<T> =
        coroutineScope {
            val admitted =
                async(start = CoroutineStart.UNDISPATCHED) {
                    Admitted(block())
                }
            val cancelled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    FormalWorkshopConversionCancellation.awaitRequest(taskId)
                    Cancelled
                }
            select {
                cancelled.onAwait {
                    admitted.cancelAndJoin()
                    Cancelled
                }
                admitted.onAwait { result ->
                    cancelled.cancelAndJoin()
                    result
                }
            }
        }
}

internal object FormalWorkshopConversionCancellation {
    private enum class State {
        ACTIVE,
        REQUEST_PERSISTING,
        CANCEL_REQUESTED,
        FINALIZING,
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()

    fun start(taskId: String) {
        state(taskId)
    }

    suspend fun beginRequest(taskId: String): Boolean {
        val state = state(taskId)
        while (true) {
            when (val current = state.value) {
                State.REQUEST_PERSISTING -> state.first { it != State.REQUEST_PERSISTING }
                State.CANCEL_REQUESTED,
                State.FINALIZING,
                -> return false
                State.ACTIVE -> if (state.compareAndSet(current, State.REQUEST_PERSISTING)) return true
            }
        }
    }

    fun completeRequest(taskId: String) {
        states[taskId]?.compareAndSet(State.REQUEST_PERSISTING, State.CANCEL_REQUESTED)
    }

    fun abortRequest(taskId: String) {
        states[taskId]?.compareAndSet(State.REQUEST_PERSISTING, State.ACTIVE)
    }

    fun check(taskId: String) {
        if (states[taskId]?.value == State.CANCEL_REQUESTED) {
            throw CancellationException("Workshop conversion was cancelled")
        }
    }

    suspend fun awaitRequest(taskId: String) {
        state(taskId).first { it == State.CANCEL_REQUESTED }
    }

    suspend fun beginFinalization(taskId: String) {
        currentCoroutineContext().ensureActive()
        val state = state(taskId)
        while (true) {
            when (val current = state.value) {
                State.CANCEL_REQUESTED -> throw CancellationException("Workshop conversion was cancelled")
                State.REQUEST_PERSISTING -> state.first { it != State.REQUEST_PERSISTING }
                State.FINALIZING -> return
                State.ACTIVE -> if (state.compareAndSet(current, State.FINALIZING)) return
            }
        }
    }

    suspend fun claimCancellation(taskId: String): Boolean {
        val state = state(taskId)
        while (true) {
            when (val current = state.value) {
                State.CANCEL_REQUESTED -> return true
                State.REQUEST_PERSISTING -> state.first { it != State.REQUEST_PERSISTING }
                State.FINALIZING -> return false
                State.ACTIVE -> if (state.compareAndSet(current, State.FINALIZING)) return false
            }
        }
    }

    fun isRequested(taskId: String): Boolean = states[taskId]?.value == State.CANCEL_REQUESTED

    fun clear(taskId: String) {
        states.remove(taskId)
    }

    private fun state(taskId: String): MutableStateFlow<State> =
        states.getOrPut(taskId) { MutableStateFlow(State.ACTIVE) }
}
