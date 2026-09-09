package com.micnubinub.syncthing.model

/**
 * REST API endpoint "/rest/system/status"
 * Part of JSON answer in field [SystemStatus.lastDialStatus]
 */
data class SystemStatusLastDialStatusElement(
    // Example: "dial tcp4 192.168.5.1:22000: i/o timeout"
    var error: String? = null,

    // Example: "2019-09-21T09:10:35Z"
    var `when`: String? = null
)
