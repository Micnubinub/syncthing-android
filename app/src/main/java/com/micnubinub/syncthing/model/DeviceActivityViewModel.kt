package com.micnubinub.syncthing.ui.viewModels

import android.app.Activity
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.TestData
import com.micnubinub.syncthing.util.Compression
import com.micnubinub.syncthing.util.ConfigRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderShareState(
    val folder: Folder,
    val shared: Boolean,
    val encryptionPassword: String
)

data class DiscoveredDeviceState(
    val deviceId: String,
    val caption: String
)

data class DeviceActivityState(
    val isCreateMode: Boolean = true,
    val expertMode: Boolean = false,
    val showQrScannerButton: Boolean = true,
    val deviceId: String = "",
    val name: String = "",
    val addresses: String = "",
    val compression: Compression = Compression.METADATA,
    val introducer: Boolean = false,
    val autoAcceptFolders: Boolean = false,
    val paused: Boolean = false,
    val untrusted: Boolean = false,
    val customSyncConditions: Boolean = false,
    val folders: List<FolderShareState> = emptyList(),
    val discoveredDevices: List<DiscoveredDeviceState>? = null,
    val localDiscoveryEnabled: Boolean = true,
    val currentAddress: String? = null,
    val clientVersion: String? = null,
    val deviceNeedsToUpdate: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val showCompressionDialog: Boolean = false,
    val showQrDialog: Boolean = false
)

sealed interface DeviceActivityAction {
    data class DeviceIdChanged(val value: String) : DeviceActivityAction
    data class NameChanged(val value: String) : DeviceActivityAction
    data class AddressesChanged(val value: String) : DeviceActivityAction
    data class CompressionSelected(val compression: Compression) : DeviceActivityAction
    data class IntroducerChanged(val checked: Boolean) : DeviceActivityAction
    data class AutoAcceptFoldersChanged(val checked: Boolean) : DeviceActivityAction
    data class PausedChanged(val checked: Boolean) : DeviceActivityAction
    data class UntrustedChanged(val checked: Boolean) : DeviceActivityAction
    data class CustomSyncConditionsChanged(val checked: Boolean) : DeviceActivityAction
    data class FolderShareChanged(val folderId: String?, val shared: Boolean) : DeviceActivityAction
    data class EncryptionPasswordChanged(val folderId: String?, val password: String) :
        DeviceActivityAction

    data object Save : DeviceActivityAction
    data object DeleteClicked : DeviceActivityAction
    data object DeleteConfirmed : DeviceActivityAction
    data object BackPressed : DeviceActivityAction
    data object DiscardConfirmed : DeviceActivityAction
    data object DismissDialogs : DeviceActivityAction
    data object CompressionClicked : DeviceActivityAction
    data object QrScannerClicked : DeviceActivityAction
    data class QrCodeScanned(val deviceId: String) : DeviceActivityAction
    data object ShowQrClicked : DeviceActivityAction
    data object RefreshDiscoveredDevices : DeviceActivityAction
    data class DiscoveredDeviceSelected(val deviceId: String) : DeviceActivityAction
    data object EmptyFolderListClicked : DeviceActivityAction
    data object CustomSyncConditionsClicked : DeviceActivityAction
    data object FoldersChanged : DeviceActivityAction
}

sealed interface DeviceActivityEvent {
    data class Finish(val resultCode: Int) : DeviceActivityEvent
    data class ShowToast(@StringRes val message: Int, val length: Int) : DeviceActivityEvent
    data object ScanQrCode : DeviceActivityEvent
    data object AddFolder : DeviceActivityEvent
    data class EditSyncConditions(val objectPrefixAndId: String?, val readableName: String?) :
        DeviceActivityEvent
}

class DeviceActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(DeviceActivityState())
    val state: StateFlow<DeviceActivityState> = _state.asStateFlow()

    private val _events = Channel<DeviceActivityEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var config: ConfigRouter? = null
    private var preferences: SharedPreferences? = null
    private var restApi: RestApi? = null
    private var compressionValues: List<String> = emptyList()
    private var device: Device? = null
    private var lastInitializedRoute: String? = null

    fun initialize(
        config: ConfigRouter,
        preferences: SharedPreferences,
        compressionValues: List<String>,
        expertMode: Boolean,
        showQrScannerButton: Boolean,
        isCreateMode: Boolean,
        deviceId: String?,
        deviceName: String?
    ) {
        val routeKey = "$isCreateMode|$deviceId|$deviceName"
        if (lastInitializedRoute == routeKey) {
            return
        }
        lastInitializedRoute = routeKey
        this.config = config
        this.preferences = preferences
        this.compressionValues = compressionValues

        var deviceNeedsToUpdate: Boolean
        if (isCreateMode) {
            Log.d(TAG, "Initializing create mode ...")
            device = Device().apply {
                this.name = deviceName.orEmpty()
                this.deviceID = deviceId.orEmpty()

                this.addresses = mutableListOf(DYNAMIC_ADDRESS)
                this.compression =
                    compressionValues.getOrElse(Compression.METADATA.index) { "metadata" }
            }
            deviceNeedsToUpdate = true
            val currentDevice = device ?: return
            _state.update {
                it.copy(
                    isCreateMode = isCreateMode,
                    expertMode = expertMode,
                    showQrScannerButton = showQrScannerButton,
                    deviceId = currentDevice.deviceID.orEmpty(),
                    name = currentDevice.name,
                    addresses = displayableAddresses(currentDevice),
                    compression = compressionFromValue(currentDevice.compression),
                    introducer = currentDevice.introducer,
                    autoAcceptFolders = currentDevice.autoAcceptFolders,
                    paused = currentDevice.paused,
                    untrusted = currentDevice.untrusted,
                    customSyncConditions = false,
                    folders = emptyList(),
                    deviceNeedsToUpdate = deviceNeedsToUpdate
                )
            }
        } else {
            Log.d(TAG, "Initializing edit mode: deviceID=$deviceId")
            viewModelScope.launch {
                // The REST API is not yet available at this point, read via ConfigXml.
                val loadedDevice = withContext(Dispatchers.IO) {
                    config.getDevices(null, false)
                        .firstOrNull { it.deviceID == deviceId }
                }
                device = loadedDevice
                if (loadedDevice == null) {
                    Log.w(TAG, "Device not found in config, maybe it was deleted?")
                    _events.trySend(DeviceActivityEvent.Finish(Activity.RESULT_CANCELED))
                    return@launch
                }
                _state.update {
                    it.copy(
                        isCreateMode = isCreateMode,
                        expertMode = expertMode,
                        showQrScannerButton = showQrScannerButton,
                        deviceId = loadedDevice.deviceID.orEmpty(),
                        name = loadedDevice.name,
                        addresses = displayableAddresses(loadedDevice),
                        compression = compressionFromValue(loadedDevice.compression),
                        introducer = loadedDevice.introducer,
                        autoAcceptFolders = loadedDevice.autoAcceptFolders,
                        paused = loadedDevice.paused,
                        untrusted = loadedDevice.untrusted,
                        customSyncConditions = preferences.getBoolean(
                            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                                Constants.PREF_OBJECT_PREFIX_DEVICE + loadedDevice.deviceID
                            ),
                            false
                        ),
                        folders = loadFolders(),
                        deviceNeedsToUpdate = false
                    )
                }
            }
        }
    }

    fun onServiceConnected(restApi: RestApi?) {
        this.restApi = restApi ?: return

        // Query device connection info from cache.
        val deviceId = device?.deviceID
        if (!deviceId.isNullOrEmpty()) {
            restApi.getRemoteDeviceStatus(deviceId)?.let { connection ->
                if (connection.at.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            currentAddress = connection.address,
                            clientVersion = connection.clientVersion
                        )
                    }
                }
            }
        }

        if (_state.value.isCreateMode) {
            queryDiscoveredDevices()
        }
    }

    fun onAction(action: DeviceActivityAction) {
        when (action) {
            is DeviceActivityAction.DeviceIdChanged -> {
                device?.deviceID = action.value
                markNeedsUpdate { it.copy(deviceId = action.value) }
            }

            is DeviceActivityAction.NameChanged -> {
                device?.name = action.value
                markNeedsUpdate { it.copy(name = action.value) }
            }

            is DeviceActivityAction.AddressesChanged -> {
                device?.addresses = persistableAddresses(action.value)
                markNeedsUpdate { it.copy(addresses = action.value) }
            }

            is DeviceActivityAction.CompressionSelected -> {
                // Don't flag an update unless the value actually changed.
                if (action.compression != _state.value.compression) {
                    device?.compression =
                        compressionValues.getOrElse(action.compression.index) { "metadata" }
                    markNeedsUpdate { it.copy(compression = action.compression) }
                }
                _state.update { it.copy(showCompressionDialog = false) }
            }

            is DeviceActivityAction.IntroducerChanged -> {
                device?.introducer = action.checked
                markNeedsUpdate { it.copy(introducer = action.checked) }
            }

            is DeviceActivityAction.AutoAcceptFoldersChanged -> {
                device?.autoAcceptFolders = action.checked
                markNeedsUpdate { it.copy(autoAcceptFolders = action.checked) }
            }

            is DeviceActivityAction.PausedChanged -> {
                device?.paused = action.checked
                markNeedsUpdate { it.copy(paused = action.checked) }
            }

            is DeviceActivityAction.UntrustedChanged -> {
                device?.untrusted = action.checked
                markNeedsUpdate { it.copy(untrusted = action.checked) }
            }

            is DeviceActivityAction.CustomSyncConditionsChanged ->
                markNeedsUpdate { it.copy(customSyncConditions = action.checked) }

            is DeviceActivityAction.FolderShareChanged -> {
                markNeedsUpdate { s ->
                    s.copy(folders = s.folders.map {
                        if (it.folder.id == action.folderId) it.copy(shared = action.shared) else it
                    })
                }
            }

            is DeviceActivityAction.EncryptionPasswordChanged -> {
                markNeedsUpdate { s ->
                    s.copy(folders = s.folders.map {
                        if (it.folder.id == action.folderId) {
                            it.copy(encryptionPassword = action.password)
                        } else it
                    })
                }
            }

            DeviceActivityAction.Save -> onSave()
            DeviceActivityAction.DeleteClicked ->
                _state.update { it.copy(showDeleteDialog = true) }

            DeviceActivityAction.DeleteConfirmed -> onDeleteConfirmed()
            DeviceActivityAction.BackPressed -> onBackPressed()
            DeviceActivityAction.DiscardConfirmed ->
                _events.trySend(DeviceActivityEvent.Finish(Activity.RESULT_CANCELED))

            DeviceActivityAction.DismissDialogs -> _state.update {
                it.copy(
                    showDeleteDialog = false,
                    showDiscardDialog = false,
                    showCompressionDialog = false,
                    showQrDialog = false
                )
            }

            DeviceActivityAction.CompressionClicked ->
                _state.update { it.copy(showCompressionDialog = true) }

            DeviceActivityAction.QrScannerClicked ->
                _events.trySend(DeviceActivityEvent.ScanQrCode)

            is DeviceActivityAction.QrCodeScanned -> {
                val scanned =
                    if (Constants.ENABLE_TEST_DATA) TestData.DEVICE_A_ID else action.deviceId
                device?.deviceID = scanned
                markNeedsUpdate { it.copy(deviceId = scanned) }
            }

            DeviceActivityAction.ShowQrClicked -> {
                if (_state.value.deviceId.isBlank()) {
                    _events.trySend(
                        DeviceActivityEvent.ShowToast(
                            R.string.could_not_access_deviceid,
                            android.widget.Toast.LENGTH_SHORT
                        )
                    )
                } else {
                    _state.update { it.copy(showQrDialog = true) }
                }
            }

            DeviceActivityAction.RefreshDiscoveredDevices -> queryDiscoveredDevices()
            is DeviceActivityAction.DiscoveredDeviceSelected -> {
                device?.deviceID = action.deviceId
                markNeedsUpdate { it.copy(deviceId = action.deviceId) }
            }

            DeviceActivityAction.EmptyFolderListClicked ->
                _events.trySend(DeviceActivityEvent.AddFolder)

            DeviceActivityAction.CustomSyncConditionsClicked -> _events.trySend(
                DeviceActivityEvent.EditSyncConditions(
                    Constants.PREF_OBJECT_PREFIX_DEVICE + device?.deviceID,
                    device?.name
                )
            )

            DeviceActivityAction.FoldersChanged ->
                viewModelScope.launch {
                    _state.update { it.copy(folders = loadFolders()) }
                }
        }
    }

    private fun markNeedsUpdate(update: (DeviceActivityState) -> DeviceActivityState) {
        _state.update { update(it).copy(deviceNeedsToUpdate = true) }
    }

    private fun onBackPressed() {
        if (_state.value.deviceNeedsToUpdate) {
            _state.update { it.copy(showDiscardDialog = true) }
        } else {
            _events.trySend(DeviceActivityEvent.Finish(Activity.RESULT_CANCELED))
        }
    }

    private fun onDeleteConfirmed() {
        val currentDevice = device
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                currentDevice?.deviceID?.let { deviceId ->
                    config?.removeDevice(restApi, deviceId)
                }
            }
            _state.update { it.copy(showDeleteDialog = false, deviceNeedsToUpdate = false) }
            _events.trySend(DeviceActivityEvent.Finish(Activity.RESULT_OK))
        }
    }

    private fun onSave() {
        val currentDevice = device
        val currentState = _state.value
        if (currentDevice == null) {
            Log.e(TAG, "onSave: device == null")
            return
        }

        // Validate fields.
        if (currentDevice.deviceID.isNullOrEmpty()) {
            toast(R.string.device_id_required)
            return
        }
        if (!currentDevice.checkDeviceID()) {
            toast(R.string.device_id_invalid)
            return
        }
        if (!currentDevice.checkDeviceAddresses()) {
            toast(R.string.device_addresses_invalid)
            return
        }

        // Folder updates and device updates perform blocking config I/O; keep
        // them off the main thread and only touch the UI afterwards.
        viewModelScope.launch {
            val resultCode = withContext(Dispatchers.IO) {
                // Update folder sharing and encryption passwords.
                for (folderState in currentState.folders) {
                    val folder = folderState.folder
                    if (folderState.shared) {
                        folder.addDevice(currentDevice)
                        folder.getDevice(currentDevice.deviceID)?.encryptionPassword =
                            folderState.encryptionPassword
                    } else {
                        folder.removeDevice(currentDevice.deviceID)
                    }
                    config?.updateFolder(restApi, folder)
                }

                when {
                    currentState.isCreateMode -> {
                        Log.v(TAG, "onSave: Adding device with ID = '${currentDevice.deviceID}'")
                        config?.updateDevice(restApi, currentDevice)
                        Activity.RESULT_OK
                    }

                    // Edit mode, nothing to save.
                    !currentState.deviceNeedsToUpdate -> Activity.RESULT_CANCELED

                    else -> {
                        // Save device specific preferences.
                        Log.v(TAG, "onSave: Updating device with ID = '${currentDevice.deviceID}'")
                        preferences?.edit {
                            putBoolean(
                                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                                    Constants.PREF_OBJECT_PREFIX_DEVICE + currentDevice.deviceID
                                ),
                                currentState.customSyncConditions
                            )
                        }

                        // Update device using RestApi or ConfigXml.
                        config?.updateDevice(restApi, currentDevice)
                        Activity.RESULT_OK
                    }
                }
            }
            _events.trySend(DeviceActivityEvent.Finish(resultCode))
        }
    }

    /**
     * Perform asynchronous query via REST to retrieve locally discovered devices.
     */
    private fun queryDiscoveredDevices() {
        val api = restApi
        if (api == null || !api.isConfigLoaded) {
            return
        }
        api.getDiscoveredDevices { discoveredDevices ->
            if (discoveredDevices == null) {
                Log.e(TAG, "queryDiscoveredDevices: discoveredDevices == null")
                return@getDiscoveredDevices
            }
            val items = discoveredDevices.keys.filterNotNull().map { deviceId ->
                val addresses = discoveredDevices[deviceId]
                    ?.addresses
                    ?.filterNotNull()
                    ?.joinToString(", ")
                    .orEmpty()
                DiscoveredDeviceState(
                    deviceId,
                    if (addresses.isEmpty()) deviceId else "$deviceId ($addresses)"
                )
            }
            _state.update {
                it.copy(
                    discoveredDevices = items,
                    localDiscoveryEnabled =
                        config?.getOptions(api)?.localAnnounceEnabled == true
                )
            }
        }
    }

    private suspend fun loadFolders(): List<FolderShareState> = withContext(Dispatchers.IO) {
        val deviceId = device?.deviceID
        config?.getFolders(restApi)?.map { folder ->
            val sharedWithDevice = folder.getDevice(deviceId)
            FolderShareState(
                folder = folder,
                shared = sharedWithDevice != null,
                encryptionPassword = sharedWithDevice?.encryptionPassword.orEmpty()
            )
        }.orEmpty()
    }

    private fun compressionFromValue(value: String?): Compression {
        val index = compressionValues.indexOf(value)
        return if (index >= 0) Compression.fromIndex(index) else Compression.METADATA
    }

    private fun toast(@StringRes message: Int) {
        _events.trySend(
            DeviceActivityEvent.ShowToast(message, android.widget.Toast.LENGTH_LONG)
        )
    }

    companion object {
        private const val TAG = "DeviceActivityViewModel"
        private const val DYNAMIC_ADDRESS = "dynamic"

        /**
         * Converts text line to addresses array. Fault-tolerant against
         * separators like ",", ";" and whitespace.
         */
        fun persistableAddresses(userInput: String): MutableList<String> {
            if (userInput.isBlank()) {
                return mutableListOf(DYNAMIC_ADDRESS)
            }
            val input = userInput.replace(",", " ")
                .replace(";", " ")
                .replace("\\s+".toRegex(), ", ")
            return input.split(", ").filter { it.isNotEmpty() }.toMutableList()
        }

        /**
         * Converts addresses array to a text line.
         */
        fun displayableAddresses(device: Device): String {
            return device.addresses.joinToString(", ").orEmpty()
        }
    }
}
