package com.micnubinub.syncthing.ui.viewModels

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.model.CachedFolderStatus
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.FolderStatus
import com.micnubinub.syncthing.service.Constants.GUI_UPDATE_INTERVAL
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml.OpenConfigException
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
 * Immutable snapshot of the per-folder status data the screen needs.
 * Extracted from [RestApi] caches so composition only reads primitives.
 */
@Stable
data class FolderItemStatusUi(
    val state: String = "",
    val completion: Double = 100.0,
    val errors: Long = 0,
    val needTotalItems: Long = 0,
    val receiveOnlyTotalItems: Long = 0,
    val globalFiles: Long = 0,
    val inSyncFiles: Long = 0,
    val error: String = "",
    val conflicts: List<String> = emptyList(),
    val lastItemFinishedAction: String = "",
    val lastItemFinishedItem: String = "",
    val lastItemFinishedTime: String = "",
)

@Stable
data class FolderFragmentState(
    val isLoading: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val folderStatuses: Map<String, FolderItemStatusUi> = emptyMap()
)

sealed interface FolderFragmentAction {
    data object LoadData : FolderFragmentAction
    data object StopPolling : FolderFragmentAction
}

class FolderFragmentViewModel : ViewModel() {
    private val _state = MutableStateFlow(FolderFragmentState())
    val state: StateFlow<FolderFragmentState> = _state.asStateFlow()
    private var pollingJob: Job? = null

    private val _service = MutableStateFlow<SyncthingService?>(null)
    val service: StateFlow<SyncthingService?> = _service.asStateFlow()

    val api: RestApi?
        get() = _service.value?.api

    private val _configRouter = MutableStateFlow<ConfigRouter?>(null)
    val configRouter: StateFlow<ConfigRouter?> = _configRouter.asStateFlow()

    fun setService(service: SyncthingService?) {
        _service.value = service
    }

    fun setConfigRouter(configRouter: ConfigRouter?) {
        if (configRouter != null) {
            _configRouter.value = configRouter
        }
    }

    fun onAction(action: FolderFragmentAction) {
        when (action) {
            FolderFragmentAction.LoadData -> loadData()
            FolderFragmentAction.StopPolling -> stopPolling()
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
        } catch (_: Exception) {
            // Ignore; cancelling the previous polling job is best-effort.
        }
        pollingJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.folders.isEmpty()) }
            while (true) {
                configRouter.value?.let { router ->
                    val folders = try {
                        withContext(Dispatchers.IO) {
                            router.getFolders(api)
                        }
                    } catch (e: OpenConfigException) {
                        Log.e(
                            TAG,
                            "Failed to parse existing config. You will need support from here ..."
                        )
                        _state.value.folders
                    }
                    val folderStatuses = pollFolderStatuses(folders, api)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            folders = folders,
                            folderStatuses = folderStatuses
                        )
                    }
                }
                delay(GUI_UPDATE_INTERVAL)
            }
        }
    }

    /**
     * Snapshot the per-folder status caches of [RestApi] into immutable UI state.
     * [StateFlow] equality deduplication ensures the UI only emits when the
     * snapshot actually changed compared to the previous poll.
     */
    private fun pollFolderStatuses(
        folders: List<Folder>,
        restApi: RestApi?
    ): Map<String, FolderItemStatusUi> {
        if (restApi == null) {
            return emptyMap()
        }
        val statuses = HashMap<String, FolderItemStatusUi>(folders.size)
        for (folder in folders) {
            val folderId = folder.id ?: continue
            statuses[folderId] = restApi.getFolderStatus(folderId).toItemStatusUi()
        }
        return statuses
    }

    private fun MutableMap.MutableEntry<FolderStatus, CachedFolderStatus>.toItemStatusUi(): FolderItemStatusUi {
        val folderStatus = key
        val cachedFolderStatus = value
        return FolderItemStatusUi(
            state = folderStatus.state,
            completion = cachedFolderStatus.completion,
            errors = folderStatus.errors,
            needTotalItems = folderStatus.needTotalItems,
            receiveOnlyTotalItems = folderStatus.receiveOnlyTotalItems,
            globalFiles = folderStatus.globalFiles,
            inSyncFiles = folderStatus.inSyncFiles,
            error = folderStatus.error,
            conflicts = cachedFolderStatus.discoveredConflictFiles.filterNotNull(),
            lastItemFinishedAction = cachedFolderStatus.lastItemFinishedAction,
            lastItemFinishedItem = cachedFolderStatus.lastItemFinishedItem,
            lastItemFinishedTime = cachedFolderStatus.lastItemFinishedTime,
        )
    }

    fun rescanAll() {
        val restApi = api
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(TAG, "rescanAll skipped because Syncthing is not running.")
            return
        }
        restApi.rescanAll()
    }

    fun overrideChanges(folderId: String?) {
        val restApi = api
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(TAG, "overrideChanges skipped because Syncthing is not running.")
            return
        }
        restApi.overrideChanges(folderId)
    }

    fun revertLocalChanges(folderId: String?) {
        val restApi = api
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(TAG, "revertLocalChanges skipped because Syncthing is not running.")
            return
        }
        restApi.revertLocalChanges(folderId)
    }

    companion object {
        private const val TAG = "FolderFragmentViewModel"
    }
}
