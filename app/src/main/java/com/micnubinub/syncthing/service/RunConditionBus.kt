package com.micnubinub.syncthing.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-wide event bus for run-condition changes, replacing LocalBroadcastManager.
 *
 * Uses a conflated channel so the latest event is always retained and delivered
 * to the collector; no event is silently dropped under back-pressure.
 */
object RunConditionBus {
    private val channel = Channel<RunConditionEvent>(Channel.CONFLATED)
    val events = channel.receiveAsFlow()

    fun tryEmit(event: RunConditionEvent) {
        channel.trySend(event)
    }
}
