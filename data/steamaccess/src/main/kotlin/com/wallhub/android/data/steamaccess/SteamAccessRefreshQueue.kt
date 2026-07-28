package com.wallhub.android.data.steamaccess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class SteamAccessRefreshQueue(
    scope: CoroutineScope,
    private val refreshAction: () -> Unit,
) {
    private val requests = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                requests.receive()
                refreshAction()
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
