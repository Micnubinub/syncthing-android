package com.micnubinub.syncthing.service

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-wide event bus for run-condition changes, replacing LocalBroadcastManager.
 *
 * The channel is buffered, not conflated: a [RunConditionEvent.SyncTriggerFired] carries
 * data - whether the active time window begins now - and must not be replaced by an
 * [RunConditionEvent.UpdateShouldRunDecision] that happened to be emitted while the
 * collector was busy, nor the other way round. Conflating either dropped the trigger and
 * the time-schedule chain stopped re-arming.
 */
object RunConditionBus {
    private const val TAG = "RunConditionBus"

    private val channel = Channel<RunConditionEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    fun tryEmit(event: RunConditionEvent) {
        if (channel.trySend(event).isFailure) {
            Log.e(TAG, "tryEmit: Dropped $event, the collector is not keeping up")
        }
    }
}
