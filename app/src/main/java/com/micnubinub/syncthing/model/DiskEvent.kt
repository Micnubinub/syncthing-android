package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable

/**
 * REST API endpoint "/rest/events/disk"
 */
@Immutable
data class DiskEvent(
    var id: Long = 0,
    var globalID: Long = 0,
    var time: String = "",
    // type = {"LocalChangeDetected", "RemoteChangeDetected"}
    var type: String = "",
    var data: DiskEventData = DiskEventData()
)
