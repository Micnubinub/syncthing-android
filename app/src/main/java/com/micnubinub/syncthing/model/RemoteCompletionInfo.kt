package com.micnubinub.syncthing.model

/**
 * Caches information frequently needed by the wrapper
 * to save expensive calls to Syncthing's REST API.
 * Vars in class do not correspond to JSON results.
 */
data class RemoteCompletionInfo(
    var completion: Double = 100.0,
    var needBytes: Double = 0.0
)
