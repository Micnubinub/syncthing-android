package com.micnubinub.syncthing.ui.viewModels

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.service.Constants.GUI_UPDATE_INTERVAL
import com.micnubinub.syncthing.service.Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml.OpenConfigException
import com.micnubinub.syncthing.util.Util.DEVICES_COMPARATOR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot of the per-device status data the screen needs.
 * Extracted from [RestApi] caches so composition only reads primitives.
 */
@Stable
data class DeviceItemStatusUi(
    val connected: Boolean = false,
    val completion: Int = 100,
    val needBytes: Double = 0.0,
    val inBits: Long = 0,
    val outBits: Long = 0,
)

@Stable
data class DeviceFragmentState(
    val isLoading: Boolean = false,
    val devices: List<Device> = emptyList(),
    val deviceStatuses: Map<String, DeviceItemStatusUi> = emptyMap(),
    val sharedFoldersByDevice: Map<String, List<Folder>> = emptyMap(),
    val lastSeenByDevice: Map<String, String> = emptyMap()
)

sealed interface DeviceFragmentAction {
    data object LoadData : DeviceFragmentAction
    data object StopPolling : DeviceFragmentAction
}

class DeviceFragmentViewModel : ViewModel() {
    private val _state = MutableStateFlow(DeviceFragmentState())
    val state: StateFlow<DeviceFragmentState> = _state.asStateFlow()
    private var pollingJob: Job? = null

    private var appContext: Context? = null

    private val _service = MutableStateFlow<SyncthingService?>(null)
    val service: StateFlow<SyncthingService?> = _service.asStateFlow()

    val api: RestApi?
        get() = _service.value?.api

    private val _configRouter = MutableStateFlow<ConfigRouter?>(null)
    val configRouter: StateFlow<ConfigRouter?> = _configRouter.asStateFlow()

    fun setConfigRouter(configRouter: ConfigRouter?) {
        if (configRouter != null) {
            _configRouter.value = configRouter
        }
    }

    fun setService(service: SyncthingService?) {
        _service.value = service
    }

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun onAction(action: DeviceFragmentAction) {
        when (action) {
            DeviceFragmentAction.LoadData -> loadData()
            DeviceFragmentAction.StopPolling -> stopPolling()
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun loadData() {
        try {
            pollingJob?.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            // Ignore; cancelling the previous polling job is best-effort.
        }
        pollingJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.devices.isEmpty()) }
            while (true) {
                val restApi = api
                configRouter.value?.let { router ->
                    val devices = try {
                        withContext(Dispatchers.IO) {
                            router.getDevices(restApi, false)
                        }
                            .orEmpty()
                            .filterNotNull()
                            .sortedWith(DEVICES_COMPARATOR)
                    } catch (e: OpenConfigException) {
                        Log.e(
                            TAG,
                            "Failed to parse existing config. You will need support from here ..."
                        )
                        _state.value.devices
                    }
                    if (restApi != null && restApi.isConfigLoaded && _service.value?.currentState == SyncthingService.State.ACTIVE) {
                        restApi.getRemoteDeviceStatus("")
                    }
                    val sharedFoldersByDevice = try {
                        withContext(Dispatchers.IO) {
                            buildSharedFoldersByDevice(router.getFolders(restApi))
                        }
                    } catch (e: OpenConfigException) {
                        Log.e(TAG, "Failed to parse folders to determine shared folders", e)
                        _state.value.sharedFoldersByDevice
                    }
                    val deviceStatuses = pollDeviceStatuses(devices, restApi)
                    val lastSeenByDevice = pollLastSeen(devices)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            devices = devices,
                            deviceStatuses = deviceStatuses,
                            sharedFoldersByDevice = sharedFoldersByDevice,
                            lastSeenByDevice = lastSeenByDevice
                        )
                    }
                }
                delay(GUI_UPDATE_INTERVAL)
            }
        }
    }

    /**
     * Precompute which folders are shared with which device, so the screen
     * does not have to run expensive config lookups during composition.
     */
    private fun buildSharedFoldersByDevice(folders: List<Folder>): Map<String, List<Folder>> {
        val sharedFoldersByDevice = HashMap<String, MutableList<Folder>>(folders.size)
        for (folder in folders) {
            folder.devices.forEach { sharedWithDevice ->
                val deviceId = sharedWithDevice.deviceID ?: return@forEach
                sharedFoldersByDevice.getOrPut(deviceId) { ArrayList() }.add(folder)
            }
        }
        return sharedFoldersByDevice
    }

    /**
     * Read the cached last-seen timestamps off the main thread so the screen
     * never performs disk I/O during composition.
     */
    private suspend fun pollLastSeen(devices: List<Device>): Map<String, String> {
        val context = appContext ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val lastSeen = HashMap<String, String>(devices.size)
            for (device in devices) {
                val deviceId = device.deviceID ?: continue
                lastSeen[deviceId] = preferences
                    .getString(PREF_CACHE_DEVICE_LASTSEEN_PREFIX + deviceId, "")
                    .orEmpty()
            }
            lastSeen
        }
    }

    /**
     * Snapshot the per-device status caches of [RestApi] into immutable UI state.
     */
    private fun pollDeviceStatuses(
        devices: List<Device>,
        restApi: RestApi?
    ): Map<String, DeviceItemStatusUi> {
        if (restApi == null) {
            return emptyMap()
        }
        val statuses = HashMap<String, DeviceItemStatusUi>(devices.size)
        for (device in devices) {
            val deviceId = device.deviceID ?: continue
            val connection = restApi.getRemoteDeviceStatus(deviceId)
            statuses[deviceId] = DeviceItemStatusUi(
                connected = connection?.connected == true,
                completion = restApi.getRemoteDeviceCompletion(deviceId),
                needBytes = restApi.getRemoteDeviceNeedBytes(deviceId),
                inBits = connection?.inBits ?: 0,
                outBits = connection?.outBits ?: 0,
            )
        }
        return statuses
    }

    companion object {
        private const val TAG = "DeviceFragmentViewModel"
    }
}
