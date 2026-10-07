package com.micnubinub.syncthing.service

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Trace
import android.provider.MediaStore
import android.util.Log
import androidx.core.util.Consumer
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.model.Event
import com.micnubinub.syncthing.model.FolderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

// import com.google.gson.Gson;
/**
 * Run by the syncthing service to convert syncthing events into local broadcasts.
 *
 * It consumes the event stream pushed by [RestApi.startEventStream] (long-poll
 * of /rest/events).
 */
class EventProcessor(val context: Context, val restApi: RestApi?) {
    private var ENABLE_VERBOSE_LOG = false
    private var scope: CoroutineScope? = null
    private val gson = GsonBuilder().create()

    /**
     * ItemFinished events accumulated within the current batch window, flushed
     * as a single MediaScannerConnection.scanFile / ContentResolver.delete call.
     */
    private val pendingScanFiles = ArrayList<String>()
    private val pendingDeleteFiles = ArrayList<String>()

    @Volatile
    private var shutdown = true


    @Inject
    lateinit var preferences: SharedPreferences

    @Inject
    lateinit var notificationHandler: NotificationHandler

    @Volatile
    private var consumerJob: Job? = null

    /**
     * Number of events waiting in [events]. The channel does not expose its occupancy
     * and cannot refuse a send while [BufferOverflow.DROP_OLDEST] is set, so this
     * mirrors it in order to decide whether a droppable event has to be shed.
     */
    private val queuedEvents = AtomicInteger(0)

    /**
     * Events pushed from the event stream and consumed on [Dispatchers.IO].
     * Bounded so a burst of events cannot grow memory without limit; if the
     * consumer falls behind, the oldest events are dropped. A drop is not
     * harmless, so [onUndeliveredElement] records it and the consumer rebuilds
     * the derived state from the core instead of leaving it stale.
     */
    private val events = Channel<Pair<Event, JsonElement?>>(
        capacity = EVENT_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropped ->
            /**
             * [BufferOverflow.DROP_OLDEST] silently evicts the oldest element instead of
             * refusing the send, so the occupancy mirror has to be corrected here. Left
             * uncorrected it only ever grows, and [enqueue] then sheds every later
             * progress event for the lifetime of the object.
             */
            queuedEvents.decrementAndGet()
            markStateIncomplete("dropped ${dropped.first.type}")
        }
    )


    /**
     * Set when an event was lost (gap in the core's stream, queue overflow) or failed
     * to process. The caches in [RestApi] are then behind the core, so they have to be
     * rebuilt from the core rather than patched forward from the events that did
     * arrive. Read and written from the event-stream thread as well as the consumer,
     * hence [Volatile].
     */
    @Volatile
    private var stateIncomplete = false

    init {
        (context.applicationContext as SyncthingApp).component().inject(this)
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(preferences)
    }

    fun start() {
        if (shutdown) {
            Log.d(TAG, "Starting event processor.")
        }
        shutdown = false
        val activeScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            scope = it
        }
        restApi?.startEventStream(
            { event, json -> enqueue(event, json) },
            { lastDeliveredId, firstMissingId ->
                markStateIncomplete(
                    "core dropped event ids ${lastDeliveredId + 1}..${firstMissingId - 1}"
                )
            }
        )
        consumerJob?.cancel()
        consumerJob = activeScope.launch {
            consumeEvents()
        }
    }

    /**
     * Enqueues an event for processing, protecting the events that a later event
     * cannot replace.
     *
     * The queue drops its oldest entry when full, so a burst of progress chatter could
     * push out a ConfigSaved or a connect/disconnect and leave the derived state
     * permanently wrong with nothing left to correct it. A supersedable event that
     * arrives to a full queue is therefore dropped here, leaving the room for the
     * events that matter.
     */
    private fun enqueue(event: Event, json: JsonElement?) {
        if (isSupersedable(event) && queuedEvents.get() >= EVENT_CHANNEL_CAPACITY) {
            markStateIncomplete("dropped supersedable ${event.type}")
            return
        }
        if (events.trySend(event to json).isSuccess) {
            queuedEvents.incrementAndGet()
        } else {
            markStateIncomplete("queue refused ${event.type}")
        }
    }

    /**
     * True if a later event makes this one irrelevant, so losing it cannot leave the
     * derived state wrong.
     */
    private fun isSupersedable(event: Event): Boolean = when (event.type) {
        // Progress samples: the next one for the same device/folder replaces them, and
        // they are by far the most frequent events.
        "DownloadProgress", "RemoteDownloadProgress", "FolderScanProgress" -> true

        // Handled as a no-op by [onEvent], so losing them changes nothing.
        "Ping", "Starting", "StartupComplete" -> true

        else -> false
    }

    /**
     * Records that the event stream lost or failed on an event. The consumer performs
     * the actual reload, which also coalesces a burst of drops into one reload.
     */
    private fun markStateIncomplete(reason: String) {
        if (stateIncomplete) {
            return
        }
        stateIncomplete = true
        Log.w(TAG, "Event stream incomplete: $reason")
    }

    /**
     * Rebuilds the derived state from the core after an event was lost, so the UI
     * cannot keep showing state the core has already moved past.
     */
    private fun reloadIncompleteState() {
        if (!stateIncomplete) {
            return
        }
        stateIncomplete = false
        val api = restApi ?: return
        Log.i(TAG, "Reloading derived state from the core after an incomplete event stream")
        // Drops the cached folder/device state and its rate limiters, so the next
        // query reads authoritative values from the core again.
        api.clearCaches()
        // A folder or device offer that arrived in the lost stretch was never shown.
        api.showPendingFolderNotifications()
    }

    private suspend fun consumeEvents() {
        // The liveness of this loop is the liveness of this coroutine. Testing the
        // [consumerJob] field instead would race its own assignment: launch() returns
        // before the block runs, so the coroutine can observe null (or the job of a
        // previous start) and exit immediately, leaving no event processed at all.
        while (currentCoroutineContext().isActive) {
            // Drain the stream; once it stays quiet for FLUSH_DEBOUNCE_MS, flush the
            // ItemFinished batch so multiple files result in one MediaScanner/MediaStore
            // call instead of one per file.
            val next = withTimeoutOrNull(FLUSH_DEBOUNCE_MS.milliseconds) { events.receive() }
            if (next == null) {
                flushItemFinishedBatch()
                // Also here, so a stream that goes quiet after a gap is still repaired.
                reloadIncompleteState()
                continue
            }
            val (event, json) = next
            queuedEvents.decrementAndGet()
            Trace.beginSection("EventProcessor.onEvent")
            try {
                reloadIncompleteState()
                onEvent(event, json)
                // Flush as soon as a batch fills up so a sustained stream of
                // ItemFinished events cannot grow the pending lists without bound.
                if (pendingScanFiles.size >= ITEM_BATCH_SIZE ||
                    pendingDeleteFiles.size >= ITEM_BATCH_SIZE
                ) {
                    flushItemFinishedBatch()
                }
            } catch (e: Exception) {
                // A malformed event must never kill the consumer loop, otherwise
                // all subsequent summaries/notifications are lost. It does mean the
                // derived state is now behind, hence the reload on the next event.
                markStateIncomplete("failed to process ${event.type}")
                Log.e(TAG, "onEvent: Failed to process event " + event.type, e)
            } finally {
                Trace.endSection()
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping event processor.")
        shutdown = true
        restApi?.stopEventStream()
        consumerJob?.cancel()
        scope?.cancel()
        scope = null
        /**
         * Drop whatever the consumer never got. Those events belong to the core
         * generation that is going away, so replaying them in the next session would
         * report state the new core never had.
         */
        while (events.tryReceive().isSuccess) {
            // Drained rather than processed, so the occupancy mirror goes back to zero.
        }
        queuedEvents.set(0)
    }

    /**
     * Performs the actual event handling.
     */
    fun onEvent(event: Event, json: JsonElement?) {
        when (event.type) {
            "ConfigSaved" -> if (restApi != null) {
                LogV("Forwarding ConfigSaved event to RestApi to get the updated config.")
                restApi.reloadConfig()
            }

            "DeviceConnected" -> {
                restApi?.updateRemoteDeviceConnected(
                    event.data?.get("id") as? String?,  // deviceId
                    true
                )
                // A folder offer may have been missed while the event stream was
                // reconnecting; re-check pending folders on every connect.
                restApi?.showPendingFolderNotifications()
            }

            "DeviceDisconnected" -> restApi?.updateRemoteDeviceConnected(
                event.data?.get("id") as? String?,  // deviceId
                false
            )

            "DevicePaused" -> restApi?.updateRemoteDevicePaused(
                event.data?.get("device") as? String?,  // deviceId
                true
            )

            "DeviceResumed" -> restApi?.updateRemoteDevicePaused(
                event.data?.get("device") as? String?,  // deviceId
                false
            )

            "FolderCompletion" -> onFolderCompletion(event.data)
            "FolderErrors" -> {
                LogV("Event " + event.type + ", data " + event.data)
                onFolderErrors(json)
            }

            "FolderPaused" -> onFolderPaused(
                event.data?.get("id") as? String? // folderId
            )

            "FolderResumed" -> onFolderResumed(
                event.data?.get("id") as? String? // folderId
            )

            "FolderSummary" -> onFolderSummary(
                json,
                event.data?.get("folder") as? String? // folderId
            )

            "ItemFinished" -> {
                val action = event.data?.get("action") as? String
                val error = event.data?.get("error") as? String?
                val folderId = event.data?.get("folder") as? String?
                val relativeFilePath = event.data?.get("item") as? String?

                // Lookup folder.path for the given folder.id if all fields were contained in the event.data.
                var folderPath: String? = null
                var folderType: String? = null
                if (!action.isNullOrEmpty() && !folderId.isNullOrEmpty() && !
                    relativeFilePath.isNullOrEmpty()
                ) {
                    restApi?.folders?.let {
                        for (folder in it) {
                            if (folder.id == folderId) {
                                folderPath = folder.path
                                folderType = folder.type
                                break
                            }
                        }
                    }

                }
                if (!folderPath.isNullOrEmpty() || !folderType.isNullOrEmpty()) {
                    if (error.isNullOrEmpty()) {
                        // We don't intend to show errors as the last synced item on the UI.
                        restApi?.setLocalFolderLastItemFinished(
                            folderId,
                            action,
                            relativeFilePath,
                            event.time
                        )
                    }
                    enqueueItemFinished(
                        action,
                        error,
                        folderType,
                        folderPath + File.separator + relativeFilePath
                    )
                } else {
                    Log.w(
                        TAG,
                        "ItemFinished: Failed to determine folder.path for folder.id=\"" + (if (
                            folderId.isNullOrEmpty()
                        ) "" else folderId) + "\""
                    )
                }
            }

            "LocalIndexUpdated" -> {
                LogV("Event " + event.type + ", data " + event.data)
                onLocalIndexUpdated(json, event.data?.get("folder") as? String?, event.time)
            }

            "PendingDevicesChanged" -> mapNullable<MutableMap<String, String?>?>(
                event.data?.get("added") as? MutableList<MutableMap<String, String?>?>?,
                Consumer { added: MutableMap<String, String?>? ->
                    added?.let { this.onPendingDevicesChanged(it) }
                })

            "PendingFoldersChanged" -> mapNullable<MutableMap<String, Any?>?>(
                event.data?.get("added") as? MutableList<MutableMap<String, Any?>?>?,
                Consumer { added: MutableMap<String, Any?>? ->
                    added?.let { this.onPendingFoldersChanged(it) }
                })

            "Ping" -> {}
            "StateChanged" -> onStateChanged(
                event.data?.get("folder") as? String?,  // folderId
                event.data?.get("to") as? String?
            )

            "DeviceDiscovered", "DownloadProgress", "FolderScanProgress", "FolderWatchStateChanged", "ItemStarted", "ListenAddressesChanged", "LoginAttempt", "RemoteDownloadProgress", "RemoteIndexUpdated" -> onRemoteIndexUpdated(
                event.data?.get("device") as? String?,  // deviceId
                event.data?.get("folder") as? String?,  // folderId
                event.data?.get("items") as? Double? ?: 0.0
            )

            "Starting", "StartupComplete" -> LogV("Ignored event " + event.type + ", data " + event.data)
            else -> Log.d(TAG, "Unhandled event " + event.type)
        }
    }

    private fun onPendingDevicesChanged(added: MutableMap<String, String?>) {
        val deviceId = added["deviceID"]
        val deviceName = added["name"]
        val deviceAddress = added["address"]
        if (deviceId == null) {
            return
        }
        Log.d(TAG, "Unknown device '$deviceName' ($deviceId) wants to connect")
        // Show device approve/ignore notification.
        deviceName?.let {
            notificationHandler.showDeviceConnectNotification(deviceId, it, deviceAddress)
        }
    }

    private fun onPendingFoldersChanged(added: MutableMap<String, Any?>) {
        val deviceId = added["deviceID"] as? String ?: ""
        val folderId = added["folderID"] as? String ?: ""
        val folderLabel = added["folderLabel"] as? String ?: ""
        val receiveEncrypted = added["receiveEncrypted"] as? Boolean
        val remoteEncrypted = added["remoteEncrypted"] as? Boolean

        if (deviceId.isEmpty() || folderId.isEmpty()) {
            return
        }

        Log.d(
            TAG, "Device '" + deviceId + "' wants to share folder '" +
                    folderLabel + "' (" + folderId + ")"
        )
        // Find the deviceName corresponding to the deviceId.
        var deviceName: String? = null
        restApi?.getDevices(false)?.let {
            for (d in it) {
                if (d.deviceID == deviceId) {
                    deviceName = d.displayName
                    break
                }
            }
        }

        val isNewFolder = restApi?.folders?.none { it.id == folderId } == true
        // Show folder approve/ignore notification.
        notificationHandler.showFolderShareNotification(
            deviceId,
            deviceName,
            folderId,
            folderLabel,
            receiveEncrypted,
            remoteEncrypted,
            isNewFolder
        )
    }

    private fun onFolderCompletion(eventData: MutableMap<String, Any>?) {
        eventData?.let {
            restApi?.setRemoteCompletionInfo(
                eventData["device"] as? String?,  // deviceId
                eventData["folder"] as? String ?: "",  // folderId
                eventData["needBytes"] as? Double ?: 0.0,
                eventData["completion"] as? Double ?: 0.0
            )
        }
    }

    private fun onFolderErrors(json: JsonElement?) {
        val data = (json as? JsonObject)?.get("data")
        if (data == null) {
            Log.e(TAG, "onFolderErrors: data == null")
            return
        }
        val errors = (data as? JsonObject)?.get("errors") as? JsonArray?
        if (errors == null) {
            Log.e(TAG, "onFolderErrors: errors == null")
            return
        }
        for (i in 0..<errors.size()) {
            (try {
                errors.get(i).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "onFolderErrors: Failed to parse error #$i", e)
                null
            })?.let { error ->
                val strError = error.get("error").toString()
                val strPath = error.get("path").toString()

                if (strError.isNotEmpty() && strPath.isNotEmpty() &&
                    strError.contains("insufficient space in basic")
                ) {
                    val segments: List<String> =
                        strPath.split(File.separator.toRegex()).dropLastWhile { it.isEmpty() }
                    val shortenedFileAndFolder =
                        if (segments.size < 2) strPath else segments[segments.size - 2] + File.separator + segments[segments.size - 1]
                    notificationHandler.showCrashedNotification(
                        R.string.notification_out_of_disk_space,
                        shortenedFileAndFolder
                    )
                }
            }
        }
    }

    private fun onFolderPaused(folderId: String?) {
        restApi?.updateLocalFolderPause(folderId, true)
    }

    private fun onFolderResumed(folderId: String?) {
        restApi?.updateLocalFolderPause(folderId, false)
    }

    private fun onFolderSummary(json: JsonElement?, folderId: String?) {
        val data = (json as? JsonObject)?.get("data")
        if (data == null) {
            Log.e(TAG, "onFolderSummary: data == null")
            return
        }
        val summary = (data as? JsonObject)?.get("summary")
        if (summary == null) {
            Log.e(TAG, "onFolderSummary: summary == null")
            return
        }

        val folderStatus: FolderStatus = try {
            gson.fromJson(summary, FolderStatus::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "onFolderSummary: gson.fromJson failed", e)
            return
        }
        restApi?.setLocalFolderStatus(folderId, folderStatus)
    }


    /**
     * Precondition: action != null
     *
     * Queues the ItemFinished event for the end of the current poll cycle so
     * that multiple events result in a single MediaScannerConnection.scanFile
     * or ContentResolver.delete call instead of one per file.
     */
    private fun enqueueItemFinished(
        action: String?,
        error: String?,
        folderType: String?,
        fullFilePath: String
    ) {
        if (!error.isNullOrEmpty()) {
            Log.e(TAG, "onItemFinished: Error \"$error\" reported on file: $fullFilePath")
            if (error.contains("no space left on device")) {
                val segments: List<String> =
                    fullFilePath.split(File.separator.toRegex()).dropLastWhile { it.isEmpty() }
                val shortenedFileAndFolder =
                    if (segments.size < 2) fullFilePath else segments[segments.size - 2] + File.separator + segments[segments.size - 1]
                notificationHandler.showCrashedNotification(
                    R.string.notification_out_of_disk_space,
                    shortenedFileAndFolder
                )
            }
            return
        }

        if (folderType == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
            // Skip notifying Android's MediaStore, MediaScanner.
            return
        }

        when (action) {
            "delete" -> {
                if (File(fullFilePath).exists()) {
                    Log.i(
                        TAG,
                        "onItemFinished: MediaStore, Skip file deletion because file exists: $fullFilePath"
                    )
                } else {
                    Log.i(
                        TAG,
                        "onItemFinished: MediaStore, Queueing file for deletion: $fullFilePath"
                    )
                    pendingDeleteFiles.add(fullFilePath)
                }
            }

            "update" -> {
                Log.i(TAG, "onItemFinished: MediaScanner, Queueing file for rescan: $fullFilePath")
                pendingScanFiles.add(fullFilePath)
            }

            "metadata" -> Log.i(TAG, "onItemFinished: MediaScanner, Skipping file: $fullFilePath")
            else -> Log.w(TAG, "onItemFinished: Unhandled action \"$action\"")
        }
    }

    /**
     * Flushes all ItemFinished events accumulated during the current poll
     * cycle as batched MediaScanner/MediaStore operations.
     */
    private fun flushItemFinishedBatch() {
        if (pendingDeleteFiles.isNotEmpty()) {
            val paths = pendingDeleteFiles.toList()
            pendingDeleteFiles.clear()
            scope?.launch(Dispatchers.IO) {
                val resolver = context.contentResolver
                val contentUri = MediaStore.Files.getContentUri("external")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // On API 29+ the _DATA column is restricted; query by RELATIVE_PATH +
                    // DISPLAY_NAME so the delete actually removes the stale MediaStore rows.
                    for (path in paths) {
                        val file = File(path)
                        val relativePath = file.parentFile?.path?.let { p ->
                            // MediaStore RELATIVE_PATH expects a trailing slash stripped path
                            // relative to the storage volume root.
                            val storageRoot =
                                android.os.Environment.getExternalStorageDirectory().path
                            p.removePrefix(storageRoot).removePrefix("/")
                        } ?: continue
                        val selection =
                            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                        val selectionArgs = arrayOf(relativePath, file.name)
                        val deleted = resolver.delete(contentUri, selection, selectionArgs)
                        if (deleted >= 1) {
                            Log.v(
                                TAG,
                                "onItemFinished: onDeleteComplete: [ok] deleted=$deleted file=${file.name}"
                            )
                        }
                    }
                } else {
                    // Chunk the IN clause so a large batch never exceeds SQLite's
                    // bound-variable limit or the Binder transaction size.
                    for (chunk in paths.chunked(DELETE_BATCH_SIZE)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        val deleted = resolver.delete(
                            contentUri,
                            "${MediaStore.Images.ImageColumns.DATA} IN ($placeholders)",
                            chunk.toTypedArray()
                        )
                        if (deleted >= 1) {
                            Log.v(
                                TAG,
                                "onItemFinished: onDeleteComplete: [ok] deleted=$deleted files"
                            )
                        }
                    }
                }
            }
        }
        if (pendingScanFiles.isNotEmpty()) {
            val paths = pendingScanFiles.toList()
            pendingScanFiles.clear()
            Log.i(TAG, "onItemFinished: MediaScanner, Rescanning ${paths.size} files")
            for (chunk in paths.chunked(SCAN_BATCH_SIZE)) {
                MediaScannerConnection.scanFile(
                    context, chunk.toTypedArray(),
                    null, null
                )
            }
        }
    }

    private fun onLocalIndexUpdated(
        json: JsonElement?,
        folderId: String?,
        dateTimeStamp: String?
    ) {
        val data = (json as? JsonObject)?.get("data")
        if (data == null) {
            Log.e(TAG, "onLocalIndexUpdated: data == null")
            return
        }
        val filenames = (data as? JsonObject)?.get("filenames") as? JsonArray?
        if (filenames == null) {
            Log.e(TAG, "onLocalIndexUpdated: filenames == null")
            return
        }
        for (i in 0..<filenames.size()) {
            var filename = (filenames.get(i) as JsonElement).toString()
            if (filename.isNotEmpty()) {
                filename = filename.replace("^\"|\"$".toRegex(), "")
                LogV("onLocalIndexUpdated: filename=[$filename], time=[$dateTimeStamp]")
                if (i == filenames.size() - 1) {
                    // Send the last (latest) local change to the UI.
                    restApi?.setLocalFolderLastItemFinished(
                        folderId,
                        "update",
                        filename,
                        dateTimeStamp
                    )
                }
            }
        }
    }

    /*
    private void onRemoteDownloadProgress(final String deviceId, 
                                                final String folderId,
                                                final JsonObject state) {
        if (state == null) {
            LogV("onRemoteDownloadProgress: state == null");
            return;
        }                        
        LogV("onRemoteDownloadProgress: state=[" + state + "]");
    }
    */
    private fun onRemoteIndexUpdated(
        deviceId: String?,
        folderId: String?,
        items: Double?
    ) {
        if (deviceId == null || folderId == null || items == null) {
            return
        }
        // LogV("onRemoteIndexUpdated: deviceId=[" + deviceId + "], folder=[" + folderId + "], items=" + items);
        if (items > 0) {
            restApi?.setRemoteIndexUpdated(folderId, true)
        }
    }

    /**
     * Emitted when a folder changes state.
     */
    private fun onStateChanged(folderId: String?, newState: String?) {
        newState?.let { restApi?.updateLocalFolderState(folderId, it) }
        // LogV("onStateChanged: folder=[" + folderId + "], newState=[" + newState + "]");
    }

    private fun <T> mapNullable(l: MutableList<T?>?, c: Consumer<T?>) {
        if (l != null) {
            for (m in l) {
                c.accept(m)
            }
        }
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "EventProcessor"

        /**
         * Quiet period after the last received ItemFinished event before batched
         * MediaScanner/MediaStore operations are flushed.
         */
        private const val FLUSH_DEBOUNCE_MS = 1000L

        /**
         * Number of pending ItemFinished events that triggers an early flush even
         * while events are still arriving, keeping the pending lists bounded.
         */
        private const val ITEM_BATCH_SIZE = 500

        /**
         * Chunk size for MediaStore deletes (SQLite's default limit is 999 bound
         * variables per statement) and MediaScanner scans (keeps each Binder call
         * well under the 1MB transaction limit).
         */
        private const val DELETE_BATCH_SIZE = 500
        private const val SCAN_BATCH_SIZE = 500

        /**
         * Cap on the in-flight event queue; when exceeded the oldest events are
         * dropped so a burst cannot grow memory without bound.
         */
        private const val EVENT_CHANNEL_CAPACITY = 1024
    }
}
