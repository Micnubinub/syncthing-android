package com.micnubinub.syncthing.ui.viewModels

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.SharedWithDevice
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.RunConditionBus
import com.micnubinub.syncthing.service.RunConditionEvent
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.util.Random
import java.util.concurrent.atomic.AtomicReference

data class FolderDeviceShareState(
    val deviceId: String?,
    val displayName: String?,
    val shared: Boolean,
    val encryptionPassword: String
)

data class FolderActivityState(
    val isCreateMode: Boolean = false,
    val expertMode: Boolean = false,
    val isSaving: Boolean = false,
    val canWriteToPath: Boolean = false,
    val label: String = "",
    val folderId: String = "",
    val path: String = "",
    val folderType: String = Constants.FOLDER_TYPE_SEND_RECEIVE,
    val fsWatcherEnabled: Boolean = true,
    val paused: Boolean = false,
    val customSyncConditions: Boolean = false,
    val pullOrder: String = "random",
    val versioningType: String = "",
    val versioningParams: Map<String, String> = emptyMap(),
    val ignoreDelete: Boolean = false,
    val runScript: Boolean = false,
    val devices: List<FolderDeviceShareState> = emptyList(),
    val ignoreList: String = "",
    val folderNeedsToUpdate: Boolean = false,
    val ignoreListNeedsToUpdate: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showDiscardDialog: Boolean = false
)

sealed interface FolderActivityAction {
    data class LabelChanged(val value: String) : FolderActivityAction
    data class FolderIdChanged(val value: String) : FolderActivityAction
    data object PathClicked : FolderActivityAction
    data object AdvancedDirectoryClicked : FolderActivityAction
    data class FolderPathSelected(val path: String?, val uri: Uri?) : FolderActivityAction
    data object FolderTypeClicked : FolderActivityAction
    data class FolderTypeSelected(val type: String) : FolderActivityAction
    data object PullOrderClicked : FolderActivityAction
    data class PullOrderSelected(val order: String) : FolderActivityAction
    data object VersioningClicked : FolderActivityAction
    data class VersioningSelected(val type: String?, val params: Map<String, String>) :
        FolderActivityAction

    data class FileWatcherChanged(val checked: Boolean) : FolderActivityAction
    data class PausedChanged(val checked: Boolean) : FolderActivityAction
    data class CustomSyncConditionsChanged(val checked: Boolean) : FolderActivityAction
    data object CustomSyncConditionsClicked : FolderActivityAction
    data class IgnoreDeleteChanged(val checked: Boolean) : FolderActivityAction
    data class RunScriptChanged(val checked: Boolean) : FolderActivityAction
    data class DeviceShareChanged(val deviceId: String?, val shared: Boolean) :
        FolderActivityAction

    data class EncryptionPasswordChanged(val deviceId: String?, val password: String) :
        FolderActivityAction

    data class IgnoreListChanged(val value: String) : FolderActivityAction
    data object EmptyDeviceListClicked : FolderActivityAction
    data object DevicesChanged : FolderActivityAction
    data object Save : FolderActivityAction
    data object DeleteClicked : FolderActivityAction
    data object DeleteConfirmed : FolderActivityAction
    data object BackPressed : FolderActivityAction
    data object DiscardConfirmed : FolderActivityAction
    data object DismissDialogs : FolderActivityAction
}

sealed interface FolderActivityEvent {
    data class Finish(val resultCode: Int) : FolderActivityEvent
    data class ShowToast(@StringRes val message: Int, val length: Int) : FolderActivityEvent
    data object PickFolder : FolderActivityEvent
    data object PickFolderAdvanced : FolderActivityEvent
    data class ShowFolderTypeDialog(val currentType: String?) : FolderActivityEvent
    data class ShowPullOrderDialog(val currentOrder: String?) : FolderActivityEvent
    data class ShowVersioningDialog(val type: String, val params: Map<String, String>) :
        FolderActivityEvent

    data object AddDevice : FolderActivityEvent
    data class EditSyncConditions(val objectPrefixAndId: String?, val readableName: String?) :
        FolderActivityEvent
}

class FolderActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(FolderActivityState())
    val state: StateFlow<FolderActivityState> = _state.asStateFlow()

    private val _events = Channel<FolderActivityEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var context: Context? = null
    private var config: ConfigRouter? = null
    private var preferences: SharedPreferences? = null
    private var restApi: RestApi? = null
    private var folder: Folder? = null

    // Contains SAF readwrite access URI on API level >= Build.VERSION_CODES.LOLLIPOP (21)
    private var folderUri: Uri? = null
    private var devices: List<Device> = emptyList()
    private val initializedSessionKey = AtomicReference<String?>()

    fun initialize(
        context: Context,
        config: ConfigRouter,
        preferences: SharedPreferences,
        expertMode: Boolean,
        isCreateMode: Boolean,
        folderId: String?,
        folderLabel: String?,
        shareWithDeviceId: String?,
        receiveEncrypted: Boolean,
        sessionKey: String
    ) {
        if (initializedSessionKey.get() == sessionKey) {
            return
        }
        initializedSessionKey.set(sessionKey)
        folderUri = null
        _state.value = FolderActivityState(
            isCreateMode = isCreateMode,
            expertMode = expertMode
        )
        this.context = context.applicationContext
        this.config = config
        this.preferences = preferences

        // Config reads and the write-access check perform blocking file/shell I/O,
        // so run them off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            var folderNeedsToUpdate: Boolean
            if (isCreateMode) {
                Log.d(TAG, "Initializing create mode ...")
                folder = Folder().apply {
                    id = folderId ?: generateRandomFolderId()
                    label = folderLabel.orEmpty().trim()
                    paused = false
                    type = if (receiveEncrypted) {
                        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
                    } else {
                        // Default for checkWriteAccess().
                        Constants.FOLDER_TYPE_SEND_RECEIVE
                    }
                    minDiskFree = Folder.MinDiskFree()
                    versioning = Folder.Versioning().apply {
                        type = "trashcan"
                        params["cleanoutDays"] = 14.toString()
                        cleanupIntervalS = 0
                        fsPath = ""
                        fsType = "basic"
                    }
                }
                folderNeedsToUpdate = true
            } else {
                Log.d(TAG, "Initializing edit mode: folder.id=$folderId")
                // The REST API is not yet available at this point, read via ConfigXml.
                folder = config.getFolders(null).firstOrNull { it.id == folderId }
                folder?.let {
                    config.getFolderIgnoreList(null, it) { folderIgnoreList ->
                        if (initializedSessionKey.get() != sessionKey) {
                            return@getFolderIgnoreList
                        }
                        folderIgnoreList?.ignore?.let { ignore ->
                            _state.update {
                                it.copy(
                                    ignoreList = ignore.filterNotNull().joinToString("\n")
                                )
                            }
                        }
                    }
                }
                if (folder == null) {
                    Log.w(TAG, "Folder not found in config, maybe it was deleted?")
                    if (initializedSessionKey.get() == sessionKey) {
                        _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_CANCELED))
                    }
                    return@launch
                }
                folderNeedsToUpdate = false
            }

            // If set, automatically share the current folder with the given device.
            if (!shareWithDeviceId.isNullOrEmpty()) {
                folder?.addDevice(SharedWithDevice().apply { deviceID = shareWithDeviceId })
                folderNeedsToUpdate = true
            }

            devices = config.getDevices(null, false)

            val currentFolder = folder ?: return@launch
            val canWriteToPath = checkWriteAccess()
            if (initializedSessionKey.get() != sessionKey) {
                return@launch
            }
            _state.update {
                it.copy(
                    isCreateMode = isCreateMode,
                    expertMode = expertMode,
                    canWriteToPath = canWriteToPath,
                    label = currentFolder.label,
                    folderId = currentFolder.id.orEmpty(),
                    path = currentFolder.path.orEmpty(),
                    folderType = currentFolder.type,
                    fsWatcherEnabled = currentFolder.fsWatcherEnabled,
                    paused = currentFolder.paused,
                    customSyncConditions = !isCreateMode && preferences.getBoolean(
                        Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                            Constants.PREF_OBJECT_PREFIX_FOLDER + currentFolder.id
                        ),
                        false
                    ),
                    pullOrder = currentFolder.order,
                    versioningType = currentFolder.versioning?.type.orEmpty(),
                    versioningParams = versioningParamsOf(currentFolder),
                    ignoreDelete = currentFolder.ignoreDelete,
                    runScript = preferences.getBoolean(
                        Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(currentFolder.id),
                        false
                    ),
                    devices = buildDeviceShareStates(),
                    folderNeedsToUpdate = folderNeedsToUpdate
                )
            }
        }
    }

    fun onServiceConnected(restApi: RestApi?) {
        this.restApi = restApi
    }

    fun onAction(action: FolderActivityAction) {
        when (action) {
            is FolderActivityAction.LabelChanged -> {
                folder?.label = action.value.trim()
                markNeedsUpdate { it.copy(label = action.value) }
            }

            is FolderActivityAction.FolderIdChanged -> {
                folder?.id = action.value
                markNeedsUpdate { it.copy(folderId = action.value) }
            }

            FolderActivityAction.PathClicked ->
                _events.trySend(FolderActivityEvent.PickFolder)

            FolderActivityAction.AdvancedDirectoryClicked ->
                _events.trySend(FolderActivityEvent.PickFolderAdvanced)

            is FolderActivityAction.FolderPathSelected ->
                onFolderPathSelected(action.path, action.uri)

            FolderActivityAction.FolderTypeClicked -> onFolderTypeClicked()

            is FolderActivityAction.FolderTypeSelected -> {
                if (!_state.value.isCreateMode &&
                    action.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
                ) {
                    // Disallow switching existing folder's type to receiveEncrypted.
                    // SyncthingNative also does this. Posting a wrong config will result in http code 500.
                    toast(R.string.folder_type_switch_to_receive_encrypted_not_allowed)
                    return
                }
                folder?.type = action.type
                markNeedsUpdate { it.copy(folderType = action.type) }
            }

            FolderActivityAction.PullOrderClicked ->
                _events.trySend(FolderActivityEvent.ShowPullOrderDialog(folder?.order))

            is FolderActivityAction.PullOrderSelected -> {
                folder?.order = action.order
                markNeedsUpdate { it.copy(pullOrder = action.order) }
            }

            FolderActivityAction.VersioningClicked -> _events.trySend(
                FolderActivityEvent.ShowVersioningDialog(
                    type = folder?.versioning?.type.takeUnless { it.isNullOrEmpty() } ?: "none",
                    params = versioningParamsOf(folder)
                )
            )

            is FolderActivityAction.VersioningSelected ->
                onVersioningSelected(action.type, action.params)

            is FolderActivityAction.FileWatcherChanged -> {
                folder?.fsWatcherEnabled = action.checked
                markNeedsUpdate { it.copy(fsWatcherEnabled = action.checked) }
            }

            is FolderActivityAction.PausedChanged -> {
                folder?.paused = action.checked
                markNeedsUpdate { it.copy(paused = action.checked) }
            }

            is FolderActivityAction.CustomSyncConditionsChanged ->
                // This is needed to display the "discard changes dialog".
                markNeedsUpdate { it.copy(customSyncConditions = action.checked) }

            FolderActivityAction.CustomSyncConditionsClicked -> _events.trySend(
                FolderActivityEvent.EditSyncConditions(
                    Constants.PREF_OBJECT_PREFIX_FOLDER + folder?.id,
                    folder?.label
                )
            )

            is FolderActivityAction.IgnoreDeleteChanged -> {
                folder?.ignoreDelete = action.checked
                markNeedsUpdate { it.copy(ignoreDelete = action.checked) }
            }

            is FolderActivityAction.RunScriptChanged ->
                // Stored in pref.
                markNeedsUpdate { it.copy(runScript = action.checked) }

            is FolderActivityAction.DeviceShareChanged ->
                onDeviceShareChanged(action.deviceId, action.shared)

            is FolderActivityAction.EncryptionPasswordChanged ->
                onEncryptionPasswordChanged(action.deviceId, action.password)

            is FolderActivityAction.IgnoreListChanged -> _state.update {
                it.copy(
                    ignoreList = action.value,
                    ignoreListNeedsToUpdate = true,
                    folderNeedsToUpdate = true
                )
            }

            FolderActivityAction.EmptyDeviceListClicked ->
                _events.trySend(FolderActivityEvent.AddDevice)

            FolderActivityAction.DevicesChanged -> {
                // ConfigRouter.getDevices can read the config XML from disk when the REST
                // API is unavailable, so run it off the main thread.
                viewModelScope.launch(Dispatchers.IO) {
                    devices = config?.getDevices(restApi, false)?.filterNotNull().orEmpty()
                    _state.update { it.copy(devices = buildDeviceShareStates()) }
                }
            }

            FolderActivityAction.Save -> onSave()

            FolderActivityAction.DeleteClicked ->
                _state.update { it.copy(showDeleteDialog = true) }

            FolderActivityAction.DeleteConfirmed -> onDeleteConfirmed()

            FolderActivityAction.BackPressed -> onBackPressed()

            FolderActivityAction.DiscardConfirmed ->
                _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_CANCELED))

            FolderActivityAction.DismissDialogs -> _state.update {
                it.copy(showDeleteDialog = false, showDiscardDialog = false)
            }
        }
    }

    private fun onFolderPathSelected(path: String?, uri: Uri?) {
        if (path.isNullOrEmpty() || path == File.separator) {
            folder?.path = ""
            folderUri = null
            // checkWriteAccess() performs blocking shell I/O; keep it off the main thread.
            viewModelScope.launch(Dispatchers.IO) {
                val canWriteToPath = checkWriteAccess()
                _state.update { it.copy(path = "", canWriteToPath = canWriteToPath) }
            }
            // Suggest the user to select a folder on internal or external storage.
            toast(R.string.toast_invalid_folder_selected)
            return
        }
        folder?.path = path
        folderUri = uri
        Log.v(TAG, "onFolderPathSelected: Got directory path '$path'")
        // checkWriteAccess() performs blocking shell I/O; keep it off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val canWriteToPath = checkWriteAccess()
            markNeedsUpdate {
                it.copy(
                    path = path,
                    canWriteToPath = canWriteToPath,
                    folderType = folder?.type ?: it.folderType
                )
            }
        }
    }

    private fun onFolderTypeClicked() {
        if (folder?.path.isNullOrEmpty()) {
            toast(R.string.folder_path_required)
            return
        }
        if (!_state.value.canWriteToPath) {
            /**
             * Do not handle the click as the folder type row is disabled
             * and an explanation is already given on the UI why the only allowed folder type
             * is "sendonly".
             */
            toast(R.string.folder_path_readonly)
            return
        }
        // The user selected folder path is writeable, offer to choose from all available folder types.
        _events.trySend(FolderActivityEvent.ShowFolderTypeDialog(folder?.type))
    }

    private fun onVersioningSelected(type: String?, params: Map<String, String>) {
        val currentFolder = folder ?: return
        if (currentFolder.versioning == null) {
            currentFolder.versioning = Folder.Versioning()
        }

        if (type == null || type == "none") {
            currentFolder.versioning = Folder.Versioning().apply { this.type = "" }
        } else {
            currentFolder.versioning?.params?.putAll(params)
            currentFolder.versioning?.type = type
        }
        markNeedsUpdate {
            it.copy(
                versioningType = currentFolder.versioning?.type.orEmpty(),
                versioningParams = versioningParamsOf(currentFolder)
            )
        }
    }

    private fun onDeviceShareChanged(deviceId: String?, shared: Boolean) {
        val device = devices.firstOrNull { it.deviceID == deviceId } ?: return
        if (shared) {
            folder?.addDevice(device)
            folder?.getDevice(deviceId)?.encryptionPassword = _state.value.devices
                .firstOrNull { it.deviceId == deviceId }
                ?.encryptionPassword
                .orEmpty()
        } else {
            folder?.removeDevice(deviceId)
        }
        markNeedsUpdate { s ->
            s.copy(devices = s.devices.map {
                if (it.deviceId == deviceId) it.copy(shared = shared) else it
            })
        }
    }

    private fun onEncryptionPasswordChanged(deviceId: String?, password: String) {
        val sharedWithDevice = folder?.getDevice(deviceId)
        val changed = sharedWithDevice != null && sharedWithDevice.encryptionPassword != password
        sharedWithDevice?.encryptionPassword = password
        _state.update { s ->
            val updated = s.copy(devices = s.devices.map {
                if (it.deviceId == deviceId) it.copy(encryptionPassword = password) else it
            })
            if (changed) updated.copy(folderNeedsToUpdate = true) else updated
        }
    }

    private fun markNeedsUpdate(update: (FolderActivityState) -> FolderActivityState) {
        _state.update { update(it).copy(folderNeedsToUpdate = true) }
    }

    private fun onBackPressed() {
        val currentState = _state.value
        if (currentState.isSaving) {
            return
        }
        if (currentState.folderNeedsToUpdate) {
            _state.update { it.copy(showDiscardDialog = true) }
        } else {
            _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_CANCELED))
        }
    }

    private fun onDeleteConfirmed() {
        val folderId = folder?.id

        if (folderId == Constants.syncthingCameraFolderId) {
            // Remove consent to "Syncthing Camera" feature.
            preferences?.edit {
                putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
            }
        }
        _state.update { it.copy(showDeleteDialog = false, folderNeedsToUpdate = false) }

        // removeFolder performs blocking config I/O; keep it off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            folderId?.let { config?.removeFolder(restApi, it) }
            _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_CANCELED))
        }
    }

    private fun onSave() {
        val currentFolder = folder
        val currentState = _state.value
        if (currentFolder == null) {
            Log.e(TAG, "onSave: folder == null")
            return
        }
        if (currentState.isSaving) {
            Log.v(TAG, "onSave: save already in progress")
            return
        }

        // Validate fields.
        if (currentFolder.id.isNullOrEmpty()) {
            toast(R.string.folder_id_required)
            return
        }
        if (currentFolder.label.isEmpty()) {
            toast(R.string.folder_label_required)
            return
        }
        if (currentFolder.path.isNullOrEmpty()) {
            toast(R.string.folder_path_required)
            return
        }

        _state.update { it.copy(isSaving = true) }

        preferences?.edit {
            putBoolean(
                Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(currentFolder.id),
                currentState.runScript
            )
        }

        if (currentState.isCreateMode) {
            Log.v(TAG, "onSave: Adding folder with ID = '${currentFolder.id}'")
            viewModelScope.launch(Dispatchers.IO) {
                preCreateFolderStruct(folderUri, currentFolder.path)
                config?.addFolder(restApi, currentFolder)
                RunConditionBus.tryEmit(RunConditionEvent.SyncTriggerFired(true))
                _state.update { it.copy(isSaving = false) }
                _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_OK))
            }
            return
        }

        // Edit mode.
        if (!currentState.folderNeedsToUpdate) {
            // We've got nothing to save.
            _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_CANCELED))
            return
        }

        // Save folder specific preferences.
        Log.v(TAG, "onSave: Updating folder with ID = '${currentFolder.id}'")
        preferences?.edit {
            putBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                    Constants.PREF_OBJECT_PREFIX_FOLDER + currentFolder.id
                ),
                currentState.customSyncConditions
            )
        }

        // Ignore list and folder updates perform blocking config I/O; keep them
        // off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            if (currentState.ignoreListNeedsToUpdate) {
                // Update ignore list.
                val ignore: List<String> = currentState.ignoreList.split("\n")
                    .dropLastWhile { it.isEmpty() }
                    .map { it }
                config?.postFolderIgnoreList(restApi, currentFolder, ignore)
            }

            // Update folder using RestApi or ConfigXml.
            config?.updateFolder(restApi, currentFolder)
            _state.update { it.copy(isSaving = false) }
            _events.trySend(FolderActivityEvent.Finish(Activity.RESULT_OK))
        }
    }

    private fun preCreateFolderStruct(uriFolderRoot: Uri?, absolutePath: String?) {
        /**
         * Normally, syncthing takes care of creating the ".stfolder" marker.
         * This fails on Android 5+ if the syncthing binary only has
         * readonly access on the path and the user tries to configure a
         * sendOnly folder. To fix this, we'll precreate the marker using java code.
         */
        val folderMarkerDirName = Folder().markerName
        val strFolderMarkerPath = absolutePath + File.separator + folderMarkerDirName

        /**
         * Name of the dummy file created within the marker directory.
         * Creating the file is a workaround for issue where manufacturer
         * specific cleaning routines silently wipe out empty directories like
         * the marker directory.
         */
        val doNotDeleteFileName = "DO_NOT_DELETE"
        val strDoNotDeleteFile = strFolderMarkerPath + File.separator + doNotDeleteFileName

        /**
         * Precreate .stversions directory so we can put ".nomedia" in place to keep the gallery clean.
         */
        val strStVersionsPath = absolutePath + File.separator + Constants.FOLDER_NAME_STVERSIONS
        val strStVersionsNoMediaFile = strStVersionsPath + File.separator + ".nomedia"

        // Fall back to classic API if uriFolderRoot is missing. E.g. in case FolderPickerActivity was used which only returns an absolute path.
        if (uriFolderRoot == null) {
            Log.w(TAG, "preCreateFolderStruct: uriFolderRoot == null. Using absolute path.")
            try {
                // ".stfolder"
                File(strFolderMarkerPath).mkdirs()
                if (File(strDoNotDeleteFile).createNewFile()) {
                    FileWriter(strDoNotDeleteFile).use { writer ->
                        writer.write(doNotDeleteFileName)
                    }
                }

                // ".stversions"
                File(strStVersionsPath).mkdirs()
                File(strStVersionsNoMediaFile).createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e)
            }
            return
        }

        val context = context ?: return

        // Derive DocumentFile handle from SAF tree Uri where we have write access.
        val dfFolder = DocumentFile.fromTreeUri(context, uriFolderRoot)

        // Create ".stfolder" directory.
        FileUtils.safCreateDirectory(dfFolder, folderMarkerDirName)?.let { parentFolder ->
            // Create ".stfolder/DO_NOT_DELETE.txt" file.
            FileUtils.safCreateFile(
                context,
                parentFolder,
                "$doNotDeleteFileName.txt",
                doNotDeleteFileName
            )
        }

        // Create ".stversions" directory.
        FileUtils.safCreateDirectory(dfFolder, Constants.FOLDER_NAME_STVERSIONS)
            ?.let { parentFolder ->
                // Create ".stversions/.nomedia" file.
                FileUtils.safCreateFile(context, parentFolder, ".nomedia", "")
            }
    }

    /**
     * Prerequisite: folder.path must be non-empty for a write test to happen.
     *
     * Check if the permissions we have on that folder is readonly or readwrite.
     * Access level readonly: folder can only be configured "sendonly".
     * Access level readwrite: folder can be configured "sendonly" or "sendreceive".
     */
    private fun checkWriteAccess(): Boolean {
        val currentFolder = folder ?: return false
        if (currentFolder.path.isNullOrEmpty()) {
            return false
        }
        val canWriteToPath = Util.nativeBinaryCanWriteToPath(currentFolder.path)
        if (!canWriteToPath) {
            // Force "sendonly" folder.
            currentFolder.type = Constants.FOLDER_TYPE_SEND_ONLY
        }
        return canWriteToPath
    }

    private fun buildDeviceShareStates(): List<FolderDeviceShareState> {
        val currentFolder = folder ?: return emptyList()
        return devices.map { device ->
            val sharedWithDevice = currentFolder.getDevice(device.deviceID)
            FolderDeviceShareState(
                deviceId = device.deviceID,
                displayName = device.displayName,
                shared = sharedWithDevice != null,
                encryptionPassword = sharedWithDevice?.encryptionPassword.orEmpty()
            )
        }
    }

    private fun versioningParamsOf(folder: Folder?): Map<String, String> {
        return folder?.versioning?.params?.mapValues { it.value.orEmpty() }.orEmpty()
    }

    private fun generateRandomFolderId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        val sb = StringBuilder()
        val random = Random()
        for (i in 0..9) {
            if (i == 5) {
                sb.append("-")
            }
            val c = chars[random.nextInt(chars.size)]
            sb.append(c)
        }
        return sb.toString()
    }

    private fun toast(@StringRes message: Int) {
        _events.trySend(FolderActivityEvent.ShowToast(message, Toast.LENGTH_LONG))
    }

    companion object {
        private const val TAG = "FolderActivityViewModel"
    }
}
