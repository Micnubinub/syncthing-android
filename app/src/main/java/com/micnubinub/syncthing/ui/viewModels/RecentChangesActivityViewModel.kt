package com.micnubinub.syncthing.ui.viewModels

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.DiskEvent
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.Random

@Stable
data class RecentChangesActivityState(
    val isLoading: Boolean = false,
    val items: List<DiskEvent> = emptyList()
)

sealed interface RecentChangesActivityAction {
    data object LoadData : RecentChangesActivityAction
}

class RecentChangesActivityViewModel : ViewModel() {
    private val gson = Gson()
    private val _state = MutableStateFlow(RecentChangesActivityState())
    val state: StateFlow<RecentChangesActivityState> = _state.asStateFlow()

    private var service: SyncthingService? = null
    private var serviceState = SyncthingService.State.INIT
    private var thisDeviceLabel = ""
    private var devices: List<Device>? = null
    private var localDeviceId: String? = null
    private var loadGeneration = 0L

    fun setService(service: SyncthingService?) {
        this.service = service
    }

    fun setThisDeviceLabel(thisDeviceLabel: String) {
        this.thisDeviceLabel = thisDeviceLabel
    }

    fun onServiceStateChange(currentState: SyncthingService.State) {
        serviceState = currentState
        if (currentState == SyncthingService.State.ACTIVE) {
            onAction(RecentChangesActivityAction.LoadData)
        }
    }

    fun onAction(action: RecentChangesActivityAction) {
        when (action) {
            RecentChangesActivityAction.LoadData -> loadData()
        }
    }

    private fun loadData() {
        if (serviceState != SyncthingService.State.ACTIVE) {
            return
        }
        val restApi = service?.api
        if (restApi == null) {
            Log.e(TAG, "loadData: syncthingService == null")
            return
        }
        _state.update { it.copy(isLoading = true) }
        devices = restApi.getDevices(true)
        localDeviceId = restApi.localDevice?.deviceID
        val generation = ++loadGeneration
        restApi.getDiskEvents(
            DISK_EVENT_LIMIT,
            RestApi.OnResultListener { diskEvents: List<DiskEvent> ->
                if (generation != loadGeneration) return@OnResultListener
                onReceiveDiskEvents(diskEvents)
            })
        if (Constants.ENABLE_TEST_DATA) {
            onReceiveDiskEvents(mutableListOf())
        }
    }

    private fun onReceiveDiskEvents(diskEvents: List<DiskEvent>) {
        // Hide disk events that are useless to display.
        val cleanEvents = diskEvents.toMutableList()
        if (Constants.ENABLE_TEST_DATA) {
            getTestData(cleanEvents)
        }


        removeUselessDiskEvents(cleanEvents)
        // Replace "modifiedBy" partial device ID by readable device name.
        resolveModifiedBy(cleanEvents)

        _state.update { it.copy(isLoading = false, items = cleanEvents.filterNotNull().toList()) }
    }

    private fun resolveModifiedBy(diskEvents: List<DiskEvent>) {
        val devices = devices ?: return
        val localDeviceId = localDeviceId ?: return
        for (diskEvent in diskEvents) {
            val modifiedBy = diskEvent.data.modifiedBy
            if (modifiedBy.isEmpty()) {
                continue
            }
            for (device in devices) {
                val deviceId = device.deviceID
                if (deviceId != null && deviceId.startsWith(modifiedBy)) {
                    diskEvent.data.modifiedBy =
                        if (deviceId == localDeviceId) thisDeviceLabel
                        else device.displayName.orEmpty()
                    break
                }
            }
        }
    }

    private fun getTestData(diskEvents: MutableList<DiskEvent>) {
        val random = Random()

        /**
         * Items on UI without "removeUselessDiskEvents"
         * 10
         * Items on UI after "removeUselessDiskEvents"
         * 6
         */
        var id = 11
        val fakeDiskEvent = DiskEvent()
        fakeDiskEvent.globalID = 84
        fakeDiskEvent.type = "RemoteChangeDetected"
        fakeDiskEvent.data.folder = "abcd-efgh"
        fakeDiskEvent.data.folderID = "abcd-efgh"
        fakeDiskEvent.data.label = "label_abcd-efgh"
        fakeDiskEvent.data.modifiedBy = "SRV01"

        // - "Camera - Copy" folder
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "deleted"
        fakeDiskEvent.data.path = "Camera - Copy"
        fakeDiskEvent.data.type = "dir"
        fakeDiskEvent.time = "2018-10-29T15:18:52.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // - "document2.txt"
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "deleted"
        fakeDiskEvent.data.path = "document2.txt"
        fakeDiskEvent.data.type = "file"
        fakeDiskEvent.time = "2020-04-13T15:01:00.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // + "document2.txt" - to be removed by Pass 2
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "added"
        fakeDiskEvent.data.path = "document2.txt"
        fakeDiskEvent.time = "2020-04-13T15:00:00.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // - "Camera - Copy/IMG_20200413_130936.jpg" - to be removed by Pass 3
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "deleted"
        fakeDiskEvent.data.path = "Camera - Copy/IMG_20200413_130936.jpg"
        fakeDiskEvent.time = "2018-10-29T15:18:50.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // + "document1.txt"
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "added"
        fakeDiskEvent.data.path = "document1.txt"
        fakeDiskEvent.time = "2018-10-29T17:08:00.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // - "Camera - Copy/IMG_20200413_132532.jpg" - to be removed by Pass 3
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "deleted"
        fakeDiskEvent.data.path = "Camera - Copy/IMG_20200413_132532.jpg"
        fakeDiskEvent.time = "2018-10-29T15:18:50.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // - "Camera - Copy/IMG_20200413_132049.jpg" - to be removed by Pass 3
        fakeDiskEvent.id = (--id).toLong()
        fakeDiskEvent.data.action = "deleted"
        fakeDiskEvent.data.path = "Camera - Copy/IMG_20200413_132049.jpg"
        fakeDiskEvent.time = "2018-10-29T15:18:50.6183215+01:00"
        addFakeDiskEvent(diskEvents, fakeDiskEvent)

        // - "document1.txt"
        for (i in --id downTo 1) {
            fakeDiskEvent.id = i.toLong()
            fakeDiskEvent.data.action = "deleted"
            fakeDiskEvent.data.path = "document1.txt"
            fakeDiskEvent.time = "2018-10-28T14:08:" + String.format(
                Locale.getDefault(),
                "%02d",
                random.nextInt(60)
            ) +
                    ".6183215+01:00"
            addFakeDiskEvent(diskEvents, fakeDiskEvent)
        }
    }

    private fun addFakeDiskEvent(
        diskEvents: MutableList<DiskEvent>,
        fakeDiskEvent: DiskEvent
    ) {
        diskEvents.add(deepCopy(fakeDiskEvent))
    }

    /**
     * Pass 1
     * Check if a file has been created and deleted afterwards.
     * We can remove the creation event for the non-existent file.
     */
    private fun removeUselessDiskEvents(diskEvents: MutableList<DiskEvent>) {
        run {
            val it = diskEvents.iterator()
            while (it.hasNext()) {
                val diskEvent = it.next()
                val it2 = diskEvents.iterator()
                while (it2.hasNext()) {
                    val diskEvent2 = it2.next()
                    if (diskEvent2.id > diskEvent.id) {
                        // diskEvent2 occured after diskEvent.
                        if (diskEvent2.data.path == diskEvent.data.path &&
                            diskEvent.data.action == "added" &&
                            diskEvent2.data.action == "deleted"
                        ) {
                            it.remove()
                            break
                        }
                    }
                }
            }
        }

        /**
         * Pass 2
         * Check if a folder has been removed.
         * We can remove prior events corresponding to the folder.
         */
        val it = diskEvents.iterator()
        while (it.hasNext()) {
            val diskEvent = it.next()
            val it2 = diskEvents.iterator()
            while (it2.hasNext()) {
                val diskEvent2 = it2.next()
                if (diskEvent2.id > diskEvent.id) {
                    // diskEvent2 occured after diskEvent.
                    if (diskEvent2.data.type == "dir" &&
                        diskEvent2.data.action == "deleted" &&
                        diskEvent.data.path.startsWith(diskEvent2.data.path + "/")
                    ) {
                        it.remove()
                        break
                    }
                }
            }
        }
    }

    /**
     * Returns a deep copy of the object.
     *
     * This method uses Gson and only works with objects that can be converted with Gson.
     */
    private fun deepCopy(obj: DiskEvent): DiskEvent {
        return gson.fromJson(gson.toJson(obj), DiskEvent::class.java)
    }

    companion object {
        private const val TAG = "RecentChangesActivityViewModel"

        private const val DISK_EVENT_LIMIT = 100
    }
}
