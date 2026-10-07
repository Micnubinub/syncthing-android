package com.micnubinub.syncthing.service

import android.content.Context
import android.content.Intent
import android.os.Trace
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.common.base.Objects
import com.google.common.base.Optional
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser.parseString
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.activities.ShareActivity
import com.micnubinub.syncthing.http.ApiError
import com.micnubinub.syncthing.http.ApiRequest
import com.micnubinub.syncthing.http.EventsPoller
import com.micnubinub.syncthing.http.GetRequest
import com.micnubinub.syncthing.http.OnErrorListener
import com.micnubinub.syncthing.http.OnSuccessListener
import com.micnubinub.syncthing.http.PostRequest
import com.micnubinub.syncthing.http.SyncthingHttpClients
import com.micnubinub.syncthing.model.CachedFolderStatus
import com.micnubinub.syncthing.model.CompletionInfo
import com.micnubinub.syncthing.model.Config
import com.micnubinub.syncthing.model.Connection
import com.micnubinub.syncthing.model.Connections
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.DeviceStat
import com.micnubinub.syncthing.model.DiscoveredDevice
import com.micnubinub.syncthing.model.DiskEvent
import com.micnubinub.syncthing.model.Event
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.FolderIgnoreList
import com.micnubinub.syncthing.model.FolderStatus
import com.micnubinub.syncthing.model.Gui
import com.micnubinub.syncthing.model.IgnoredFolder
import com.micnubinub.syncthing.model.LocalCompletion
import com.micnubinub.syncthing.model.Options
import com.micnubinub.syncthing.model.PendingDevice
import com.micnubinub.syncthing.model.PendingFolder
import com.micnubinub.syncthing.model.RemoteCompletion
import com.micnubinub.syncthing.model.RemoteCompletionInfo
import com.micnubinub.syncthing.model.RemoteIgnoredDevice
import com.micnubinub.syncthing.model.SystemStatus
import com.micnubinub.syncthing.model.SystemVersion
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS
import com.micnubinub.syncthing.service.Constants.ENABLE_TEST_DATA
import com.micnubinub.syncthing.service.RestApi.Companion.STARTUP_SNAPSHOT_DEADLINE_MS
import com.micnubinub.syncthing.util.FileUtils.syncthingTildeAbsolutePath
import com.micnubinub.syncthing.util.Util.FOLDERS_COMPARATOR
import com.micnubinub.syncthing.util.Util.getSyncConflictFiles
import com.micnubinub.syncthing.util.Util.localZonedDateTime
import com.micnubinub.syncthing.util.Util.runScriptSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.Collections
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

/**
 * Provides functions to interact with the syncthing REST API.
 */
class RestApi(
    private val context: Context,
    /**
     * Written by the config-save consumer when a new config moved the Web GUI, and read by
     * every request that follows, which runs on other threads.
     */
    @Volatile
    private var url: URL,
    val apiKey: String?,
    apiListener: OnApiAvailableListener,
    configListener: OnConfigChangedListener,
    /**
     * Told when the startup handshake of the current generation failed, see
     * [readConfigFromRestApi]. The owner must terminate what it started rather than sit
     * in STARTING with no usable core.
     */
    private val onStartupFailedListener: (String) -> Unit = {}
) {
    /**
     * Object that must be locked upon accessing [startupGeneration] and [startupResults].
     */
    private val asyncQueryCompleteLock = Any()

    /**
     * Monotonic id of the current startup handshake, bumped by every
     * [readConfigFromRestApi]. Results that arrive from an abandoned generation are
     * dropped instead of completing or failing the one that replaced it.
     */
    private var startupGeneration = 0L

    /**
     * Whether each snapshot of [startupGeneration] succeeded, absent until it reports.
     * A snapshot that failed stays failed: activating on a failed one would hand the UI
     * a config that was never loaded.
     */
    private val startupResults =
        EnumMap<StartupSnapshot, Boolean>(StartupSnapshot::class.java)

    /**
     * The three snapshots that make up a startup handshake, see [readConfigFromRestApi].
     */
    private enum class StartupSnapshot { VERSION, CONFIG, SYSTEM_STATUS }

    /**
     * Object that must be locked upon accessing config
     */
    private val configLock = Any()

    /**
     * One config snapshot waiting to be delivered to the core, plus the result the
     * waiter is suspended on.
     */
    private class PendingConfigSave(
        val jsonConfig: String,
        val completion: CompletableDeferred<ConfigSaveResult>
    )

    /**
     * Serializes config writes. Two overlapping POSTs to /rest/system/config can reach
     * the core out of order, which would leave it with the older config, so every save
     * goes through this single-consumer queue.
     */
    private val configSaveQueue = Channel<PendingConfigSave>(Channel.UNLIMITED)

    /**
     * Delivers queued config saves in the order they were taken. Separate from [scope],
     * which [shutdown] cancels for unrelated polling.
     */
    private val configSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Saves whose completion is still awaited, so [shutdown] can release them.
     */
    private val pendingConfigSaves =
        ConcurrentHashMap.newKeySet<CompletableDeferred<ConfigSaveResult>>()

    private var scope: CoroutineScope
    private val onApiAvailableListener: OnApiAvailableListener
    private val onConfigChangedListener: OnConfigChangedListener

    /**
     * Stores the latest result of device and folder completion events.
     */
    private val localCompletion: LocalCompletion
    private val remoteCompletion: RemoteCompletion
    private val gson = Gson()

    /**
     * Throttles concurrent /rest/db/completion requests fired after a config
     * reload, so a large device x folder matrix does not flood the REST API.
     */
    private val completionRequestSemaphore = Semaphore(MAX_CONCURRENT_COMPLETION_REQUESTS)

    @Inject
    lateinit var notificationHandler: NotificationHandler

    private var ENABLE_VERBOSE_LOG = false
    var version: String? = null
    private var config: Config? = null
    private var eventStream: EventsPoller? = null

    /**
     * Emits whenever a cached folder status changes (state, summary or pause), pushed
     * from the event stream. UI layers collect this instead of polling folder status.
     */
    private val _folderStateChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val folderStateChanged: SharedFlow<Unit> = _folderStateChanged

    /**
     * Results cached from systemInfo, written on the main thread when the startup snapshot
     * completes and read from whichever thread asks for the local device.
     */
    @Volatile
    private var localDeviceId: String? = null

    @Volatile
    private var urVersionMax: Int = 0

    /**
     * Stores the result of the last successful request to [GetRequest.URI_CONNECTIONS],
     * or an empty Map.
     */
    private var previousConnections = Optional.absent<Connections>()

    /**
     * Stores the timestamp of the last result of the REST API endpoint [GetRequest.URI_CONNECTIONS].
     */
    private var previousConnectionTime: Long = 0

    /**
     * In the last-finishing [.readConfigFromRestApi] callback, we have to call
     * [SyncthingService.onApiAvailable] to indicate that the RestApi class is fully initialized.
     * We do this to avoid getting stuck with our main thread due to synchronous REST queries.
     * The correct indication of full initialisation is crucial to stability as other listeners of
     * [../activities] needs cached config and system information available.
     * e.g. SettingsFragment need "localDeviceId"
     */
    private var lastOnlineDeviceCount = 0
    private var lastTotalSyncCompletion = -1

    /**
     * Read from the config-save consumer on [Dispatchers.IO] and written by [shutdown] on
     * whichever thread tears the service down.
     */
    @Volatile
    private var hasShutdown = false

    /**
     * Timestamp of the last [GetRequest.URI_CONNECTIONS] query so [getRemoteDeviceStatus]
     * can refresh cached connection status at a bounded rate instead of once per process.
     * Read and written from every thread that asks for a device status.
     */
    @Volatile
    private var lastConnectionStatusQueryTime: Long = 0

    /**
     * Timestamp of the last [GetRequest.URI_DB_STATUS] query per folder, and of the last
     * event-driven write to the folder status cache. Used to refresh folder status at a
     * bounded rate without applying a response that raced a newer event-driven write.
     *
     * Written from the event processor on IO and read from [getFolderStatus] on the
     * caller's thread, so these must be concurrent maps. They cannot be [HashMap]:
     * concurrent structural modification can spin forever or drop entries.
     */
    private val folderStatusQueryTime = ConcurrentHashMap<String, Long>()
    private val folderStatusWriteTime = ConcurrentHashMap<String, Long>()

    /**
     * [ConcurrentHashMap] rejects null keys, and folder IDs are nullable throughout the
     * model. A null ID is a single logical key, so it maps to the empty string; an empty
     * ID is not a valid Syncthing folder ID and therefore never a real key.
     */
    private fun folderStatusKey(folderId: String?): String = folderId.orEmpty()

    val prettyPrinter = GsonBuilder().setPrettyPrinting().create()

    init {
        (context.applicationContext as SyncthingApp).component().inject(this)
        ENABLE_VERBOSE_LOG = getPrefVerboseLog(context)
        onApiAvailableListener = apiListener
        onConfigChangedListener = configListener
        localCompletion = LocalCompletion(ENABLE_VERBOSE_LOG)
        remoteCompletion = RemoteCompletion(ENABLE_VERBOSE_LOG)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        configSaveScope.launch {
            consumeConfigSaveQueue()
        }
    }

    /**
     * Gets local device ID, syncthing version and config, then calls all OnApiAvailableListeners.
     *
     * The handshake only succeeds when all three snapshots of this generation succeeded.
     * Any failure, or nothing reporting before [STARTUP_SNAPSHOT_DEADLINE_MS], fails it and
     * reports through [onStartupFailedListener] so the service can tear down instead of
     * activating on partial data or waiting forever.
     */
    fun readConfigFromRestApi() {
        Trace.beginSection("RestApi.readConfigFromRestApi")
        try {
            LogV("Querying config from REST ...")
            val generation: Long
            synchronized(asyncQueryCompleteLock) {
                startupGeneration++
                generation = startupGeneration
                startupResults.clear()
            }
            scope.launch {
                delay(STARTUP_SNAPSHOT_DEADLINE_MS.milliseconds)
                failStartup(generation, "Startup snapshots did not all report in time")
            }
            GetRequest(
                context,
                url,
                GetRequest.URI_VERSION,
                apiKey,
                null,
                { result: String? ->
                    val parsedVersion = try {
                        parseString(result).getAsJsonObject().get("version").asString
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "readConfigFromRestApi: Failed to read version: $e")
                        null
                    }
                    if (parsedVersion == null) {
                        completeStartupSnapshot(generation, StartupSnapshot.VERSION, false)
                    } else {
                        version = parsedVersion
                        updateDebugFacilitiesCache()
                        completeStartupSnapshot(generation, StartupSnapshot.VERSION, true)
                    }
                },
                { error ->
                    Log.e(TAG, "readConfigFromRestApi: Failed to load version: $error")
                    completeStartupSnapshot(generation, StartupSnapshot.VERSION, false)
                })
            GetRequest(
                context,
                url,
                GetRequest.URI_CONFIG,
                apiKey,
                null,
                { result: String? ->
                    onReloadConfigComplete(result) { loaded ->
                        completeStartupSnapshot(generation, StartupSnapshot.CONFIG, loaded)
                    }
                },
                { error ->
                    Log.e(TAG, "readConfigFromRestApi: Failed to load config: $error")
                    completeStartupSnapshot(generation, StartupSnapshot.CONFIG, false)
                })
            getSystemStatus(
                { info: SystemStatus? ->
                    localDeviceId = info?.myID
                    urVersionMax = info?.urVersionMax ?: 0
                    completeStartupSnapshot(generation, StartupSnapshot.SYSTEM_STATUS, true)
                },
                { error ->
                    Log.e(TAG, "readConfigFromRestApi: Failed to load system status: $error")
                    completeStartupSnapshot(generation, StartupSnapshot.SYSTEM_STATUS, false)
                })
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Records the outcome of one startup snapshot and, once every snapshot of
     * [generation] has reported, either activates the service or fails the startup.
     */
    private fun completeStartupSnapshot(
        generation: Long,
        snapshot: StartupSnapshot,
        succeeded: Boolean
    ) {
        val allReported: Boolean
        val allSucceeded: Boolean
        synchronized(asyncQueryCompleteLock) {
            if (generation != startupGeneration) {
                Log.w(
                    TAG,
                    "completeStartupSnapshot: ignoring $snapshot from superseded generation $generation"
                )
                return
            }
            startupResults[snapshot] = succeeded
            allReported = StartupSnapshot.entries.all { startupResults.containsKey(it) }
            allSucceeded = StartupSnapshot.entries.all { startupResults[it] == true }
        }
        if (!allReported) {
            return
        }
        if (!allSucceeded) {
            failStartup(generation, "Not all startup snapshots could be read")
            return
        }
        LogV("Reading config from REST completed. Syncthing version is $version")
        // Tell SyncthingService it can transition to State.ACTIVE.
        onApiAvailableListener.onApiAvailable()

        // Apply a pending cleanup-interval restore from a previous run that
        // was killed before its delayed restore could execute, then possibly
        // start a fresh cleanup cycle (triggers only every 10th startup).
        restoreVersioningCleanupFromPending()
        triggerVersioningCleanupIfNecessary()
    }

    /**
     * Fails the startup handshake of [generation], telling the service to terminate what
     * it owns. A generation that already reported is left alone, so the success path is
     * never undone.
     */
    private fun failStartup(generation: Long, reason: String) {
        synchronized(asyncQueryCompleteLock) {
            if (generation != startupGeneration) {
                return
            }
            // Mark every snapshot as reported so a late result cannot activate the core
            // after this point.
            StartupSnapshot.entries.forEach { startupResults.putIfAbsent(it, false) }
        }
        Log.e(TAG, "readConfigFromRestApi: $reason")
        onStartupFailedListener(reason)
    }

    private fun triggerVersioningCleanupIfNecessary() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var startupCounter = sharedPreferences.getInt(Constants.PREF_APP_START_COUNTER, 0) + 1
        startupCounter = if (startupCounter == Int.MAX_VALUE) 1 else startupCounter
        sharedPreferences.edit {
            putInt(Constants.PREF_APP_START_COUNTER, startupCounter)
        }

        if (startupCounter % 10 != 0) {
            LogV("Skipping versioning cleanup because it is only triggered every 10th startup to save resources.")
            return
        }

        // Remember the user's cleanupIntervalS per folder and persist the map,
        // so the pending restore survives a process kill.
        val restoreMap: MutableMap<String, Int> = LinkedHashMap<String, Int>()
        synchronized(configLock) {
            config?.folders?.forEach { folder ->
                folder.versioning?.cleanupIntervalS?.let { cleanupIntervalS ->
                    folder.id?.let { restoreMap[it] = cleanupIntervalS }
                }
            }
        }
        sharedPreferences.edit {
            putString(Constants.PREF_VERSIONING_CLEANUP_RESTORE, gson.toJson(restoreMap))
        }

        // Temporarily lower cleanupIntervalS for every folder to force cleanup after startup.
        setVersioningCleanupIntervalS(2)
        scope.launch {
            delay(10_000L.milliseconds)
            restoreVersioningCleanupFromPending()
        }
    }

    /**
     * Restores the user's original `cleanupIntervalS` values that were lowered
     * by [triggerVersioningCleanupIfNecessary] and clears the pending restore.
     *
     * Idempotent: safe to call from the delayed coroutine as well as from a
     * subsequent startup where the previous process was killed before the
     * restore could run. No-op when there is nothing pending.
     */
    private fun restoreVersioningCleanupFromPending() {
        if (hasShutdown) {
            LogV("Skipping versioning cleanup restore due to hasShutdown == true; will restore next start")
            return
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = sharedPreferences.getString(Constants.PREF_VERSIONING_CLEANUP_RESTORE, null)
        if (stored.isNullOrEmpty()) {
            return
        }

        var restoreMap: MutableMap<String, Int>? = null
        try {
            restoreMap = gson.fromJson<MutableMap<String, Int>>(
                stored, object : TypeToken<MutableMap<String, Int>>() {}.type
            )
        } catch (e: Exception) {
            Log.e(TAG, "restoreVersioningCleanupFromPending: Failed to parse pending restore", e)
        }

        if (restoreMap.isNullOrEmpty()) {
            sharedPreferences.edit { remove(Constants.PREF_VERSIONING_CLEANUP_RESTORE) }
            return
        }

        var changed = false
        synchronized(configLock) {
            config?.folders?.forEach { folder ->
                folder.id?.let { id ->
                    restoreMap[id]?.let { cleanupIntervalS ->
                        if (folder.versioning?.cleanupIntervalS != cleanupIntervalS) {
                            folder.versioning?.cleanupIntervalS = cleanupIntervalS
                            changed = true
                        }
                    }
                }
            }
            if (changed) {
                LogV("Restored VersioningCleanupIntervalS to the user's original values")
                queueConfigSave()
            }
        }
        sharedPreferences.edit { remove(Constants.PREF_VERSIONING_CLEANUP_RESTORE) }
    }

    fun reloadConfig() {
        GetRequest(
            context,
            url,
            GetRequest.URI_CONFIG,
            apiKey,
            null,
            { configResult: String? -> onReloadConfigComplete(configResult) },
            null
        )
    }

    private fun onReloadConfigComplete(
        configResult: String?,
        onReady: ((loaded: Boolean) -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val parsedConfig = try {
                gson.fromJson(configResult, Config::class.java)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "onReloadConfigComplete: Failed to parse config", e)
                onReady?.invoke(false)
                return@launch
            }
            if (parsedConfig == null) {
                Log.e(TAG, "onReloadConfigComplete: config is null: $configResult")
                onReady?.invoke(false)
                return@launch
            }
            synchronized(configLock) {
                config = parsedConfig
            }
            Log.d(TAG, "onReloadConfigComplete: Successfully parsed configuration.")

            synchronized(configLock) {
                val logRemoteIgnoredDevices = gson.toJson(config?.remoteIgnoredDevices)
                if (logRemoteIgnoredDevices != "[]") {
                    LogV("ORCC: remoteIgnoredDevices = $logRemoteIgnoredDevices")
                }

                // Loop through devices to get ignoredFolders per device.
                for (device in getDevices(false)) {
                    val logIgnoredFolders = gson.toJson(device.ignoredFolders)
                    if (logIgnoredFolders != "[]") {
                        LogV("ORCC: device[" + device.displayName + "].ignoredFolders = " + logIgnoredFolders)
                    }
                }
            }

            onReady?.invoke(true)

            GetRequest(
                context,
                url, GetRequest.URI_PENDING_DEVICES,
                apiKey, null, OnSuccessListener { result: String? ->
                    if (result == null) {
                        Log.e(TAG, "ORCC: URI_PENDING_DEVICES, result == null")
                        return@OnSuccessListener
                    }
                    val jsonObject = parseJsonObjectOrNull(result)
                    if (jsonObject == null) {
                        Log.e(TAG, "ORCC: URI_PENDING_DEVICES, result is not a JSON object")
                        return@OnSuccessListener
                    }
                    val entries = jsonObject.entrySet()
                    for ((key, value) in entries) {
                        val resultDeviceId = key ?: continue
                        val pendingDevice =
                            gson.fromJson(value, PendingDevice::class.java)
                        Log.d(
                            TAG,
                            "ORCC: resultDeviceId = " + resultDeviceId + "('" + pendingDevice.name + "')"
                        )
                        notificationHandler.showDeviceConnectNotification(
                            resultDeviceId,
                            pendingDevice.name,
                            pendingDevice.address
                        )
                    }
                }) { }
            showPendingFolderNotifications()

            // Update cached device and folder information.

            localCompletion.updateFromConfig(folders)
            remoteCompletion.updateFromConfig(getDevices(true), folders)

            // Perform first query for remote device status by forcing a cache miss.
            getRemoteDeviceStatus("")

            for (folder in folders) {
                folder.devices.let { sharedWithDevices ->
                    for (device in sharedWithDevices) {
                        completionRequestSemaphore.acquire()
                        GetRequest(
                            context,
                            url,
                            GetRequest.URI_DB_COMPLETION,
                            apiKey,
                            mutableMapOf(
                                "device" to device.deviceID,
                                "folder" to folder.id
                            ),
                            { result: String? ->
                                try {
                                    val completionInfo =
                                        gson.fromJson(result, CompletionInfo::class.java)
                                    LogV(
                                        "ORCC: /rest/db/completion: folder=" + folder.id +
                                                ", device=" + device.displayName +
                                                ", completion=" + completionInfo.completion +
                                                ", needBytes=" + String.format(
                                            Locale.getDefault(),
                                            "%.0f",
                                            completionInfo.needBytes
                                        ) +
                                                ", remoteState=" + completionInfo.remoteState
                                    )
                                    val remoteCompletionInfo = RemoteCompletionInfo()
                                    remoteCompletionInfo.completion = completionInfo.completion
                                    remoteCompletionInfo.needBytes = completionInfo.needBytes
                                    remoteCompletion.setCompletionInfo(
                                        device.deviceID,
                                        folder.id,
                                        remoteCompletionInfo
                                    )
                                } finally {
                                    completionRequestSemaphore.release()
                                }
                            }, {
                                completionRequestSemaphore.release()
                            })
                    }
                }

            }
        }
    }

    /**
     * Queries pending folder offers from the REST API and shows a consent
     * notification for every folder offered by a known device. Invoked once
     * after the config is loaded and again whenever a device connects, so an
     * offer is not lost when the "PendingFoldersChanged" event is missed
     * (e.g. while the event stream was reconnecting).
     */
    fun showPendingFolderNotifications() {
        GetRequest(
            context,
            url, GetRequest.URI_PENDING_FOLDERS,
            apiKey, null, OnSuccessListener { result: String? ->
                if (result == null) {
                    Log.e(TAG, "ORCC: URI_PENDING_FOLDERS, result == null")
                    return@OnSuccessListener
                }
                val jsonObject = parseJsonObjectOrNull(result)
                if (jsonObject == null) {
                    Log.e(TAG, "ORCC: URI_PENDING_FOLDERS, result is not a JSON object")
                    return@OnSuccessListener
                }
                val entries = jsonObject.entrySet()
                for (folderEntry in entries) {
                    val resultFolderId = folderEntry.key ?: continue
                    val jsonObjectOfferedBy =
                        folderEntry.value?.getAsJsonObject()?.get("offeredBy")
                            ?.getAsJsonObject()
                    jsonObjectOfferedBy?.entrySet()?.let { mapEntries ->
                        for (offeredByEntry in mapEntries) {
                            val offeredByDeviceId = offeredByEntry.key ?: continue
                            val pendingFolder = gson.fromJson(
                                offeredByEntry.value,
                                PendingFolder::class.java
                            )
                            Log.d(
                                TAG,
                                "ORCC: resultFolderId = " + resultFolderId + "('" + pendingFolder.label + "')"
                            )
                            getDevices(false).filter({ d: Device -> d.deviceID == offeredByDeviceId })
                                .firstOrNull()?.let { matchingDevice ->
                                    val isNewFolder = folders
                                        .none({ f: Folder? -> f?.id == resultFolderId })
                                    notificationHandler.showFolderShareNotification(
                                        offeredByDeviceId,
                                        matchingDevice.displayName,
                                        resultFolderId,
                                        pendingFolder.label,
                                        pendingFolder.receiveEncrypted,
                                        pendingFolder.remoteEncrypted,
                                        isNewFolder
                                    )
                                }
                        }
                    }
                }
            }) { }
    }

    /**
     * Queries debug facilities available from the currently running syncthing binary
     * if the syncthing binary version changed. First launch of the binary is also
     * considered as a version change.
     * Precondition: [.version] read from REST
     */
    private fun updateDebugFacilitiesCache() {
        if (version != PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_LAST_BINARY_VERSION, "")
        ) {
            // First binary launch or binary upgraded case.
            GetRequest(
                context,
                url, GetRequest.URI_SYSTEM_LOGLEVELS,
                apiKey, null, { result: String? ->
                    try {
                        val facilitiesToStore: MutableSet<String> = HashSet<String>()
                        val json = parseString(result).getAsJsonObject()
                        val jsonFacilities = json.getAsJsonObject("packages")
                        for (facilityName in jsonFacilities.keySet()) {
                            facilitiesToStore.add(facilityName)
                        }
                        PreferenceManager.getDefaultSharedPreferences(context).edit {
                            putStringSet(
                                Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
                                facilitiesToStore
                            )
                        }

                        // Store current binary version so we will only store this information again
                        // after a binary update.
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit {
                                putString(Constants.PREF_LAST_BINARY_VERSION, version)
                            }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "updateDebugFacilitiesCache: Failed to get debug facilities. result=$result"
                        )
                    }
                }, null
            )
        }
    }

    /**
     * Permanently ignore a device when it tries to connect.
     * Ignored devices will not trigger the "DeviceRejected" event
     * in [EventProcessor.onEvent].
     */
    suspend fun ignoreDevice(
        deviceId: String,
        deviceName: String,
        deviceAddress: String
    ): ConfigSaveResult {
        val completion = synchronized(configLock) {
            // Check if the device has already been ignored.
            config?.remoteIgnoredDevices?.let {
                for (remoteIgnoredDevice in it) {
                    if (deviceId == remoteIgnoredDevice?.deviceID) {
                        // Device already ignored.
                        Log.d(TAG, "Device already ignored [$deviceId]")
                        return@synchronized null
                    }
                }
            }

            /**
             * Ignore device by moving its corresponding "pendingDevice" entry to
             * a newly created "remoteIgnoredDevice" entry.
             */
            val remoteIgnoredDevice = RemoteIgnoredDevice()
            remoteIgnoredDevice.deviceID = deviceId
            remoteIgnoredDevice.address = deviceAddress
            remoteIgnoredDevice.name = deviceName
            remoteIgnoredDevice.time = localZonedDateTime
            config?.remoteIgnoredDevices?.add(remoteIgnoredDevice)
            queueConfigSaveLocked()
        }
        Log.d(TAG, "Ignored device [$deviceId]")
        return completion?.await() ?: ConfigSaveResult.SKIPPED
    }

    /**
     * Permanently ignore a folder share request.
     * Ignored folders will not trigger the "FolderRejected" event
     * in [EventProcessor.onEvent].
     */
    suspend fun ignoreFolder(
        deviceId: String,
        folderId: String,
        folderLabel: String
    ): ConfigSaveResult {
        var completion: CompletableDeferred<ConfigSaveResult>? = null
        synchronized(configLock) {
            config?.devices?.let { devices ->
                for (device in devices) {
                    if (deviceId == device.deviceID) {
                        /**
                         * Check if the folder has already been ignored.
                         */
                        device.ignoredFolders?.let {
                            for ((id) in it) {
                                if (folderId == id) {
                                    // Folder already ignored.
                                    Log.d(
                                        TAG,
                                        "ignoreFolder: Folder [$folderId] already ignored on device [$deviceId]"
                                    )
                                    return@synchronized
                                }
                            }
                        }


                        /**
                         * Ignore folder by moving its corresponding "pendingFolder" entry to
                         * a newly created "ignoredFolder" entry.
                         */
                        val ignoredFolder = IgnoredFolder()
                        ignoredFolder.id = folderId
                        ignoredFolder.label = folderLabel
                        ignoredFolder.time = localZonedDateTime
                        device.ignoredFolders?.add(ignoredFolder)
                        LogV("ignoreFolder: device.getIgnoredFolders() = " + gson.toJson(device.ignoredFolders))
                        completion = queueConfigSaveLocked()

                        // Given deviceId handled.
                        break
                    }
                }
            }
        }
        Log.d(TAG, "Ignored folder [$folderId] announced by device [$deviceId]")
        return completion?.await() ?: ConfigSaveResult.SKIPPED
    }

    /**
     * Undo ignoring devices and folders.
     */
    suspend fun undoIgnoredDevicesAndFolders(): ConfigSaveResult {
        Log.d(TAG, "Undo ignoring devices and folders ...")
        val completion = synchronized(configLock) {
            config?.remoteIgnoredDevices?.clear()
            config?.devices?.let {
                for (device in it) {
                    device.ignoredFolders?.clear()
                }
            }
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    /**
     * Override folder changes. This is the same as hitting
     * the "override changes" button from the web UI.
     */
    fun overrideChanges(folderId: String?) {
        Log.d(TAG, "overrideChanges '$folderId'")
        PostRequest(
            context,
            url,
            PostRequest.URI_DB_OVERRIDE,
            apiKey,
            mutableMapOf("folder" to folderId),
            null,
            null
        )
    }

    /**
     * Rescan all folders
     */
    suspend fun rescanAll(): Boolean {
        Log.d(TAG, "rescanAll")
        return postAndReport("rescanAll", PostRequest.URI_DB_SCAN, null)
    }

    /**
     * Revert local folder changes. This is the same as hitting
     * the "Revert local changes" button from the web UI.
     */
    suspend fun revertLocalChanges(folderId: String?): Boolean {
        Log.d(TAG, "revertLocalChanges '$folderId'")
        return postAndReport(
            "revertLocalChanges", PostRequest.URI_DB_REVERT, mutableMapOf("folder" to folderId)
        )
    }

    /**
     * Issues a POST and suspends until the core has answered, so a caller that reports an
     * action as done cannot do so for a request that never arrived.
     *
     * @return whether the core accepted the request.
     */
    private suspend fun postAndReport(
        what: String,
        path: String,
        params: MutableMap<String, String?>?,
        postBody: String? = null
    ): Boolean {
        val result = PostRequest.postAndAwait(context, url, path, apiKey, params, postBody)
        if (result is ApiRequest.ApiResult.Failure) {
            Log.e(TAG, "$what: The core did not accept the request: ${result.error}")
            return false
        }
        return true
    }

    val webGuiUrl: URL
        get() {
            synchronized(configLock) {
                // TLS is always enforced; cleartext HTTP would expose the API key.
                val urlProtocol = "https"
                try {
                    if (config?.gui?.address == null) {
                        Log.e(
                            TAG,
                            "getWebGuiUrl: config.getGui().getAddress() == null, returning 127.0.0.1:8384"
                        )
                        return URL("$urlProtocol://127.0.0.1:8384")
                    }
                    return URL(urlProtocol + "://" + config?.gui?.address)
                } catch (e: MalformedURLException) {
                    throw RuntimeException("Failed to parse web interface URL", e)
                }
            }
        }

    /**
     * Sends current config to Syncthing and suspends until Syncthing has accepted it.
     * Will result in a "ConfigSaved" event.
     * EventProcessor will trigger reloadConfig().
     *
     * Saves are serialized through [configSaveQueue], so a mutation made while an earlier
     * save is in flight cannot overtake it and leave the core with the older config.
     *
     * @return [ConfigSaveResult.SAVED] only once the core has the new config. On anything
     * else no reload is triggered, so the UI keeps showing the config that is actually
     * live and the caller can report the failure or roll back.
     */
    suspend fun sendConfig(): ConfigSaveResult {
        val completion = synchronized(configLock) { queueConfigSaveLocked() }
        return completion.await()
    }

    /**
     * Result of a config mutation, see [sendConfig].
     */
    enum class ConfigSaveResult {
        /**
         * Syncthing has the new config.
         */
        SAVED,

        /**
         * The config was not written, so it is unchanged and must not be reported as saved.
         */
        FAILED,

        /**
         * The core is going away, so the change was neither written nor lost silently:
         * it is still in the model and is saved on the next start.
         */
        SKIPPED
    }

    /**
     * Snapshots the current config and hands it to [configSaveQueue], so that overlapping
     * mutations cannot deliver an older snapshot after a newer one.
     *
     * Snapshotting and enqueueing happen together because a save that is queued before an
     * intervening mutation would otherwise overwrite it on the core.
     *
     * Call this with [configLock] held.
     */
    private fun queueConfigSaveLocked(): CompletableDeferred<ConfigSaveResult> {
        val completion = CompletableDeferred<ConfigSaveResult>()
        val queued = configSaveQueue.trySend(PendingConfigSave(gson.toJson(config), completion))
        if (queued.isFailure) {
            // The queue is closed, i.e. the core is shutting down.
            completion.complete(ConfigSaveResult.SKIPPED)
        } else {
            pendingConfigSaves.add(completion)
        }
        return completion
    }

    /**
     * For internal mutations whose outcome nobody reports. The reload still only happens
     * once the core has accepted the write.
     */
    private fun queueConfigSave() {
        synchronized(configLock) {
            queueConfigSaveLocked()
        }
    }

    /**
     * Runs the single save-queue consumer for the lifetime of this [RestApi].
     */
    private suspend fun consumeConfigSaveQueue() {
        for (save in configSaveQueue) {
            save.completion.complete(deliverConfigSave(save))
            pendingConfigSaves.remove(save.completion)
        }
    }

    /**
     * Posts one config snapshot and reports what the core made of it. Runs on the single
     * save-queue consumer, so the snapshots reach the core in the order they were taken.
     */
    private suspend fun deliverConfigSave(save: PendingConfigSave): ConfigSaveResult {
        if (hasShutdown) {
            return ConfigSaveResult.SKIPPED
        }
        val result = PostRequest.postAndAwait(
            context, url, PostRequest.URI_SYSTEM_CONFIG, apiKey,
            postBody = save.jsonConfig
        )
        return when (result) {
            is ApiRequest.ApiResult.Success -> {
                url = webGuiUrl
                // Only now is the new config authoritative, so only now do listeners reload.
                onConfigChangedListener.onConfigChanged()
                ConfigSaveResult.SAVED
            }

            is ApiRequest.ApiResult.Failure -> {
                Log.e(
                    TAG,
                    "deliverConfigSave: Syncthing did not accept the new config: ${result.error}"
                )
                ConfigSaveResult.FAILED
            }
        }
    }

    /**
     * Posts shutdown request.
     * This will cause SyncthingNative to exit and not restart.
     */
    suspend fun shutdown() {
        hasShutdown = true
        stopEventStream()
        scope.cancel()
        // Nothing the core has not already answered can still succeed now, so end the
        // outstanding requests instead of letting them occupy a dispatcher thread each
        // until they time out. This runs before the shutdown POST below, which is
        // created afterwards and therefore still reaches the core.
        SyncthingHttpClients.cancelInFlightCalls()

        // The core is going away, so a queued config save can no longer be delivered.
        // Release every waiter instead of leaving it suspended for good.
        configSaveQueue.close()
        configSaveScope.cancel()
        pendingConfigSaves.forEach { it.complete(ConfigSaveResult.SKIPPED) }
        pendingConfigSaves.clear()

        // Bounded: a wedged core must not hold the caller here indefinitely. The
        // caller force-terminates the process on timeout, so a missing request is
        // recoverable.
        withTimeoutOrNull(SHUTDOWN_REQUEST_TIMEOUT_MS.milliseconds) {
            PostRequest.postAndAwait(
                context, url, PostRequest.URI_SYSTEM_SHUTDOWN, apiKey
            )
        } ?: Log.w(TAG, "shutdown: Syncthing did not acknowledge the shutdown request")
    }

    /**
     * Drops cached data to free memory under system memory pressure.
     * All caches are repopulated lazily on the next query.
     */
    fun clearCaches() {
        // Both models are written from the event processor on IO, so they must be
        // cleared through the lock-owning methods, not by clearing the maps here.
        localCompletion.clear()
        remoteCompletion.clear()
        previousConnections = Optional.absent()
        previousConnectionTime = 0
        // The rate limiters are keyed by folder, so they would otherwise keep a
        // stale entry per folder for the rest of the process lifetime and suppress
        // the first query after the cache drop.
        folderStatusQueryTime.clear()
        folderStatusWriteTime.clear()
    }

    val folders: List<Folder>
        get() {
            val folders: MutableList<Folder>
            synchronized(configLock) {
                folders =
                    (config?.folders?.map { it.copy() } as? MutableList<Folder>) ?: mutableListOf()
            }
            for (folder in folders) {
                if (folder.path?.startsWith("~/") == true) {
                    folder.path = folder.path?.replaceFirst(
                        "^~".toRegex(),
                        syncthingTildeAbsolutePath
                    )
                }
            }
            Collections.sort(
                folders,
                FOLDERS_COMPARATOR
            )
            return folders
        }

    fun getFolderByID(folderID: String?): Folder? {
        if (ENABLE_TEST_DATA && folderID == "abcd-efgh") {
            val folder = Folder()
            folder.id = "abcd-efgh"
            folder.label = "label_abcd-efgh"
            folder.path = "/storage/emulated/0/testdata"
            folder.type = Constants.FOLDER_TYPE_SEND_RECEIVE
            return folder
        }

        for (folder in folders) {
            if (folder.id == folderID) {
                return folder
            }
        }
        return null
    }

    /**
     * This is only used for new folder creation, see [../activities].
     */
    suspend fun addFolder(folder: Folder): ConfigSaveResult {
        val completion = synchronized(configLock) {
            // Add the new folder to the model.
            config?.folders?.add(folder)
            // Send model changes to syncthing, does not require a restart.
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    suspend fun updateFolder(newFolder: Folder): ConfigSaveResult {
        val completion = synchronized(configLock) {
            removeFolderInternal(newFolder.id)
            config?.folders?.add(newFolder)
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    suspend fun removeFolder(id: String?): ConfigSaveResult {
        val completion = synchronized(configLock) {
            removeFolderInternal(id)
            // localCompletion will be updated after the ConfigSaved event.
            // remoteCompletion will be updated after the ConfigSaved event.
            queueConfigSaveLocked()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            remove(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + id)
        }
        return completion.await()
    }

    private fun removeFolderInternal(id: String?) {
        synchronized(configLock) {
            val it: MutableIterator<Folder> = config?.folders?.iterator() ?: return
            while (it.hasNext()) {
                val f = it.next()
                if (f.id == id) {
                    it.remove()
                    break
                }
            }
        }
    }

    /**
     * Returns a list of all existing devices.
     *
     * @param includeLocal True if the local device should be included in the result.
     */
    fun getDevices(includeLocal: Boolean): List<Device> {
        val devices: MutableList<Device>
        synchronized(configLock) {
            devices = config?.devices?.mapTo(mutableListOf()) {
                it.copy()
            } ?: mutableListOf()
        }

        val it = devices.iterator()
        while (it.hasNext()) {
            val device = it.next()
            val isLocalDevice = Objects.equal(localDeviceId, device.deviceID)
            if (!includeLocal && isLocalDevice) {
                it.remove()
                break
            }
        }
        return devices
    }

    val localDevice: Device?
        get() {
            val devices = getDevices(true)
            if (devices.isEmpty()) {
                return null
            }
            LogV("getLocalDevice: Looking for local device ID $localDeviceId")
            for (d in devices) {
                if (d.deviceID == localDeviceId) {
                    return d.copy()
                }
            }
            return null
        }

    /**
     * Adds or updates a device identified by its device ID.
     */
    suspend fun updateDevice(newDevice: Device): ConfigSaveResult {
        val completion = synchronized(configLock) {
            removeDeviceInternal(newDevice.deviceID)
            config?.devices?.add(newDevice)
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    suspend fun removeDevice(deviceId: String): ConfigSaveResult {
        val completion = synchronized(configLock) {
            removeDeviceInternal(deviceId)
            // remoteCompletion will be updated after the ConfigSaved event.
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    private fun removeDeviceInternal(deviceId: String?) {
        synchronized(configLock) {
            val it: MutableIterator<Device> = config?.devices?.iterator() ?: return
            while (it.hasNext()) {
                val d = it.next()
                if (d.deviceID == deviceId) {
                    it.remove()
                    break
                }
            }
        }
    }

    val options: Options?
        get() {
            synchronized(configLock) {
                return config?.options?.copy()
            }
        }

    val gui: Gui?
        get() {
            synchronized(configLock) {
                return config?.gui?.copy()
            }
        }

    /**
     * Applies GUI/options edits together with a local device rename, so that a single
     * config write carries all of them. [newLocalDevice] may be null while the config is
     * still loading.
     *
     * @return [ConfigSaveResult.SAVED] only once the core has the new config.
     */
    suspend fun editSettings(
        newGui: Gui?,
        newOptions: Options?,
        newLocalDevice: Device?
    ): ConfigSaveResult {
        val completion = synchronized(configLock) {
            config?.gui = newGui
            config?.options = newOptions
            newLocalDevice?.let { device ->
                removeDeviceInternal(device.deviceID)
                config?.devices?.add(device)
            }
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    suspend fun updateGui(newGui: Gui?): ConfigSaveResult {
        val completion = synchronized(configLock) {
            config?.gui = newGui
            queueConfigSaveLocked()
        }
        return completion.await()
    }

    /**
     * Requests and parses information about current system status and resource usage.
     */
    fun getSystemStatus(
        listener: OnResultListener<SystemStatus>,
        errorListener: OnErrorListener? = null
    ) {
        GetRequest(
            context,
            url, GetRequest.URI_SYSTEM_STATUS,
            apiKey, null, { result: String? ->
                val systemStatus = try {
                    gson.fromJson(result, SystemStatus::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "getSystemStatus: Parsing REST API result failed. result=$result")
                    null
                }
                if (systemStatus == null) {
                    // Reporting through neither listener would leave a caller that is
                    // waiting on one of them waiting for good.
                    errorListener?.onError(
                        ApiError.Network(IOException("Failed to parse system status"))
                    )
                } else {
                    listener.onResult(systemStatus)
                }
            }, errorListener
        )
    }

    val isConfigLoaded: Boolean
        get() {
            synchronized(configLock) {
                return config != null
            }
        }

    /**
     * Requests locally discovered devices.
     */
    fun getDiscoveredDevices(listener: OnResultListener<MutableMap<String, DiscoveredDevice?>?>) {
        GetRequest(
            context, url, GetRequest.URI_SYSTEM_DISCOVERY, apiKey,
            null, { result: String? ->
                val discoveredDevices = try {
                    gson.fromJson<MutableMap<String, DiscoveredDevice?>>(
                        result,
                        object : TypeToken<MutableMap<String, DiscoveredDevice?>>() {
                        }.type
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "getDiscoveredDevices: Failed to parse the REST API result")
                    null
                }
                if (ENABLE_TEST_DATA && discoveredDevices != null) {
                    val fakeDiscoveredDevice = DiscoveredDevice()
                    fakeDiscoveredDevice.addresses = listOf("tcp4://192.168.178.10:40004")
                    discoveredDevices[TestData.DEVICE_A_ID] = fakeDiscoveredDevice
                    discoveredDevices[TestData.DEVICE_B_ID] = fakeDiscoveredDevice
                }
                listener.onResult(discoveredDevices)
            }, { })
    }

    /**
     * Requests ignore list for given folder.
     */
    fun getFolderIgnoreList(folderId: String?, listener: OnResultListener<FolderIgnoreList?>) {
        GetRequest(
            context,
            url,
            GetRequest.URI_DB_IGNORES,
            apiKey,
            mutableMapOf("folder" to folderId),
            { result: String ->
                // A null answer is how this method already reports "nothing usable"; the
                // editor leaves the ignore list alone in that case rather than replacing
                // it with an empty one.
                val folderIgnoreList = try {
                    gson.fromJson(result, FolderIgnoreList::class.java)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "getFolderIgnoreList: Failed to parse the REST API result")
                    null
                }
                listener.onResult(folderIgnoreList)
            },
            null
        )
    }

    /**
     * Posts ignore list for given folder and suspends until the core has accepted it, so
     * a screen that saves a folder can report the outcome of the whole save.
     *
     * @return whether the core accepted the ignore list.
     */
    suspend fun postFolderIgnoreList(folderId: String?, ignore: List<String>?): Boolean {
        val folderIgnoreList = FolderIgnoreList()
        folderIgnoreList.ignore = ignore
        return postAndReport(
            "postFolderIgnoreList",
            PostRequest.URI_DB_IGNORES,
            mutableMapOf("folder" to folderId),
            gson.toJson(folderIgnoreList)
        )
    }

    /**
     * Requests and parses system version information.
     */
    fun getSystemVersion(listener: OnResultListener<SystemVersion?>) {
        GetRequest(
            context,
            url,
            GetRequest.URI_VERSION,
            apiKey,
            null,
            { result: String? ->
                val systemVersion = try {
                    gson.fromJson(result, SystemVersion::class.java)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "getSystemVersion: Failed to parse the REST API result")
                    null
                }
                listener.onResult(systemVersion)
            },
            null
        )
    }

    /**
     * Returns status information about the device with the given id from cache.
     * Set deviceId to "" to query status for an initially empty cache.
     */
    fun getRemoteDeviceStatus(
        deviceId: String?
    ): Connection? {
        val cacheEntry: Connection? = remoteCompletion.getDeviceStatus(deviceId)
        val queryDue = System.currentTimeMillis() - lastConnectionStatusQueryTime >=
                Constants.REST_UPDATE_INTERVAL
        if ((cacheEntry?.at?.isEmpty() ?: true) || queryDue) {
            /**
             * Cache miss or stale cached status.
             * Query the required information so it will be available on a future call to this function.
             */
            if (!deviceId.isNullOrEmpty()) {
                LogV("getRemoteDeviceStatus: Cache miss, deviceId=\"$deviceId\". Performing query.")
            }
            lastConnectionStatusQueryTime = System.currentTimeMillis()
            GetRequest(
                context,
                url, GetRequest.URI_CONNECTIONS,
                apiKey, null, { result: String? ->
                    /**
                     * We got connection status information for ALL devices instead of one.
                     * It does not hurt storing all of them.
                     */
                    val connections = try {
                        gson.fromJson(result, Connections::class.java)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "getRemoteDeviceStatus: Failed to parse URI_CONNECTIONS")
                        null
                    }
                    connections?.let {
                        calculateConnectionStats(it)
                        it.connections?.entries?.let { entries ->
                            for (e in entries) {
                                e.value?.let { connection ->
                                    remoteCompletion.setDeviceStatus(
                                        e.key,  // deviceId
                                        connection
                                    )
                                }

                            }
                        }
                    }
                    // Connection changes now have the same notification and completion
                    // side effects as DeviceConnected/Disconnected events, so devices that
                    // come online are not stuck shown as disconnected.
                    onTotalSyncCompletionChange()

                }, null
            )
            GetRequest(
                context,
                url, GetRequest.URI_STATS_DEVICE,
                apiKey, null, OnSuccessListener { result: String? ->
                    /**
                     * We got the last seen timestamp for ALL devices - including the local device - instead of one.
                     * It does not hurt storing all of them.
                     */
                    if (result == null) {
                        Log.e(TAG, "getRemoteDeviceStatus: URI_STATS_DEVICE, result == null")
                        return@OnSuccessListener
                    }
                    val jsonObject = parseJsonObjectOrNull(result)
                    if (jsonObject == null) {
                        Log.e(
                            TAG,
                            "getRemoteDeviceStatus: URI_STATS_DEVICE, result is not a JSON object"
                        )
                        return@OnSuccessListener
                    }
                    val entries = jsonObject.entrySet()
                    for (entry in entries) {
                        val resultDeviceId = entry.key
                        val deviceStat =
                            gson.fromJson(entry.value, DeviceStat::class.java)
                        PreferenceManager.getDefaultSharedPreferences(context).edit {
                            putString(
                                Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + resultDeviceId,
                                deviceStat.lastSeen
                            )
                        }
                    }
                }) { }
        }
        return cacheEntry
    }

    fun getRemoteDeviceCompletion(deviceId: String?): Int {
        return remoteCompletion.getDeviceCompletion(deviceId)
    }

    fun getRemoteDeviceNeedBytes(deviceId: String?): Double {
        return remoteCompletion.getDeviceNeedBytes(deviceId)
    }

    val totalConnectionStatistic: Connection?
        get() {
            if (!previousConnections.isPresent) {
                return Connection()
            }
            return previousConnections.get().total?.copy()
        }

    /**
     * Calculate transfer rates for each remote device connection and the "total device" stats.
     */
    private fun calculateConnectionStats(connections: Connections) {
        val now = System.currentTimeMillis()
        val msElapsed = now - previousConnectionTime
        if (msElapsed < Constants.REST_UPDATE_INTERVAL) {
            // Too soon since the last rate calculation: keep the previously
            // cached stats untouched (the parameter was a pointless dead store).
            return
        }

        previousConnectionTime = now
        connections.connections?.entries?.let {
            for (e in it) {
                val prev: Connection? =
                    if (previousConnections.isPresent && previousConnections.get().connections
                            ?.containsKey(e.key) == true
                    )
                        previousConnections.get().connections?.get(e.key)
                    else
                        Connection()

                prev?.let {
                    e.value?.setTransferRate(prev, msElapsed)
                }

            }
        }

        val prev: Connection =
            previousConnections.transform<Connection> { it.total ?: Connection() }.or(
                Connection()
            )
        connections.total?.setTransferRate(prev, msElapsed)
        previousConnections = Optional.of<Connections>(connections)
    }

    val totalSyncCompletion: Int
        /**
         * Returns overall sync completion percentage representing all
         * currently running folder and device transfers.
         * Folder percentage means we are currently pulling changes from remotes.
         * Device percentage means remotes currently pull changes from us.
         * Uses cached stats instead of performing REST queries.
         */
        get() {
            val totalDeviceCompletion = remoteCompletion.totalDeviceCompletion
            if (totalDeviceCompletion == -1) {
                // Total sync completion is not applicable because there are no devices or no devices are connected.
                return -1
            }

            val totalFolderCompletion = localCompletion.totalFolderCompletion

            // Calculate overall sync completion percentage.
            var totalSyncCompletion: Int = if (totalFolderCompletion == 100) {
                totalDeviceCompletion
            } else {
                floor((totalFolderCompletion + totalDeviceCompletion).toDouble() / 2)
                    .toInt()
            }

            // Filter invalid percentage values.
            if (totalSyncCompletion < 0) {
                totalSyncCompletion = 0
            } else if (totalSyncCompletion > 100) {
                totalSyncCompletion = 100
            }
            /*
     LogV("getTotalSyncCompletion: totalSyncCompletion=" + Integer.toString(totalSyncCompletion) + "%, " +
             "folders=" + Integer.toString(totalFolderCompletion) + "%, " +
             "devices=" + Integer.toString(totalDeviceCompletion) + "%");
     */
            return totalSyncCompletion
        }

    /**
     * Requests and parses information about recent changes.
     */
    fun getDiskEvents(limit: Int, listener: OnResultListener<List<DiskEvent>>) {
        GetRequest(
            context, url,
            GetRequest.URI_EVENTS_DISK, apiKey,
            mutableMapOf("limit" to limit.toString()),
            { result: String? ->

                try {
                    val jsonDiskEvents = parseString(result).getAsJsonArray()
                    val diskEvents: MutableList<DiskEvent> = ArrayList(jsonDiskEvents.size())
                    for (i in jsonDiskEvents.size() - 1 downTo 0) {
                        val jsonDiskEvent = jsonDiskEvents.get(i)
                        diskEvents.add(
                            gson.fromJson(jsonDiskEvent, DiskEvent::class.java)
                        )
                    }
                    listener.onResult(diskEvents)
                } catch (e: Exception) {
                    Log.e(TAG, "getDiskEvents: Parsing REST API result failed. result=$result")
                    // Reporting through neither listener would leave a caller waiting for good.
                    listener.onResult(emptyList())
                }
            }, null
        )
    }

    /**
     * Starts the persistent event stream (long-poll of /rest/events). The
     * [onEvent] callback is invoked for each event, together with its raw JSON
     * for handlers that need fields outside [Event].
     */
    fun startEventStream(
        onEvent: (event: Event, json: JsonElement?) -> Unit,
        onStreamGap: (lastDeliveredId: Long, firstMissingId: Long) -> Unit =
            { _, _ -> }
    ) {
        stopEventStream()
        eventStream =
            EventsPoller(context, url, apiKey, onEvent, onStreamGap).also { it.start() }
    }

    fun stopEventStream() {
        eventStream?.stop()
        eventStream = null
    }

    /**
     * Returns status information about the folder with the given id from cache.
     */
    fun getFolderStatus(
        folderId: String?
    ): MutableMap.MutableEntry<FolderStatus, CachedFolderStatus> {
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        val now = System.currentTimeMillis()
        val queryDue = now - (folderStatusQueryTime[folderStatusKey(folderId)] ?: 0L) >=
                Constants.REST_UPDATE_INTERVAL
        if (cacheEntry.key.stateChanged.isEmpty() || queryDue) {
            /**
             * Cache miss because we haven't received a "FolderSummary" event yet, or the
             * cached status has gone stale (e.g. still showing "idle" while the folder
             * is actually scanning/syncing).
             * Query the required information so it will be available on a future call to this function.
             */
            LogV("getFolderStatus: Cache miss, folderId=\"$folderId\". Performing query.")
            folderStatusQueryTime[folderStatusKey(folderId)] = now
            GetRequest(
                context,
                url,
                GetRequest.URI_DB_STATUS,
                apiKey,
                mutableMapOf("folder" to folderId),
                OnSuccessListener { result: String? ->
                    val folder = getFolderByID(folderId)
                    if (folder == null) {
                        Log.e(TAG, "getFolderStatus#GetRequest#onResult: folderId == null")
                        return@OnSuccessListener
                    }
                    // Ignore responses that raced a newer event-driven write, otherwise a
                    // delayed status response could roll the folder state back (e.g. to a
                    // stale "scanning") after an event already applied the correct state.
                    val writeTime = folderStatusWriteTime[folderStatusKey(folderId)] ?: 0L
                    if (writeTime <= now) {
                        localCompletion.setFolderStatus(
                            folderId,
                            folder.paused,
                            gson.fromJson(result, FolderStatus::class.java)
                        )
                    }
                }
            ) { }
        }
        return cacheEntry
    }

    private fun sendBroadcastToApps(intent: Intent) {
        val packageIdList = arrayOf<String?>( // "com.example.syncthingreceiver",
            "org.decsync.cc"
        )
        // intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        val receiveSyncStatusPermission =
            context.packageName + PERMISSION_RECEIVE_SYNC_STATUS_SUFFIX
        for (packageId in packageIdList) {
            intent.setPackage(packageId)
            context.applicationContext.sendBroadcast(intent, receiveSyncStatusPermission)
        }
    }

    fun sendBroadcastFolderSyncComplete(
        deviceId: String?,
        folder: Folder,
        folderState: String?
    ) {
        val intent = Intent()
        intent.action = context.packageName + ACTION_NOTIFY_FOLDER_SYNC_COMPLETE_SUFFIX
        intent.putExtra("deviceId", deviceId)
        intent.putExtra("folderId", folder.id)
        intent.putExtra("folderLabel", folder.label)
        intent.putExtra("folderState", folderState)
        sendBroadcastToApps(intent)
    }

    /**
     * Updates cached folder and device completion info according to event data.
     */
    fun setLocalFolderStatus(
        folderId: String?,
        folderStatus: FolderStatus
    ) {
        folderStatusWriteTime[folderStatusKey(folderId)] = System.currentTimeMillis()
        localCompletion.setFolderStatus(folderId, folderStatus)
        onTotalSyncCompletionChange()
        notifyFolderStateChanged()
    }

    fun setLocalFolderLastItemFinished(
        folderId: String?,
        lastItemFinishedAction: String?,
        lastItemFinishedItem: String?,
        lastItemFinishedTime: String?
    ) {
        /**
         * lastItemFinishedAction RAW data from Syncthing
         * update:     A file was changed or deleted
         */
        var realLastItemFinishedAction: String? = lastItemFinishedAction

        // Reject item names with path traversal or NUL characters.
        val safeItem = lastItemFinishedItem?.takeIf { name ->
            !name.contains('/') && !name.contains('\\') && !name.contains("\u0000") && name != ".." && name.isNotEmpty()
        }

        // Check if the file was updated or deleted in reality.
        if (lastItemFinishedAction == "update" && safeItem != null) {
            getFolderByID(folderId)?.path?.let { path ->
                if (!File(path, safeItem).exists()) {
                    realLastItemFinishedAction = "delete"
                }
            }
        }
        if (folderId != null && lastItemFinishedTime != null && safeItem != null && realLastItemFinishedAction != null) {
            localCompletion.setLastItemFinished(
                folderId,
                realLastItemFinishedAction,
                safeItem,
                lastItemFinishedTime
            )
        }

    }

    fun setRemoteCompletionInfo(
        deviceId: String?,
        folderId: String,
        needBytes: Double,
        completion: Double
    ) {
        val folder = getFolderByID(folderId)
        if (folder == null) {
            Log.e(TAG, "setRemoteCompletionInfo: folderId == null")
            return
        }
        val remoteCompletionInfo = RemoteCompletionInfo()
        if (folder.paused) {
            /**
             * Fixes issue where device sync percentage is displayed 50% on wrapper UI
             * and 100% on Web UI if there are at least two folders syncing with the same device and
             * at least one of them is paused. This is caused by EventProcessor telling us a paused
             * to be 0% complete. To get consistent UI output, we assume 100% completion for paused
             * folders.
             */
            LogV(
                "setRemoteCompletionInfo: Paused folder \"" + folderId + "\" - got " +
                        remoteCompletionInfo.completion + "%, passing on 100%"
            )
            remoteCompletionInfo.completion = 100.0
            remoteCompletionInfo.needBytes = 0.0
        } else {
            remoteCompletionInfo.completion = completion
            remoteCompletionInfo.needBytes = needBytes
        }
        remoteCompletion.setCompletionInfo(deviceId, folderId, remoteCompletionInfo)
        onTotalSyncCompletionChange()

        /**
         * Check if a folder completed synchronization on the local or a remote device.
         * Plan finisher workloads that need to run after folder completion.
         * They will be offloaded to a separate thread later.
         */
        var planGetSyncConflictFiles = false
        var planOnFolderSyncCompleted = false

        val cacheEntry: MutableMap.MutableEntry<FolderStatus, CachedFolderStatus> =
            localCompletion.getFolderStatus(folderId)
        val folderStatus = cacheEntry.key
        val folderIsSyncing = folderStatus.state.contains("sync")
        if (remoteCompletionInfo.completion == 100.0) {
            if (!folderIsSyncing) {
                planGetSyncConflictFiles = true

                val cachedFolderStatus = cacheEntry.value
                if (cachedFolderStatus.remoteIndexUpdated) {
                    localCompletion.setRemoteIndexUpdated(folderId, false)
                    planOnFolderSyncCompleted = true
                }
            }
        }

        if (hasShutdown || scope.coroutineContext[kotlinx.coroutines.Job]?.isActive != true) {
            return
        }

        if (!planGetSyncConflictFiles && !planOnFolderSyncCompleted) {
            return
        }

        scope.launch(Dispatchers.IO) {
            if (hasShutdown) {
                return@launch
            }
            if (planGetSyncConflictFiles) {
                localCompletion.setDiscoveredConflictFiles(
                    folderId,
                    getSyncConflictFiles(folder.path)
                )
            }
            if (planOnFolderSyncCompleted) {
                onFolderSyncCompleted(
                    folder,
                    folderStatus.state,
                    deviceId
                )
            }
        }
    }

    fun onFolderSyncCompleted(
        folder: Folder,
        folderState: String?,
        deviceId: String?
    ) {
        Log.d(TAG, "onFolderSyncCompleted: Completed folder=[" + folder.id + "]")

        // Run folder script set if enabled by user pref.
        folder.id?.let {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

            val folderRunScriptEnabled = sharedPreferences.getBoolean(
                Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(it), false
            )
            if (folderRunScriptEnabled) {
                runScriptSet(
                    folder.path + "/" + Constants.FILENAME_STFOLDER,
                    arrayOf(
                        "sync_complete"
                    )
                )
            }
        }

        // Notify listening third-party apps.
        sendBroadcastFolderSyncComplete(deviceId, folder, folderState)

    }

    fun setRemoteIndexUpdated(
        folderId: String?,
        remoteIndexUpdated: Boolean
    ) {
        localCompletion.setRemoteIndexUpdated(folderId, remoteIndexUpdated)
    }

    fun updateLocalFolderPause(folderId: String?, newPaused: Boolean) {
        // Clear status cache when pausing or resuming the folder.
        folderStatusWriteTime[folderStatusKey(folderId)] = System.currentTimeMillis()
        localCompletion.setFolderStatus(folderId, newPaused, FolderStatus())
        notifyFolderStateChanged()
    }

    fun updateLocalFolderState(folderId: String?, newState: String) {
        val cacheEntry: MutableMap.MutableEntry<FolderStatus, CachedFolderStatus> =
            localCompletion.getFolderStatus(folderId)
        cacheEntry.key.state = newState
        // A "StateChanged" event is authoritative server status, so mark the entry
        // as filled. Without this, getFolderStatus() keeps treating the folder as a
        // cache miss and re-fires /rest/db/status, whose delayed responses can race
        // the event stream and roll the state back (e.g. stuck on "scanning").
        if (cacheEntry.key.stateChanged.isEmpty()) {
            cacheEntry.key.stateChanged = "1"
        }
        folderStatusWriteTime[folderStatusKey(folderId)] = System.currentTimeMillis()
        localCompletion.setFolderStatus(folderId, cacheEntry.key)
        notifyFolderStateChanged()
    }

    private fun notifyFolderStateChanged() {
        _folderStateChanged.tryEmit(Unit)
    }

    fun updateRemoteDeviceConnected(deviceId: String?, newConnected: Boolean) {
        val cacheEntry: Connection? = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry?.connected = newConnected
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    fun updateRemoteDevicePaused(deviceId: String?, newPaused: Boolean) {
        val cacheEntry: Connection? = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry?.connected = false
        cacheEntry?.paused = newPaused
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    /**
     * Returns prettyfied usage report.
     */

    fun getUsageReport(listener: OnResultListener<String?>) {
        GetRequest(
            context,
            url,
            GetRequest.URI_REPORT,
            apiKey,
            null,
            { result: String? ->
                val json = try {
                    parseString(result)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "getUsageReport: Failed to parse the REST API result")
                    null
                }
                listener.onResult(json?.let { prettyPrinter.toJson(it) })
            },
            null
        )
    }

    val isUsageReportingAccepted: Boolean
        get() {
            val options = options
            if (options == null) {
                Log.e(
                    TAG,
                    "isUsageReportingAccepted called while options == null"
                )
                return false
            }
            return options.isUsageReportingAccepted(urVersionMax)
        }

    val isUsageReportingDecided: Boolean
        get() {
            val options = options
            if (options == null) {
                Log.e(
                    TAG,
                    "isUsageReportingDecided called while options == null"
                )
                return true
            }
            return options.isUsageReportingDecided(urVersionMax)
        }

    fun setUsageReporting(acceptUsageReporting: Boolean) {
        val currentOptions = options
        if (currentOptions == null) {
            Log.e(TAG, "setUsageReporting called while options == null")
            return
        }
        currentOptions.urAccepted =
            (if (acceptUsageReporting) urVersionMax else Options.USAGE_REPORTING_DENIED)
        synchronized(configLock) {
            config?.options = currentOptions
        }
    }

    fun downloadSupportBundle(targetFile: File, listener: OnResultListener<Boolean?>?) {
        // Pin to loopback like every other request, so a 0.0.0.0 GUI listen address
        // cannot leak the API key/config onto a routable interface.
        val supportUrl = URL(ApiRequest.forceLoopbackHost(url), GetRequest.URI_DEBUG_SUPPORT)
        val requestBuilder = okhttp3.Request.Builder().url(supportUrl.toString())
        apiKey?.let { requestBuilder.header("X-API-Key", it) }
        val client = SyncthingHttpClients.get(context)
        val call = client.newCall(requestBuilder.build())
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "downloadSupportBundle: Network request failed", e)
                listener?.onResult(false)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "downloadSupportBundle: HTTP ${resp.code}")
                        listener?.onResult(false)
                        return
                    }
                    var failSuccess = true
                    LogV("downloadSupportBundle: Streaming to '${targetFile.path}' ...")
                    try {
                        targetFile.parentFile?.mkdirs()
                        if (!targetFile.exists()) {
                            targetFile.createNewFile()
                        }
                        resp.body.byteStream().use { inputStream ->
                            FileOutputStream(targetFile).use { fileOutputStream ->
                                inputStream.copyTo(fileOutputStream, bufferSize = 8192)
                            }
                        }
                    } catch (e: IOException) {
                        Log.w(
                            TAG,
                            "downloadSupportBundle: Failed to write '${targetFile.path}'",
                            e
                        )
                        failSuccess = false
                    }
                    listener?.onResult(failSuccess)
                }
            }
        })
    }

    /**
     * Event triggered by [RunConditionMonitor] routed here through [SyncthingService].
     */
    fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        synchronized(configLock) {
            var configChanged = false
            // Check if the config has been loaded.
            if (config == null) {
                Log.w(TAG, "applyCustomRunConditions: config is not ready yet.")
                return
            }

            config?.folders?.let { folders ->
                for (folder in folders) {
                    // LogV("applyCustomRunConditions: Processing config of folder(" + folder.getLabel() + ")");
                    val folderCustomSyncConditionsEnabled = sharedPreferences.getBoolean(
                        DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id),
                        false
                    )
                    if (folderCustomSyncConditionsEnabled) {
                        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                            Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                        )
                        LogV("applyCustomRunConditions: f(" + folder.label + ")=" + (if (syncConditionsMet) "1" else "0"))
                        if (folder.paused == syncConditionsMet) {
                            folder.paused = !syncConditionsMet
                            Log.d(
                                TAG,
                                "applyCustomRunConditions: f(" + folder.label + ")=" + (if (syncConditionsMet) ">1" else ">0")
                            )
                            configChanged = true
                        }
                    }
                }
            }
            if (config?.folders == null) {
                Log.d(TAG, "applyCustomRunConditions: mconfig.folders is not ready yet.");
                return;
            }


            // Check if the devices are available from config.
            config?.devices?.let { devices ->
                for (device in devices) {
                    // LogV("applyCustomRunConditions: Processing config of device(" + device.getName() + ")");
                    val deviceCustomSyncConditionsEnabled = sharedPreferences.getBoolean(
                        DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID),
                        false
                    )
                    if (deviceCustomSyncConditionsEnabled) {
                        val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                            Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                        )
                        LogV("applyCustomRunConditions: d(" + device.name + ")=" + (if (syncConditionsMet) "1" else "0"))
                        if (device.paused == syncConditionsMet) {
                            device.paused = !syncConditionsMet
                            Log.d(
                                TAG,
                                "applyCustomRunConditions: d(" + device.name + ")=" + (if (syncConditionsMet) ">1" else ">0")
                            )
                            configChanged = true
                        }
                    }
                }
            }
            if (config?.devices == null) {
                Log.d(TAG, "applyCustomRunConditions: mconfig.devices is not ready yet.");
                return;
            }

            if (configChanged) {
                LogV("applyCustomRunConditions: Sending changed config ...")
                queueConfigSave()
            } else {
                LogV("applyCustomRunConditions: No action was necessary.")
            }
        }
    }

    private fun setVersioningCleanupIntervalS(cleanupIntervalS: Int) {
        synchronized(configLock) {
            config?.folders?.let { folders ->
                for (folder in folders) {
                    folder.versioning?.cleanupIntervalS = cleanupIntervalS
                }
            }

            LogV("Set VersioningCleanupIntervalS to $cleanupIntervalS")
            queueConfigSave()
        }
    }

    private fun onTotalSyncCompletionChange() {
        // LogV("onTotalSyncCompletionChange fired.");
//        if (notificationHandler == null) {
//            return
//        }

        val onlineDeviceCount = remoteCompletion.onlineDeviceCount
        val totalSyncCompletion = totalSyncCompletion
        if ((onlineDeviceCount == lastOnlineDeviceCount) &&
            (totalSyncCompletion == lastTotalSyncCompletion)
        ) {
            return
        }
        notificationHandler.updatePersistentNotification(
            context as SyncthingService,
            false,  // Do not persist previous notification text.
            onlineDeviceCount,
            totalSyncCompletion
        )
        lastOnlineDeviceCount = onlineDeviceCount
        lastTotalSyncCompletion = totalSyncCompletion
    }

    /**
     * Parses [jsonString] as a JSON object, or returns null when it is not one.
     *
     * Success listeners are delivered on the main thread (see
     * [com.micnubinub.syncthing.http.ApiRequest.connect]), so a JsonSyntaxException from a
     * truncated or non-JSON body - a squatter on the Web GUI port is enough - would take
     * the whole process down. Null lets the caller treat it like any other unusable
     * answer.
     */
    private fun parseJsonObjectOrNull(jsonString: String?): JsonObject? = try {
        parseString(jsonString).getAsJsonObject()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "parseJsonObjectOrNull: Failed to parse the REST API result as a JSON object")
        null
    }

    private fun jsonToPrettyFormat(jsonString: String): String? {
        val json = parseString(jsonString).getAsJsonObject()
        return prettyPrinter.toJson(json)
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    fun interface OnConfigChangedListener {
        fun onConfigChanged()
    }

    fun interface OnResultListener<T> {
        fun onResult(t: T)
    }

    fun interface OnApiAvailableListener {
        fun onApiAvailable()
    }

    companion object {
        private const val TAG = "RestApi"

        /**
         * Maximum number of concurrent /rest/db/completion requests fired
         * after a config reload.
         */
        private const val MAX_CONCURRENT_COMPLETION_REQUESTS = 8

        /**
         * How long [shutdown] waits for the core to acknowledge the shutdown request
         * before giving up and letting the caller terminate the process.
         */
        private const val SHUTDOWN_REQUEST_TIMEOUT_MS: Long = 5000

        /**
         * How long the startup handshake of one generation may take before it is failed,
         * see [readConfigFromRestApi]. Bounded so a snapshot that never reports leaves the
         * service in ERROR rather than in STARTING for the lifetime of the process.
         */
        private const val STARTUP_SNAPSHOT_DEADLINE_MS: Long = 60_000

        /**
         * Action name suffix of the intents sent to other apps that subscribed to us.
         * Prefixed with [context.packageName] at send time so the fully-qualified action
         * matches what receiving apps declare.
         */
        private const val ACTION_NOTIFY_FOLDER_SYNC_COMPLETE_SUFFIX =
            ".ACTION_NOTIFY_FOLDER_SYNC_COMPLETE"

        /**
         * Permission name suffix for apps receiving our broadcast intents.
         * Prefixed with [context.packageName] at send time so the fully-qualified permission
         * matches the declaration in the manifest.
         */
        private const val PERMISSION_RECEIVE_SYNC_STATUS_SUFFIX = ".permission.RECEIVE_SYNC_STATUS"
    }
}
