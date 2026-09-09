package com.micnubinub.syncthing.util

import android.content.Context
import android.util.Log
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.FolderIgnoreList
import com.micnubinub.syncthing.model.Gui
import com.micnubinub.syncthing.model.Options
import com.micnubinub.syncthing.service.RestApi

/**
 * Provides a transparent access to the config if ...
 * a) Syncthing is running and REST API is available.
 * b) Syncthing is NOT running and config.xml is accessed.
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

    fun addFolder(restApi: RestApi?, folder: Folder) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.addFolder(folder)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.addFolder(folder) // This will send the config afterwards.
    }

    fun ignoreFolder(
        restApi: RestApi?,
        deviceId: String,
        folderId: String,
        folderLabel: String
    ) {
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(
                TAG,
                "ignoreFolder failed, Syncthing is not running or REST API is not (yet) available."
            )
            return
        }

        // Syncthing is running and REST API is available.
        restApi.ignoreFolder(
            deviceId,
            folderId,
            folderLabel
        ) // This will send the config afterwards.
    }

    fun updateFolder(restApi: RestApi?, folder: Folder) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.updateFolder(folder)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateFolder(folder) // This will send the config afterwards.
    }

    fun removeFolder(restApi: RestApi?, folderId: String) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.removeFolder(folderId)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.removeFolder(folderId) // This will send the config after wards.
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
     */
    fun postFolderIgnoreList(restApi: RestApi?, folder: Folder, ignore: List<String>) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.postFolderIgnoreList(folder, ignore)
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.postFolderIgnoreList(folder.id, ignore)
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

    fun updateDevice(restApi: RestApi?, device: Device) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.updateDevice(device)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateDevice(device) // This will send the config afterwards.
    }

    fun removeDevice(restApi: RestApi?, deviceID: String) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.removeDevice(deviceID)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.removeDevice(deviceID) // This will send the config afterwards.
    }

    fun ignoreDevice(
        restApi: RestApi?,
        deviceID: String,
        deviceName: String,
        deviceAddress: String
    ) {
        if (restApi?.isConfigLoaded != true) {
            Log.e(
                TAG,
                "ignoreDevice failed, Syncthing is not running or REST API is not (yet) available."
            )
            return
        }

        // Syncthing is running and REST API is available.
        restApi.ignoreDevice(
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

    fun updateGui(restApi: RestApi?, gui: Gui) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            synchronized(configXmlLock) {
                configXml.loadConfig()
                configXml.updateGui(gui)
                configXml.saveChanges()
            }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateGui(gui) // This will send the config afterwards.
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
