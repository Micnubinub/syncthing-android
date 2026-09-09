package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable

/**
 * REST API endpoint "/rest/events/disk"
 */
@Immutable
data class DiskEventData(
    // action = {"added", "deleted", "modified"}
    var action: String = "",
    var folder: String = "",
    var folderID: String = "",
    var label: String = "",
    var modifiedBy: String = "",
    var path: String = "",
    // type = {"file", "dir"}
    var type: String = ""
)
