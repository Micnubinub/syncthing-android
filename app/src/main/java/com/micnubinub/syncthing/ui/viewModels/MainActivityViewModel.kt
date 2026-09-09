package com.micnubinub.syncthing.ui.viewModels

import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.ui.screens.Destination
import com.micnubinub.syncthing.util.ConfigRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
data class MainActivityState(
    val isLoading: Boolean = false,
    val drawerState: DrawerValue = DrawerValue.Closed,
    val title: String = "Alt-Syncthing",
    val isSyncing: Boolean = false,
)

data class DeviceIdDialogData(
    val deviceName: String,
    val deviceId: String,
)

data class UsageReportingDialogData(
    val report: String,
)

sealed interface MainActivityAction {
    data object LoadData : MainActivityAction
}

class MainActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(MainActivityState())
    private var syncStatusJob: Job? = null

    val state: StateFlow<MainActivityState> = _state.asStateFlow()

    private val _service = MutableStateFlow<SyncthingService?>(null)
    val service: StateFlow<SyncthingService?> = _service.asStateFlow()

    private val _serviceState =
        MutableStateFlow<SyncthingService.State>(SyncthingService.State.INIT)
    val serviceState: StateFlow<SyncthingService.State> = _serviceState.asStateFlow()

    private val _deviceIdDialog = MutableStateFlow<DeviceIdDialogData?>(null)
    val deviceIdDialog: StateFlow<DeviceIdDialogData?> = _deviceIdDialog.asStateFlow()

    private val _usageReportingDialog = MutableStateFlow<UsageReportingDialogData?>(null)
    val usageReportingDialog: StateFlow<UsageReportingDialogData?> =
        _usageReportingDialog.asStateFlow()

    val api: StateFlow<RestApi?> = _service
        .map { it?.api }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setService(service: SyncthingService?) {
        _service.value = service
        if (service == null) {
            syncStatusJob?.cancel()
            syncStatusJob = null
            return
        }
        observeSyncStatus()
    }

    /**
     * Derives the syncing indicator from the folder-status cache, recomputed whenever
     * the RestApi pushes a folder change over its event stream (StateChanged,
     * FolderSummary, pause/resume) instead of polling the API on an interval.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSyncStatus() {
        syncStatusJob?.cancel()
        syncStatusJob = api.flatMapLatest { restApi ->
            if (restApi == null) {
                flowOf(false)
            } else {
                restApi.folderStateChanged
                    .onStart { emit(Unit) }
                    .map { computeIsSyncing(restApi) }
            }
        }.onEach { isSyncing ->
            _state.update { it.copy(isSyncing = isSyncing) }
        }.launchIn(viewModelScope)
    }

    private fun computeIsSyncing(restApi: RestApi): Boolean =
        restApi.isConfigLoaded && restApi.folders.any { folder ->
            val folderState = restApi.getFolderStatus(folder.id).key.state
            folderState.contains("sync") || folderState.contains("scan")
        }

    fun setServiceState(serviceState: SyncthingService.State) {
        _serviceState.value = serviceState
    }

    fun showDeviceIdDialog(deviceName: String, deviceId: String) {
        _deviceIdDialog.value = DeviceIdDialogData(deviceName, deviceId)
    }

    fun dismissDeviceIdDialog() {
        _deviceIdDialog.value = null
    }

    fun showUsageReportingDialog(report: String) {
        _usageReportingDialog.value = UsageReportingDialogData(report)
    }

    fun dismissUsageReportingDialog() {
        _usageReportingDialog.value = null
    }

    fun onAction(action: MainActivityAction) {
        when (action) {
            MainActivityAction.LoadData -> loadData()
        }
    }

    fun update(configRouter: ConfigRouter, destination: Destination) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (destination) {
                    Destination.FOLDERS -> configRouter.getFolders(api.value)
                    Destination.DEVICES -> configRouter.getDevices(api.value)
                    Destination.STATUS -> {}
                }
            }
            val restApi = api.value
            if (restApi?.isConfigLoaded == true) {
                withContext(Dispatchers.IO) {
                    restApi.rescanAll()
                }
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
        }
    }

    fun setDrawerState(drawerState: DrawerValue) {
        viewModelScope.launch {
            _state.update { it.copy(drawerState = drawerState) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncStatusJob?.cancel()
    }
}
