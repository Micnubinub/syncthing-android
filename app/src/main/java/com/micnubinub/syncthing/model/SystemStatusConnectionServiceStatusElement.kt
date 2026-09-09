package com.micnubinub.syncthing.model

/**
 * REST API endpoint "/rest/system/status"
 * Part of JSON answer in field [SystemStatus.connectionServiceStatus]
 */
data class SystemStatusConnectionServiceStatusElement(
    var error: String? = null,
    var lanAddresses: MutableList<String>? = null,
    var wanAddresses: MutableList<String>? = null
)
