package com.micnubinub.syncthing.util

import android.content.Context
import android.util.Log
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.FolderIgnoreList
import com.micnubinub.syncthing.model.Gui
import com.micnubinub.syncthing.model.Options
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Provides a transparent access to the config if ...
 * a) Syncthing is running and REST API is available.
 * b) Syncthing is NOT running and config.xml is accessed.
 *
 * "Not running" means [SyncthingService.isCoreRunning] is false, i.e. no core process
 * owns config.xml. It deliberately does not mean "the REST API has not loaded a config":
 * a core that is still starting already owns the file, so a write in that window would be
 * overwritten by the core or read by it as a half-written document. Writes in that window
 * are reported as [RestApi.ConfigSaveResult.SKIPPED] instead.
 */
class ConfigRouter(mContext: Context) {
    fun interface OnResultListener1<T> {
        fun onResult(t: T?)
    }

    private val configXml: ConfigXml = ConfigXml(mContext)

    /**
     * Guards every access to the shared [configXml] DOM. [ConfigRouter] is a
     * singleton which is invoked from the main thread and from IO dispatchers
     * concurrently; without this lock, concurrent loads/saves/mutations would
     * corrupt the shared XML document.
     */
    private val configXmlLock: Any = Any()

    /**
     * Drops cached config data to free memory under system memory pressure.
     * The config is re-parsed lazily on the next access.
     */
    fun clearCache() {
        synchronized(configXmlLock) {
            configXml.clearCache()
        }
    }

    fun getFolders(restApi: RestApi?): List<Folder> {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                return configXml.folders.filterNotNull()
            }
        }

        // Syncthing is running and REST API is available.
        return restApi.folders
    }

    fun getSharedFolders(restApi: RestApi?, deviceID: String?): MutableList<Folder> {
        val folders = getFolders(restApi)
        val sharedFolders: MutableList<Folder> = ArrayList(folders.size)

        for (folder in folders) {
            if (folder.getDevice(deviceID) != null) {
                // "device" is sharing "folder".
                sharedFolders.add(folder)
            }
        }

        return sharedFolders
    }

    suspend fun addFolder(restApi: RestApi?, folder: Folder): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.addFolder(folder) }
        }

        // Syncthing is running and REST API is available.
        return restApi.addFolder(folder) // This will send the config afterwards.
    }

    suspend fun ignoreFolder(
        restApi: RestApi?,
        deviceId: String,
        folderId: String,
        folderLabel: String
    ): RestApi.ConfigSaveResult {
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(
                TAG,
                "ignoreFolder failed, Syncthing is not running or REST API is not (yet) available."
            )
            return RestApi.ConfigSaveResult.SKIPPED
        }

        // Syncthing is running and REST API is available.
        return restApi.ignoreFolder(
            deviceId,
            folderId,
            folderLabel
        ) // This will send the config afterwards.
    }

    suspend fun updateFolder(restApi: RestApi?, folder: Folder): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.updateFolder(folder) }
        }

        // Syncthing is running and REST API is available.
        return restApi.updateFolder(folder) // This will send the config afterwards.
    }

    suspend fun removeFolder(restApi: RestApi?, folderId: String): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.removeFolder(folderId) }
        }

        // Syncthing is running and REST API is available.
        return restApi.removeFolder(folderId) // This will send the config after wards.
    }

    /**
     * Gets ignore list for given folder.
     */
    fun getFolderIgnoreList(
        restApi: RestApi?,
        folder: Folder,
        listener: OnResultListener1<FolderIgnoreList?>
    ) {
        if (restApi?.isConfigLoaded != true) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.getFolderIgnoreList(folder) { folderIgnoreList: FolderIgnoreList? ->
                    listener.onResult(folderIgnoreList)
                }
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.getFolderIgnoreList(folder.id) { folderIgnoreList: FolderIgnoreList? ->
            listener.onResult(folderIgnoreList)
        }
    }

    /**
     * Stores ignore list for given folder.
     *
     * @return whether the ignore list is now in effect, so a caller that reports the save
     * as done does not do so for a write the core never accepted.
     */
    suspend fun postFolderIgnoreList(
        restApi: RestApi?,
        folder: Folder,
        ignore: List<String>
    ): Boolean {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            return synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.postFolderIgnoreList(folder, ignore)
            }
        }

        // Syncthing is running and REST API is available.
        return restApi.postFolderIgnoreList(folder.id, ignore)
    }

    fun getDevices(restApi: RestApi?, includeLocal: Boolean? = false): MutableList<Device> {
        val devices = if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.getDevices(includeLocal == true).filterNotNull().toMutableList()
            }
        } else {
            // Syncthing is running and REST API is available.
            restApi.getDevices(includeLocal == true).filterNotNull().toMutableList()
        }

        return devices
    }

    suspend fun updateDevice(restApi: RestApi?, device: Device): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.updateDevice(device) }
        }

        // Syncthing is running and REST API is available.
        return restApi.updateDevice(device) // This will send the config afterwards.
    }

    suspend fun removeDevice(restApi: RestApi?, deviceID: String): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.removeDevice(deviceID) }
        }

        // Syncthing is running and REST API is available.
        return restApi.removeDevice(deviceID) // This will send the config afterwards.
    }

    suspend fun ignoreDevice(
        restApi: RestApi?,
        deviceID: String,
        deviceName: String,
        deviceAddress: String
    ): RestApi.ConfigSaveResult {
        if (restApi?.isConfigLoaded != true) {
            Log.e(
                TAG,
                "ignoreDevice failed, Syncthing is not running or REST API is not (yet) available."
            )
            return RestApi.ConfigSaveResult.SKIPPED
        }

        // Syncthing is running and REST API is available.
        return restApi.ignoreDevice(
            deviceID,
            deviceName,
            deviceAddress
        ) // This will send the config afterwards.
    }

    fun getGui(restApi: RestApi?): Gui? {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                return configXml.gui
            }
        }

        // Syncthing is running and REST API is available.
        return restApi.gui
    }

    suspend fun updateGui(restApi: RestApi?, gui: Gui): RestApi.ConfigSaveResult {
        if (!mayUseRestApi(restApi)) {
            return saveConfigXml(restApi) { configXml.updateGui(gui) }
        }

        // Syncthing is running and REST API is available.
        return restApi.updateGui(gui) // This will send the config afterwards.
    }

    /**
     * True when the change can be handed to the core through the REST API, i.e. the core
     * is running and has already loaded its config model.
     */
    @OptIn(ExperimentalContracts::class)
    private fun mayUseRestApi(restApi: RestApi?): Boolean {
        contract { returns(true) implies (restApi != null) }
        return restApi != null && restApi.isConfigLoaded
    }

    /**
     * Applies [mutate] to the shared config.xml while holding [configXmlLock] and reports
     * whether the write actually landed on disk. The result is what lets callers tell
     * "saved" apart from "silently lost", instead of toasting success on a failed write.
     *
     * [coreLifecycleMutex] is held for the whole read-modify-write so a core start or
     * teardown cannot slip in between: the file belongs to the core from the moment it is
     * started until its process is confirmed gone. [restApi] is only used to report why
     * the direct path was refused, not to decide it.
     */
    private suspend fun saveConfigXml(
        restApi: RestApi?,
        mutate: () -> Unit
    ): RestApi.ConfigSaveResult =
        SyncthingService.coreLifecycleMutex.withLock {
            if (SyncthingService.isCoreRunning) {
                Log.e(
                    TAG,
                    "saveConfigXml: refusing to write config.xml, the core owns it" +
                            (if (restApi == null) " (shutdown in progress)" else " (still starting)")
                )
                return@withLock RestApi.ConfigSaveResult.SKIPPED
            }
            withContext(Dispatchers.IO) {
                synchronized(configXmlLock) {
                    configXml.loadConfig()
                    mutate()
                    if (configXml.saveChanges()) {
                        RestApi.ConfigSaveResult.SAVED
                    } else {
                        Log.e(TAG, "saveConfigXml: could not write config.xml")
                        RestApi.ConfigSaveResult.FAILED
                    }
                }
            }
        }

    fun getOptions(restApi: RestApi?): Options? {
        if (restApi?.isConfigLoaded != true) {
            synchronized(configXmlLock) {
                configXml.loadConfig()
                return configXml.options
            }
        }

        return restApi.options
    }

    companion object {
        private const val TAG = "ConfigRouter"
    }
}
