package com.micnubinub.syncthing.service

/**
 * Events delivered through [RunConditionBus], replacing the former
 * LocalBroadcastManager-based run-condition broadcasts.
 */
sealed class RunConditionEvent {
    /**
     * A scheduled sync trigger fired. [beginActiveWindow] carries the former
     * [RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW] broadcast extra.
     */
    data class SyncTriggerFired(val beginActiveWindow: Boolean) : RunConditionEvent()

    /**
     * Requests a re-evaluation of the should-run decision.
     */
    data object UpdateShouldRunDecision : RunConditionEvent()
}
