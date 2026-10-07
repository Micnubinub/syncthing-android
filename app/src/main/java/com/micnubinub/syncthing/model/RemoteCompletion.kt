package com.micnubinub.syncthing.model

import android.util.Log
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import java.lang.reflect.Type
import java.util.AbstractMap
import kotlin.math.floor

/**
 * This class caches remote folder and device synchronization
 * completion indicators defined in [RemoteCompletionInfo]
 * according to syncthing's REST "/completion" JSON result schema.
 * Completion model of syncthing's web UI is completion[deviceId][folderId]
 */
class RemoteCompletion(enableVerboseLog: Boolean) {
    private val gson = Gson()
    private val ENABLE_DEBUG_LOG = false
    private var ENABLE_VERBOSE_LOG = false

    var deviceFolderMap: HashMap<String?, MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>> =
        HashMap<String?, MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>>()

    /**
     * Object that must be locked upon accessing mDeviceFolderMap.
     */
    private val deviceFolderMapLock = Any()

    init {
        ENABLE_VERBOSE_LOG = enableVerboseLog
    }

    /**
     * Removes a folder from the cache model.
     */
    private fun removeFolder(folderId: String?) {
        synchronized(deviceFolderMapLock) {
            // Remove the folder from every device's map, not just the first
            // one that happens to contain it.
            for (folderMapEntry in deviceFolderMap.values) {
                val folderMap = folderMapEntry.value
                folderMap.remove(folderId)
            }
        }
    }

    /**
     * Updates device and folder information in the cache model
     * after a config update.
     */
    fun updateFromConfig(newDevices: List<Device>, newFolders: List<Folder>) {
        synchronized(deviceFolderMapLock) {
            // Handle devices that were removed from the config.
            val removedDevices: MutableList<String?> = ArrayList<String?>()
            for (deviceId in deviceFolderMap.keys) {
                var deviceFound = false
                for (device in newDevices) {
                    if (device.deviceID == deviceId) {
                        deviceFound = true
                        break
                    }
                }
                if (!deviceFound) {
                    removedDevices.add(deviceId)
                }
            }
            for (deviceId in removedDevices) {
                LogV("updateFromConfig: Remove device '" + getShortenedDeviceId(deviceId) + "' from cache model")
                deviceFolderMap.remove(deviceId)
            }

            // Handle devices that were added to the config.
            for (device in newDevices) {
                if (!deviceFolderMap.containsKey(device.deviceID)) {
                    LogV("updateFromConfig: Add device '${getShortenedDeviceId(device.deviceID)}' to cache model")
                    (AbstractMap.SimpleEntry(
                        Connection() as Any,
                        HashMap<String, RemoteCompletionInfo>()
                    ) as? MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>)?.let {
                        deviceFolderMap[device.deviceID] = it
                    }
                }
            }

            // Handle folders that were removed from the config.
            val removedFolders: MutableList<String?> = ArrayList<String?>()

            for (device in deviceFolderMap.entries) {
                //                            Map.Entry   HashMap    String
                for (folderId in device.value.value.keys) {
                    var folderFound = false
                    for (folder in newFolders) {
                        if (folder.id == folderId) {
                            folderFound = true
                            break
                        }
                    }
                    if (!folderFound) {
                        removedFolders.add(folderId)
                    }
                }
            }
            for (folderId in removedFolders) {
                LogV("updateFromConfig: Remove folder '$folderId' from cache model")
                removeFolder(folderId)
            }

            // Handle folders that were added to the config.
            for (folder in newFolders) {
                for (device in newDevices) {
                    folder.getDevice(device.deviceID)?.let {
                        // folder is shared with device.
                        deviceFolderMap[device.deviceID]?.value?.let {
                            if (!it.containsKey(folder.id)) {
                                LogV(
                                    "updateFromConfig: Add folder '" + folder.id +
                                            "' shared with device '" + getShortenedDeviceId(device.deviceID) + "' to cache model."
                                )
                                it[folder.id] = RemoteCompletionInfo()
                            }
                        }
                    }
                }
            }
        }
    }

    val totalDeviceCompletion: Int
        /**
         * Calculates remote device sync completion percentage across all connected devices.
         * Returns "-1" if sync completion is not applicable.
         */
        get() {
            synchronized(deviceFolderMapLock) {
                var connectedDeviceCount = 0
                var folderCount = 0
                var sumCompletion = 0.0
                for (device in deviceFolderMap.values) {
                    if (device.key?.connected != true) {
                        continue
                    }
                    connectedDeviceCount++

                    //                                                 HashMap   RemoteCompletionInfo
                    for (completionInfo in device.value.values) {
                        var folderCompletion = completionInfo?.completion ?: 100.0
                        if (folderCompletion < 0) {
                            folderCompletion = 0.0
                        } else if (folderCompletion > 100) {
                            folderCompletion = 100.0
                        }

                        // Syncthing's WebUI considers remote folders with 0% and 100% completion as up-to-date.
                        if (folderCompletion != 0.0 && folderCompletion != 100.0) {
                            sumCompletion += folderCompletion
                            folderCount++
                        }
                    }
                }
                if (connectedDeviceCount == 0) {
                    return -1
                }
                if (folderCount == 0) {
                    return 100
                }
                var totalDeviceCompletion = floor(sumCompletion / folderCount).toInt()

                if (totalDeviceCompletion < 0) {
                    totalDeviceCompletion = 0
                } else if (totalDeviceCompletion > 100) {
                    totalDeviceCompletion = 100
                }
                return totalDeviceCompletion
            }
        }

    /**
     * Calculates remote device sync completion percentage across all folders
     * shared with the device.
     */
    fun getDeviceCompletion(deviceId: String?): Int {
        synchronized(deviceFolderMapLock) {
            if (!deviceFolderMap.containsKey(deviceId)) {
                LogV("getDeviceCompletion: Cache miss for deviceId=[$deviceId]")
                return 100
            }
            var folderCount = 0
            var sumCompletion = 0.0
            deviceFolderMap[deviceId]?.value?.let { folderMap ->
                for (folder in folderMap.entries) {
                    var folderCompletion = folder.value?.completion ?: 100.0
                    if (folderCompletion < 0) {
                        folderCompletion = 0.0
                    } else if (folderCompletion > 100) {
                        folderCompletion = 100.0
                    }

                    // Syncthing's WebUI considers remote folders with 0% and 100% completion as up-to-date.
                    if (folderCompletion != 0.0 && folderCompletion != 100.0) {
                        sumCompletion += folderCompletion
                        folderCount++
                    }
                }
            }
            if (folderCount == 0) {
                return 100
            }
            var deviceCompletion = floor(sumCompletion / folderCount).toInt()
            if (deviceCompletion < 0) {
                deviceCompletion = 0
            } else if (deviceCompletion > 100) {
                deviceCompletion = 100
            }
            return deviceCompletion
        }
    }

    fun getDeviceNeedBytes(deviceId: String?): Double {
        synchronized(deviceFolderMapLock) {
            if (!deviceFolderMap.containsKey(deviceId)) {
                LogV("getDeviceNeedBytes: Cache miss for deviceId=[$deviceId]")
                return 0.0
            }
            var sumNeedBytes = 0.0
            deviceFolderMap[deviceId]?.value?.let { folderMap ->
                for (folder in folderMap.entries) {
                    sumNeedBytes += folder.value?.needBytes ?: 0.0
                }
            }
            return sumNeedBytes
        }
    }

    /**
     * Set completionInfo within the completion[deviceId][folderId] model.
     */
    fun setCompletionInfo(
        deviceId: String?, folderId: String?,
        completionInfo: RemoteCompletionInfo
    ) {
        synchronized(deviceFolderMapLock) {
            // Add device parent node if it does not exist.
            if (!deviceFolderMap.containsKey(deviceId)) {
                deviceFolderMap[deviceId] = AbstractMap.SimpleEntry<Any, Any?>(
                    Connection(),
                    HashMap<String?, RemoteCompletionInfo?>()
                ) as MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>
            }
            LogV(
                "setCompletionInfo: Storing " + completionInfo.completion + "% for folder \"" +
                        folderId + "\" at device \"" +
                        getShortenedDeviceId(deviceId) + "\"."
            )
            // Add folder or update existing folder entry.
            deviceFolderMap[deviceId]?.value?.put(folderId, completionInfo)
        }
    }

    /**
     * Returns remote device status.
     */
    fun getDeviceStatus(deviceId: String?): Connection? {
        synchronized(deviceFolderMapLock) {
            if (!deviceFolderMap.containsKey(deviceId)) {
                return Connection()
            }
            //                                      Map.Entry     Connection
            val connection = deviceFolderMap[deviceId]?.key
            return deepCopy<Connection?>(connection, object : TypeToken<Connection>() {}.type)
        }
    }

    val onlineDeviceCount: Int
        get() {
            synchronized(deviceFolderMapLock) {
                var onlineDeviceCount = 0
                for (device in deviceFolderMap.values) {
                    if (device.key?.connected == true) {
                        onlineDeviceCount++
                    }
                }
                return onlineDeviceCount
            }
        }

    /**
     * Store remote device status for later when we need info for the UI.
     */
    fun setDeviceStatus(
        deviceId: String?,
        connection: Connection?
    ) {
        synchronized(deviceFolderMapLock) {
            // Add device parent node if it does not exist.
            if (!deviceFolderMap.containsKey(deviceId)) {
                deviceFolderMap[deviceId] = AbstractMap.SimpleEntry<Any?, Any?>(
                    Connection(),
                    HashMap<String?, RemoteCompletionInfo?>()
                ) as MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>
            }

            if (ENABLE_DEBUG_LOG) {
                Log.d(
                    TAG, "setDeviceStatus: deviceId=\"" + deviceId + "\"" +
                            ", connected=" + connection?.connected.toString() +
                            ", paused=" + connection?.paused.toString()
                )
            }

            // Update device status information.
            val updatedEntry: MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>> =
                AbstractMap.SimpleEntry<Any?, Any?>(
                    deepCopy<Connection?>(connection, object : TypeToken<Connection>() {}.type),
                    deepCopy<HashMap<String?, RemoteCompletionInfo?>?>(
                        deviceFolderMap[deviceId]?.value,
                        object : TypeToken<HashMap<String?, RemoteCompletionInfo?>>() {}.type
                    )
                ) as MutableMap.MutableEntry<Connection?, HashMap<String?, RemoteCompletionInfo?>>

            deviceFolderMap.put(deviceId, updatedEntry)
        }
    }

    /**
     * Returns the first characters of the device ID for logging purposes.
     */
    fun getShortenedDeviceId(deviceId: String?): String? {
        return if (deviceId.isNullOrEmpty()) "" else deviceId.take(7)
    }

    /**
     * Drops every cached device/folder entry. The cache is repopulated lazily on
     * the next query or by the next event.
     *
     * Callers outside this class must go through here rather than clearing
     * [deviceFolderMap] directly, otherwise they race the event-driven writes
     * that hold [deviceFolderMapLock].
     */
    fun clear() {
        synchronized(deviceFolderMapLock) {
            deviceFolderMap.clear()
        }
    }

    /**
     * Returns a deep copy of object.
     * 
     * This method uses Gson and only works with objects that can be converted with Gson.
     */
    private fun <T> deepCopy(obj: T?, type: Type): T? {
        return gson.fromJson<T?>(gson.toJson(obj, type), type)
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "RemoteCompletion"
    }
}
