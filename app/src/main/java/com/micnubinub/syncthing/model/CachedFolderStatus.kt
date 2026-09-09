package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable

/**
 * Caches information frequently needed by the wrapper
 * to save expensive calls to Syncthing's REST API.
 * Vars in class do not correspond to JSON results.
 */
@Immutable
data class CachedFolderStatus(
    var completion: Double = 100.0,
    /**
     * Accessed by setters
     */
    // Example: "Test1.sync-conflict-20250514-140912-XF3FVSV", "subfolder/Test2.sync-conflict-20250514-140912-XF3FVSV"
    var discoveredConflictFiles: List<String?> = emptyList(),
    var lastItemFinishedAction: String = "",
    var lastItemFinishedItem: String = "",
    // Example: "2019-11-19T23:28:55.7479276+01:00"
    var lastItemFinishedTime: String = "",
    // Indicates if a folder - device pair has received updates from the remote side.
    var remoteIndexUpdated: Boolean = false,
    var paused: Boolean = false
)
