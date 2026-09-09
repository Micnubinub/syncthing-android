package com.micnubinub.syncthing.model

import android.util.Log
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import java.lang.reflect.Type
import java.util.AbstractMap
import kotlin.math.floor

/**
 * This class caches local folder synchronization
 * completion indicators defined in [CachedFolderStatus]
 * according to Syncthing's "FolderSummary" event JSON result schema.
 * Completion model of Syncthing's web UI is completion[folderId]
 */
typealias FolderStatusMap = MutableMap.MutableEntry<FolderStatus, CachedFolderStatus?>
typealias FolderStatusMapNonNull = MutableMap.MutableEntry<FolderStatus, CachedFolderStatus>

class LocalCompletion(enableVerboseLog: Boolean) {
    private val gson = Gson()
    private var ENABLE_VERBOSE_LOG = false

    val folderMap: HashMap<String, FolderStatusMap?> = HashMap<String, FolderStatusMap?>()

    /**
     * Object that must be locked upon accessing mFolderMapLock.
     */
    private val mFolderMapLock = Any()

    init {
        ENABLE_VERBOSE_LOG = enableVerboseLog
    }

    /**
     * Updates folder information in the cache model
     * after a config update.
     */
    fun updateFromConfig(newFolders: List<Folder>) {
        synchronized(mFolderMapLock) {
            // Handle folders that were removed from the config.
            val removedFolders = ArrayList<String>()
            var folderFound: Boolean
            for (folderId in folderMap.keys) {
                folderFound = false
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
            for (folderId in removedFolders) {
                LogV("updateFromConfig: Remove folder '$folderId' from cache model")
                if (folderMap.containsKey(folderId)) {
                    folderMap.remove(folderId)
                }
            }

            // Handle folders that were added to the config.
            for (folder in newFolders) {
                folder.id?.let { id ->
                    if (!folderMap.containsKey(id)) {
                        LogV("updateFromConfig: Add folder '" + id + "' to cache model.")
                        folderMap[id] = AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus?>(
                            FolderStatus(),
                            CachedFolderStatus()
                        )
                    }
                }

            }
        }
    }

    val totalFolderCompletion: Int
        /**
         * Calculates local folder sync completion percentage across all folders.
         */
        get() {
            synchronized(mFolderMapLock) {
                var folderCount = 0
                var sumCompletion = 0.0
                for (folder in folderMap.entries) {
                    folder.value?.value?.let { cachedFolderStatus ->
// Filter invalid percentage values we may have got from the REST API.
                        if (cachedFolderStatus.completion < 0) {
                            cachedFolderStatus.completion = 0.0
                        } else if (cachedFolderStatus.completion > 100) {
                            cachedFolderStatus.completion = 100.0
                        }

                        if (!cachedFolderStatus.paused &&
                            cachedFolderStatus.completion != 100.0
                        ) {
                            sumCompletion += cachedFolderStatus.completion
                            folderCount++
                        }
                    }

                }
                if (folderCount == 0) {
                    return 100
                }
                var totalFolderCompletion =
                    floor(sumCompletion / folderCount).toInt()
                if (totalFolderCompletion < 0) {
                    totalFolderCompletion = 0
                } else if (totalFolderCompletion > 100) {
                    totalFolderCompletion = 100
                }
                return totalFolderCompletion
            }
        }

    /**
     * Returns local folder status including completion info.
     */
    fun getFolderStatus(folderId: String?): FolderStatusMapNonNull {
        synchronized(mFolderMapLock) {
            if (!folderMap.containsKey(folderId)) {
                return AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus>(
                    FolderStatus(),
                    CachedFolderStatus()
                )
            }
            val folderEntry = folderMap.get(folderId)
            val key = deepCopy<FolderStatus?>(
                folderEntry?.key,
                object : TypeToken<FolderStatus>() {}.type
            ) ?: FolderStatus()
            val value = deepCopy<CachedFolderStatus?>(
                folderEntry?.value,
                object : TypeToken<CachedFolderStatus>() {}.type
            ) ?: CachedFolderStatus()
            return AbstractMap.SimpleEntry(key, value)
        }
    }

    /**
     * Store folderStatus for later when we need info for the UI.
     * Calculate cachedFolderStatus within the completion[folderId] model.
     */
    fun setFolderStatus(
        folderId: String?,
        folderPaused: Boolean,
        folderStatus: FolderStatus
    ) {
        synchronized(mFolderMapLock) {
            val cacheEntry = getFolderStatus(folderId)
            val cachedFolderStatus = cacheEntry.value
            cachedFolderStatus.paused = folderPaused
            if (folderStatus.globalBytes == 0L ||
                (folderStatus.inSyncBytes > folderStatus.globalBytes)
            ) {
                cachedFolderStatus.completion = 100.0
            } else {
                cachedFolderStatus.completion =
                    floor((folderStatus.inSyncBytes.toDouble() / folderStatus.globalBytes) * 100).toInt()
                        .toDouble()
            }
            if (folderStatus.state == "idle") {
                cachedFolderStatus.completion = 100.0
            }
            if (ENABLE_VERBOSE_LOG) {
                LogV(
                    "setFolderStatus: folderId=\"" + folderId + "\"" +
                            ", state=\"" + folderStatus.state + "\"" +
                            ", paused=" + cachedFolderStatus.paused.toString() +
                            ", completion=" + cachedFolderStatus.completion.toInt() + "%"
                )
            }

            // Add folder or update existing folder entry.
            folderId?.let {
                folderMap.put(
                    folderId,
                    AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus?>(
                        folderStatus,
                        cachedFolderStatus
                    )
                )
            }

        }
    }

    fun setFolderStatus(
        folderId: String?,
        folderStatus: FolderStatus
    ) {
        synchronized(mFolderMapLock) {
            // Persist cachedFolderStatus.paused from the previous entry.
            val cacheEntry = getFolderStatus(folderId)
            setFolderStatus(folderId, cacheEntry.value.paused, folderStatus)
        }
    }

    /**
     * Setters of additionally stored information
     * e.g. "ItemFinished" event details arriving through [EventProcessor] > [RestApi]
     */
    fun setLastItemFinished(
        folderId: String,
        lastItemFinishedAction: String,
        lastItemFinishedItem: String,
        lastItemFinishedTime: String
    ) {
        synchronized(mFolderMapLock) {
            val cacheEntry = getFolderStatus(folderId)
            val cachedFolderStatus = cacheEntry.value
            cachedFolderStatus.lastItemFinishedAction = lastItemFinishedAction
            cachedFolderStatus.lastItemFinishedItem = lastItemFinishedItem
            cachedFolderStatus.lastItemFinishedTime = lastItemFinishedTime

            // Add folder or update existing folder entry.
            folderMap.put(
                folderId,
                AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus?>(
                    cacheEntry.key,
                    cachedFolderStatus
                )
            )
        }
    }

    fun setRemoteIndexUpdated(
        folderId: String?,
        remoteIndexUpdated: Boolean
    ) {
        synchronized(mFolderMapLock) {
            val cacheEntry = getFolderStatus(folderId)
            val cachedFolderStatus = cacheEntry.value
            cachedFolderStatus.remoteIndexUpdated = remoteIndexUpdated

            // Add folder or update existing folder entry.
            folderId?.let {
                folderMap.put(
                    folderId,
                    AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus?>(
                        cacheEntry.key,
                        cachedFolderStatus
                    )
                )
            }
        }
    }

    fun setDiscoveredConflictFiles(
        folderId: String?,
        discoveredConflictFiles: List<String>
    ) {
        synchronized(mFolderMapLock) {
            val cacheEntry = getFolderStatus(folderId)
            val cachedFolderStatus = cacheEntry.value
            cachedFolderStatus.discoveredConflictFiles = discoveredConflictFiles.toList()

            // Add folder or update existing folder entry.
            folderId?.let {
                folderMap.put(
                    folderId,
                    AbstractMap.SimpleEntry<FolderStatus, CachedFolderStatus?>(
                        cacheEntry.key,
                        cachedFolderStatus
                    )
                )
            }
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
        private const val TAG = "LocalCompletion"
    }
}
