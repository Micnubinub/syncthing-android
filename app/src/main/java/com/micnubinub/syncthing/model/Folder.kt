package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable
import com.micnubinub.syncthing.service.Constants
import java.io.Serializable

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/master/lib/config
 * - https://github.com/syncthing/syncthing/blob/master/lib/config/folderconfiguration.go
 */
@Immutable
data class Folder(
    // Folder Configuration
    var group: String = "",
    var id: String? = null,
    var label: String = "",
    var filesystemType: String = "basic",
    var path: String? = null,
    var type: String = Constants.FOLDER_TYPE_SEND_RECEIVE,
    var fsWatcherEnabled: Boolean = true,
    var fsWatcherDelayS: Float = 10f,
    val devices: MutableList<SharedWithDevice> = ArrayList(),

    /**
     * Folder rescan interval defaults to 3600s as it is the default in
     * syncthing when the file watcher is enabled and a new folder is created.
     */
    var rescanIntervalS: Int = 3600,
    var ignorePerms: Boolean = true,
    var autoNormalize: Boolean = true,
    var minDiskFree: MinDiskFree? = null,
    var versioning: Versioning? = null,
    var copiers: Int = 0,
    var pullerMaxPendingKiB: Int = 0,
    var hashers: Int = 0,
    var order: String = "random",
    var ignoreDelete: Boolean = false,
    var scanProgressIntervalS: Int = 0,
    var pullerPauseS: Int = 0,
    var maxConflicts: Int = 10,
    var disableSparseFiles: Boolean = false,
    var paused: Boolean = false,
    var markerName: String = Constants.FILENAME_STFOLDER,
    var copyOwnershipFromParent: Boolean = false,
    var modTimeWindowS: Int = 0,
    var blockPullOrder: String = "standard",
    var disableFsync: Boolean = false,
    var maxConcurrentWrites: Int = 0,
    var copyRangeMethod: String = "standard",
    var caseSensitiveFS: Boolean = false,
    var syncOwnership: Boolean = false,
    var sendOwnership: Boolean = false,
    var syncXattrs: Boolean = false,
    var sendXattrs: Boolean = false,
    var blockIndexing: Boolean = true,
    var invalid: String? = null
) {
    @Immutable
    data class Versioning(
        var type: String? = null,
        var cleanupIntervalS: Int = 0,
        var params: MutableMap<String, String?> = HashMap(),
        // Since v1.14.0
        var fsPath: String? = null,
        var fsType: String? = null // default: "basic"
    ) : Serializable

    @Immutable
    data class MinDiskFree(
        var value: Float = 1f,
        var unit: String = "%"
    )

    fun addDevice(device: Device) {
        // Avoid {@link ConfigXml#updateDevice} creating two list entries with the same device ID.
        removeDevice(device.deviceID)

        val d = SharedWithDevice()
        d.deviceID = device.deviceID
        d.introducedBy = device.introducedBy
        devices.add(d)
    }

    fun addDevice(sharedWithDevice: SharedWithDevice) {
        // Avoid {@link ConfigXml#updateDevice} creating two list entries with the same device ID.
        removeDevice(sharedWithDevice.deviceID)

        val d = SharedWithDevice()
        d.deviceID = sharedWithDevice.deviceID
        d.encryptionPassword = sharedWithDevice.encryptionPassword
        d.introducedBy = sharedWithDevice.introducedBy
        devices.add(d)
    }

    val deviceCount: Int
        /**
         * Note: This is expected to return "1" if a folder is not shared with any
         * other device. Syncthing's config will list ourself as the only device
         * sub node which is associated with the folder. This will return >= 2
         * if the folder is shared with other devices.
         */
        get() {
            return devices.size
        }

    fun getDevice(deviceId: String?): SharedWithDevice? {
        devices.let {
            for (d in devices) {
                if (d.deviceID == deviceId) {
                    return d
                }
            }
        }

        return null
    }

    fun removeDevice(deviceId: String?) {
        val it = devices.iterator()
        while (it.hasNext() == true) {
            val currentId = it.next().deviceID
            if (currentId == deviceId) {
                it.remove()
            }
        }
    }

    override fun toString(): String {
        return (if (label.isNullOrEmpty())
            (if (id.isNullOrEmpty()) "" else id)
        else
            label) ?: ""
    }
}
