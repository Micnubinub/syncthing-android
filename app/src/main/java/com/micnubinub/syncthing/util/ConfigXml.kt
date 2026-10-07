package com.micnubinub.syncthing.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.favre.lib.crypto.bcrypt.BCrypt
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.model.Folder.MinDiskFree
import com.micnubinub.syncthing.model.Folder.Versioning
import com.micnubinub.syncthing.model.FolderIgnoreList
import com.micnubinub.syncthing.model.Gui
import com.micnubinub.syncthing.model.IgnoredFolder
import com.micnubinub.syncthing.model.Options
import com.micnubinub.syncthing.model.SharedWithDevice
import com.micnubinub.syncthing.service.AppPrefs
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.SyncthingRunnable
import com.micnubinub.syncthing.service.SyncthingRunnable.ExecutableNotFoundException
import com.micnubinub.syncthing.util.FileUtils.ExternalStorageDirType
import com.micnubinub.syncthing.util.Util.DEVICES_COMPARATOR
import com.micnubinub.syncthing.util.Util.FOLDERS_COMPARATOR
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.Charset
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Provides direct access to the config.xml file in the file system.
 * This class should only be used if the syncthing API is not available (usually during startup).
 */
class ConfigXml(private val context: Context) {
    private var ENABLE_VERBOSE_LOG = false
    private val configFile: File
    private var config: Document? = null

    class OpenConfigException : RuntimeException()

    fun interface OnResultListener1<T> {
        fun onResult(t: T?)
    }


    /**
     * Lazily cached local device ID. Only successful lookups are cached so a
     * transient failure (e.g. the binary not being ready yet) is retried on the
     * next access instead of poisoning the session. The cache also avoids
     * expensive child-process execs for the device ID on every read.
     */
    private var cachedLocalDeviceID: String? = null

    init {
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(context)
        configFile = Constants.getConfigFile(context)
    }

    /**
     * Parses config.xml into the in-memory DOM. This is a pure read: it never writes the
     * file, so it is safe to call on any path, including while the core owns the file.
     * Use [loadConfigAndUpdate] for the pre-start call sites that also apply migrations.
     */
    @Throws(OpenConfigException::class)
    fun loadConfig() {
        cachedLocalDeviceID = null
        parseConfig()
    }

    /**
     * [loadConfig] plus the one-time migration/repair pass ([updateIfNeeded]), which may
     * write the file back. Only call this before the core is started: the core owns
     * config.xml once it runs, and a write from here would either be overwritten by the
     * core or be read by it as a partially written file.
     */
    @Throws(OpenConfigException::class)
    fun loadConfigAndUpdate() {
        cachedLocalDeviceID = null
        parseConfig()
        updateIfNeeded()
    }

    /**
     * Drops the parsed config DOM to free memory under system memory pressure.
     * The config file is re-parsed on the next [loadConfig] call.
     */
    fun clearCache() {
        config = null
        cachedLocalDeviceID = null
    }

    /**
     * This should run within an AsyncTask as it can cause a full CPU load
     * for more than 30 seconds on older phone hardware.
     */
    @Throws(OpenConfigException::class, ExecutableNotFoundException::class)
    fun generateConfig() {
        // Create new secret keys and config.
        Log.i(TAG, "(Re)Generating keys and config.")
        SyncthingRunnable(context, SyncthingRunnable.Command.generate).run(true)
        parseConfig()
        var changed = false

        // Set local device name.
        Log.i(TAG, "Starting syncthing to retrieve local device id.")
        if (localDeviceIDandStoreToPref.isNotEmpty()) {
            changed = changeLocalDeviceName(localDeviceIDandStoreToPref) || changed
        }

        // Change default folder section.
        val elementDefaults =
            config?.documentElement?.getElementsByTagName("defaults")?.item(0) as Element?
        if (elementDefaults != null) {
            val elementDefaultFolder = elementDefaults
                .getElementsByTagName("folder").item(0) as Element?
            if (elementDefaultFolder != null) {
                val elementVersioning =
                    elementDefaultFolder.getElementsByTagName("versioning").item(0) as Element?
                if (elementVersioning != null) {
                    elementVersioning.setAttribute("type", "trashcan")
                    val nodeParam = config?.createElement("param")
                    elementVersioning.appendChild(nodeParam)
                    val elementParam = nodeParam as Element
                    elementParam.setAttribute("key", "cleanoutDays")
                    elementParam.setAttribute("val", "14")
                    changed = true
                }
            }
        }

        /* Section - GUI */
        val gui = guiElement ?: throw OpenConfigException()

        // Set user to "syncthing"
        changed = setConfigElement(gui, "user", "syncthing") || changed

        // Initialiaze password to the API key
        changed = setConfigElement(
            gui,
            "password",
            BCrypt.withDefaults().hashToString(4, apiKey.orEmpty().toCharArray())
        ) || changed
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(Constants.PREF_WEBUI_PASSWORD, apiKey)
        }

        //  Allow debug and release to run in parallel for testing purposes.
        if (Constants.isDebuggable(context)) {
            // Set alternative gui listen port.
            changed = setConfigElement(gui, "address", "127.0.0.1:8385") || changed

            // Set alternative data listen port.
            val elementOptions =
                config?.documentElement?.getElementsByTagName("options")?.item(0) as Element?
            elementOptions?.let {
                changed = setConfigElement(
                    it, "listenAddress", listOf(
                        "tcp://:22001",
                        "dynamic+https://relays.syncthing.net/endpoint"
                    )
                ) || changed
            }
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    private val localDeviceIDfromPref: String
        get() {
            cachedLocalDeviceID?.let { return it }
            var localDeviceID: String =
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(
                        Constants.PREF_LOCAL_DEVICE_ID,
                        ""
                    ) ?: ""
            if (localDeviceID.isEmpty()) {
                Log.d(
                    TAG,
                    "getLocalDeviceIDfromPref: Local device ID unavailable, trying to retrieve it from syncthing ..."
                )
                try {
                    localDeviceID = this.localDeviceIDandStoreToPref
                } catch (e: ExecutableNotFoundException) {
                    Log.e(
                        TAG,
                        "getLocalDeviceIDfromPref: Failed to execute syncthing core"
                    )
                }
                if (localDeviceID.isEmpty()) {
                    Log.e(
                        TAG,
                        "getLocalDeviceIDfromPref: Local device ID unavailable"
                    )
                }
            }
            if (localDeviceID.isNotEmpty()) {
                cachedLocalDeviceID = localDeviceID
            }
            return localDeviceID
        }

    @get:Throws(ExecutableNotFoundException::class)
    private val localDeviceIDandStoreToPref: String
        get() {
            Log.d(
                TAG,
                SyncthingRunnable.Command.entries.toString()
            )
            Log.d(
                TAG,
                "getLocalDeviceIDandStoreToPref: Retrieving local device ID from syncthing ..."
            )
            val logOutput =
                SyncthingRunnable(context, SyncthingRunnable.Command.deviceid).run(true)
            val localDeviceID = logOutput.replace("\n", "")

            // Verify that local device ID is correctly formatted.
            val localDevice = Device()
            localDevice.deviceID = localDeviceID
            if (!localDevice.checkDeviceID()) {
                Log.w(
                    TAG,
                    "getLocalDeviceIDandStoreToPref: Syncthing core returned a bad formatted device ID \"$localDeviceID\""
                )
                return ""
            }

            // Store local device ID to pref. This saves us expensive calls to the syncthing binary if we need it later.
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(
                    Constants.PREF_LOCAL_DEVICE_ID,
                    localDeviceID
                )
            }
            Log.d(
                TAG,
                "getLocalDeviceIDandStoreToPref: Cached local device ID \"$localDeviceID\""
            )
            return localDeviceID
        }

    private fun parseConfig() {
        if (!configFile.canRead()) {
            Log.w(TAG, "Failed to open config file '$configFile'")
            throw OpenConfigException()
        }
        try {
            FileInputStream(configFile).use { inputStream ->
                val inputStreamReader = InputStreamReader(inputStream, Charset.forName("UTF-8"))
                val inputSource = InputSource(inputStreamReader)
                inputSource.encoding = "UTF-8"
                val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                config = db.parse(inputSource)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse config file '$configFile'", e)
            throw OpenConfigException()
        }
    }

    val webGuiUrl: URL
        get() {
            // TLS is always enforced; cleartext HTTP would expose the API key.
            val urlProtocol = "https"
            try {
                return URL(
                    "$urlProtocol://" + this.guiElement?.getElementsByTagName("address")?.item(0)
                        ?.textContent
                )
            } catch (e: MalformedURLException) {
                throw RuntimeException("Failed to parse web interface URL", e)
            }
        }

    val webGuiBindPort: Int
        get() {
            try {
                val gui = Gui()
                gui.address =
                    guiElement?.getElementsByTagName("address")?.item(0)?.textContent
                return gui.bindPort.toInt()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "getWebGuiBindPort: Failed with exception: ",
                    e
                )
                return Constants.DEFAULT_WEBGUI_TCP_PORT
            }
        }

    val apiKey: String
        get() = guiElement?.getElementsByTagName("apikey")?.item(0)?.textContent.orEmpty()

    val webUIUsername: String
        get() {
            return guiElement?.getElementsByTagName("user")?.item(0)?.textContent.orEmpty()
        }

    val webUIPassword: String
        get() = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.PREF_WEBUI_PASSWORD, "").orEmpty()

    /**
     * Updates the config file.
     * Sets ignorePerms flag to true on every folder, force enables TLS, sets the
     * username/password.
     */
    private fun updateIfNeeded() {
        /* Perform one-time migration tasks on syncthing's config file when coming from an older config version. */
        var changed = migrateSyncthingOptions()

        /* Get refs to important config objects */
        val folders = config?.documentElement?.getElementsByTagName("folder")

        /* Section - folders */
        for (i in 0..<(folders?.length ?: 0)) {
            val r = folders?.item(i) as Element
            // Set ignorePerms attribute.
            if (!r.hasAttribute("ignorePerms") ||
                !r.getAttribute("ignorePerms").toBoolean()
            ) {
                Log.i(TAG, "Set 'ignorePerms' on folder " + r.getAttribute("id"))
                r.setAttribute("ignorePerms", true.toString())
                changed = true
            }

            // Set 'hashers' on the given folder.
            changed = setConfigElement(r, "hashers", "1") || changed
        }

        /* Section - GUI */
        val gui = guiElement ?: throw OpenConfigException()

        // Platform-specific: Force REST API and Web UI access to use TLS.
        // Cleartext HTTP is never acceptable as it would expose the API key and
        // GUI credentials.
        val forceHttps = true
        if (!gui.hasAttribute("tls") ||
            gui.getAttribute("tls").toBoolean() != forceHttps
        ) {
            gui.setAttribute("tls", forceHttps.toString())
            changed = true
        }

        // Enforce GUI authentication: without it, anyone able to reach the GUI
        // address could control the local syncthing instance. If user or
        // password are missing/empty (e.g. crafted or stripped config), restore
        // the default user and set the password to the (strong, random) API key.
        val guiUser = getContentOrDefault(gui.getElementsByTagName("user").item(0), "")
        val guiPassword = getContentOrDefault(gui.getElementsByTagName("password").item(0), "")
        if (guiUser.isEmpty() || guiPassword.isEmpty()) {
            Log.w(TAG, "GUI user/password missing, enforcing GUI authentication.")
            changed = setConfigElement(gui, "user", "syncthing") || changed
            changed = setConfigElement(
                gui,
                "password",
                BCrypt.withDefaults().hashToString(4, apiKey.orEmpty().toCharArray())
            ) || changed
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(Constants.PREF_WEBUI_PASSWORD, apiKey)
            }
        }

        /* Section - options */
        val options = config?.documentElement?.getElementsByTagName("options")?.item(0) as Element

        /* Dismiss "fsWatcherNotification" according to https://github.com/syncthing/syncthing-android/pull/1051 */
        val childNodes = options.childNodes
        for (i in 0..<childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "unackedNotificationID") {
                when (val notificationType = getContentOrDefault(node, "")) {
                    "authenticationUserAndPassword", "crAutoEnabled", "crAutoDisabled", "fsWatcherNotification" -> {
                        Log.i(TAG, "Remove found unackedNotificationID '$notificationType'.")
                        options.removeChild(node)
                        changed = true
                    }
                }
            }
        }

        // Disable "startBrowser" because it applies to desktop environments and cannot start a mobile browser app.
        val defaultOptions = Options()
        changed = setConfigElement(options, "startBrowser", defaultOptions.startBrowser) || changed

        /**
         * Disable Syncthing's NAT feature because it causes kernel oops on some buggy kernels.
         */
        if (Constants.osHasKernelBugIssue505()) {
            val natEnabledChanged = setConfigElement(options, "natEnabled", false)
            if (natEnabledChanged) {
                Log.d(TAG, "Disabling NAT option because a buggy kernel was detected.")
                changed = true
            }
        }

        // Add the "Syncthing Camera" folder if the user consented to use the feature.
        val prefEnableSyncthingCamera =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        if (prefEnableSyncthingCamera) {
            changed = addSyncthingCameraFolder() || changed
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    /**
     * Updates syncthing options to a version specific target setting in the config file.
     * Used for one-time config migration from a lower syncthing version to the current version.
     * Enables filesystem watcher.
     * Returns if changes to the config have been made.
     */
    private fun migrateSyncthingOptions(): Boolean {
        val defaultFolder = Folder()

        /* Read existing config version */
        var iConfigVersion = getAttributeOrDefault(config?.documentElement, "version", 0)
        val iOldConfigVersion = iConfigVersion

        /* Check if we have to do manual migration from version X to Y */
        if (iConfigVersion == 27) {
            /* fsWatcher transition */
            Log.i(TAG, "Migrating config version $iConfigVersion to 28 ...")

            /* Enable fsWatcher for all folders */
            val folders = config?.documentElement?.getElementsByTagName("folder")
            for (i in 0..<(folders?.length ?: 0)) {
                val r = folders?.item(i) as Element

                // Enable "fsWatcherEnabled" attribute and set default delay.
                Log.i(
                    TAG,
                    "Set 'fsWatcherEnabled', 'fsWatcherDelayS' on folder " + r.getAttribute("id")
                )
                r.setAttribute("fsWatcherEnabled", defaultFolder.fsWatcherEnabled.toString())
                r.setAttribute("fsWatcherDelayS", defaultFolder.fsWatcherDelayS.toString())
            }

            /**
             * Set config version to 28 after manual config migration
             * This prevents "unackedNotificationID" getting populated
             * with the fsWatcher GUI notification.
             */
            iConfigVersion = 28
        }

        if (iConfigVersion == iOldConfigVersion) {
            return false
        }
        config?.documentElement?.setAttribute("version", iConfigVersion.toString())
        Log.i(
            TAG, "Old config version was " + iOldConfigVersion.toString() +
                    ", new config version is " + iConfigVersion.toString()
        )
        return true
    }

    private fun getAttributeOrDefault(
        element: Element,
        attribute: String?,
        defaultValue: Boolean
    ): Boolean {
        return if (element.hasAttribute(attribute)) element.getAttribute(attribute)
            .toBoolean() else defaultValue
    }

    private fun getAttributeOrDefault(
        element: Element?,
        attribute: String?,
        defaultValue: Int
    ): Int {
        return try {
            (element?.getAttribute(attribute)?.toInt() ?: defaultValue)
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getAttributeOrDefault(
        element: Element,
        attribute: String?,
        defaultValue: Float
    ): Float {
        return try {
            if (element.hasAttribute(attribute)) element.getAttribute(attribute)
                .toFloat() else defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getAttributeOrDefault(
        element: Element,
        attribute: String?,
        defaultValue: String?
    ): String? {
        return if (element.hasAttribute(attribute)) element.getAttribute(attribute) else defaultValue
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Boolean): Boolean {
        return node?.textContent?.toBoolean() ?: defaultValue
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Int): Int {
        return try {
            node?.textContent?.toInt() ?: defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Float): Float {
        return try {
            node?.textContent?.toFloat() ?: defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getContentOrDefault(node: Node?, defaultValue: String): String {
        return node?.textContent ?: defaultValue
    }

    val folders: MutableList<Folder>
        get() {
            val localDeviceID = this.localDeviceIDfromPref
            val folders: MutableList<Folder> = ArrayList()

            // Prevent enumerating "<folder>" tags below "<default>" nodes by enumerating child nodes manually.
            val childNodes = config?.documentElement?.childNodes
            for (i in 0..<(childNodes?.length ?: 0)) {
                val node = childNodes?.item(i)
                if (node?.nodeName != "folder") {
                    continue
                }
                val r = node as Element
                val folder = Folder()
                folder.id = getAttributeOrDefault(r, "id", "")
                folder.group = getAttributeOrDefault(r, "group", folder.group).orEmpty()
                folder.label = getAttributeOrDefault(r, "label", folder.label).orEmpty()

                folder.path = getAttributeOrDefault(r, "path", "")
                if (folder.path?.startsWith("~/") == true) {
                    folder.path = folder.path?.replaceFirst(
                        "^~".toRegex(),
                        FileUtils.syncthingTildeAbsolutePath
                    )
                }

                folder.type = getAttributeOrDefault(
                    r,
                    "type",
                    Constants.FOLDER_TYPE_SEND_RECEIVE
                ) ?: Constants.FOLDER_TYPE_SEND_RECEIVE
                folder.autoNormalize =
                    getAttributeOrDefault(r, "autoNormalize", folder.autoNormalize)
                folder.fsWatcherDelayS =
                    getAttributeOrDefault(r, "fsWatcherDelayS", folder.fsWatcherDelayS)
                folder.fsWatcherEnabled =
                    getAttributeOrDefault(r, "fsWatcherEnabled", folder.fsWatcherEnabled)
                folder.ignorePerms = getAttributeOrDefault(r, "ignorePerms", folder.ignorePerms)
                folder.rescanIntervalS =
                    getAttributeOrDefault(r, "rescanIntervalS", folder.rescanIntervalS)

                folder.copiers =
                    getContentOrDefault(r.getElementsByTagName("copiers").item(0), folder.copiers)
                folder.hashers =
                    getContentOrDefault(r.getElementsByTagName("hashers").item(0), folder.hashers)
                folder.order =
                    getContentOrDefault(r.getElementsByTagName("order").item(0), folder.order)
                folder.paused =
                    getContentOrDefault(r.getElementsByTagName("paused").item(0), folder.paused)
                folder.ignoreDelete = getContentOrDefault(
                    r.getElementsByTagName("ignoreDelete").item(0),
                    folder.ignoreDelete
                )
                folder.copyOwnershipFromParent = getContentOrDefault(
                    r.getElementsByTagName("copyOwnershipFromParent").item(0),
                    folder.copyOwnershipFromParent
                )
                folder.modTimeWindowS = getContentOrDefault(
                    r.getElementsByTagName("modTimeWindowS").item(0),
                    folder.modTimeWindowS
                )
                folder.blockPullOrder = getContentOrDefault(
                    r.getElementsByTagName("blockPullOrder").item(0),
                    folder.blockPullOrder
                )
                folder.disableFsync = getContentOrDefault(
                    r.getElementsByTagName("disableFsync").item(0),
                    folder.disableFsync
                )
                folder.maxConcurrentWrites = getContentOrDefault(
                    r.getElementsByTagName("maxConcurrentWrites").item(0),
                    folder.maxConcurrentWrites
                )
                folder.maxConflicts = getContentOrDefault(
                    r.getElementsByTagName("maxConflicts").item(0),
                    folder.maxConflicts
                )
                folder.copyRangeMethod = getContentOrDefault(
                    r.getElementsByTagName("copyRangeMethod").item(0),
                    folder.copyRangeMethod
                )
                folder.caseSensitiveFS = getContentOrDefault(
                    r.getElementsByTagName("caseSensitiveFS").item(0),
                    folder.caseSensitiveFS
                )
                folder.syncOwnership = getContentOrDefault(
                    r.getElementsByTagName("syncOwnership").item(0),
                    folder.syncOwnership
                )
                folder.sendOwnership = getContentOrDefault(
                    r.getElementsByTagName("sendOwnership").item(0),
                    folder.sendOwnership
                )
                folder.syncXattrs = getContentOrDefault(
                    r.getElementsByTagName("syncXattrs").item(0),
                    folder.syncXattrs
                )
                folder.sendXattrs = getContentOrDefault(
                    r.getElementsByTagName("sendXattrs").item(0),
                    folder.sendXattrs
                )
                folder.blockIndexing = getContentOrDefault(
                    r.getElementsByTagName("blockIndexing").item(0),
                    folder.blockIndexing
                )
                folder.filesystemType = getContentOrDefault(
                    r.getElementsByTagName("filesystemType").item(0),
                    folder.filesystemType
                )

                // Devices
                /*
                 <device id="[DEVICE_ID]" introducedBy=""/>
                 */
                val nodeDevices = r.getElementsByTagName("device")
                for (j in 0..<nodeDevices.length) {
                    val elementDevice = nodeDevices.item(j) as Element
                    val device = SharedWithDevice()
                    device.deviceID = getAttributeOrDefault(elementDevice, "id", "")

                    // Exclude self.
                    if (!device.deviceID.isNullOrEmpty() && device.deviceID != localDeviceID) {
                        device.introducedBy = getAttributeOrDefault(
                            elementDevice,
                            "introducedBy",
                            device.introducedBy
                        ) ?: device.introducedBy
                        // LogV("getFolders: deviceID=" + device.deviceID + ", introducedBy=" + device.introducedBy);
                        device.encryptionPassword = getContentOrDefault(
                            elementDevice.getElementsByTagName("encryptionPassword").item(0),
                            device.encryptionPassword
                        )
                        folder.addDevice(device)
                    }
                }

                // MinDiskFree
                /*
                 <minDiskFree unit="MB">5</minDiskFree>
                 */
                folder.minDiskFree = MinDiskFree()
                val elementMinDiskFree =
                    r.getElementsByTagName("minDiskFree").item(0) as Element?
                if (elementMinDiskFree != null) {
                    folder.minDiskFree?.unit =
                        getAttributeOrDefault(elementMinDiskFree, "unit", folder.minDiskFree?.unit)
                            ?: "%"
                    folder.minDiskFree?.value =
                        getContentOrDefault(elementMinDiskFree, folder.minDiskFree?.value ?: 1f)
                }

                // LogV("folder.minDiskFree.unit=" + folder.minDiskFree.unit + ", folder.minDiskFree.value=" + folder.minDiskFree.value);

                // Versioning
                /*
                 <versioning></versioning>
                 <versioning type="trashcan">
                     <param key="cleanoutDays" val="90"></param>
                     <cleanupIntervalS>3600</cleanupIntervalS>
                     <fsPath></fsPath>
                     <fsType>basic</fsType>
                 </versioning>
                 */
                folder.versioning = Versioning()
                val elementVersioning =
                    r.getElementsByTagName("versioning").item(0) as Element?
                if (elementVersioning != null) {
                    folder.versioning?.type = getAttributeOrDefault(elementVersioning, "type", "")
                    folder.versioning?.cleanupIntervalS = getContentOrDefault(
                        elementVersioning.getElementsByTagName("cleanupIntervalS").item(0), 3600
                    )
                    folder.versioning?.fsPath = getContentOrDefault(
                        elementVersioning.getElementsByTagName("fsPath").item(0), ""
                    )
                    folder.versioning?.fsType = getContentOrDefault(
                        elementVersioning.getElementsByTagName("fsType").item(0), "basic"
                    )
                    val nodeVersioningParam =
                        elementVersioning.getElementsByTagName("param")
                    for (j in 0..<nodeVersioningParam.length) {
                        val elementVersioningParam =
                            nodeVersioningParam.item(j) as Element
                        folder.versioning?.params?.put(
                            getAttributeOrDefault(elementVersioningParam, "key", "").orEmpty(),
                            getAttributeOrDefault(elementVersioningParam, "val", "").orEmpty()
                        )
                        /*
         Log.v(TAG, "folder.versioning.type=" + folder.versioning.type +
                 ", key=" + getAttributeOrDefault(elementVersioningParam, "key", "") +
                 ", val=" + getAttributeOrDefault(elementVersioningParam, "val", "")
         );
         */
                    }
                }

                // For testing purposes only.
                // LogV("folder.label=" + folder.label + "/" +"folder.type=" + folder.type + "/" + "folder.paused=" + folder.paused);
                folders.add(folder)
            }
            Collections.sort(
                folders,
                FOLDERS_COMPARATOR
            )
            return folders
        }

    fun addFolder(folder: Folder) {
        Log.d(TAG, "addFolder: folder.id=" + folder.id)
        val nodeConfig = config?.documentElement
        val nodeFolder = config?.createElement("folder")
        nodeConfig?.appendChild(nodeFolder)
        val elementFolder = nodeFolder as Element
        elementFolder.setAttribute("id", folder.id)
        updateFolder(folder)
    }

    fun updateFolder(folder: Folder) {
        val nodeFolders = config?.documentElement?.childNodes?.let { childNodes ->
            (0 until childNodes.length)
                .mapNotNull { childNodes.item(it) as? Element }
                .filter { it.tagName == "folder" }
        } ?: emptyList()
        for (i in nodeFolders.indices) {
            val r = nodeFolders[i]
            if (folder.id == getAttributeOrDefault(r, "id", "")) {
                // Found folder node to update.
                r.setAttribute("group", folder.group)
                r.setAttribute("label", folder.label)
                r.setAttribute("path", folder.path)
                r.setAttribute("type", folder.type)
                r.setAttribute("autoNormalize", folder.autoNormalize.toString())
                r.setAttribute("fsWatcherDelayS", folder.fsWatcherDelayS.toString())
                r.setAttribute("fsWatcherEnabled", folder.fsWatcherEnabled.toString())
                r.setAttribute("ignorePerms", folder.ignorePerms.toString())
                r.setAttribute("rescanIntervalS", folder.rescanIntervalS.toString())

                setConfigElement(r, "copiers", folder.copiers.toString())
                setConfigElement(r, "hashers", folder.hashers.toString())
                setConfigElement(r, "order", folder.order)
                setConfigElement(r, "paused", folder.paused)
                setConfigElement(r, "ignoreDelete", folder.ignoreDelete)
                setConfigElement(r, "copyOwnershipFromParent", folder.copyOwnershipFromParent)
                setConfigElement(r, "modTimeWindowS", folder.modTimeWindowS.toString())
                setConfigElement(r, "blockPullOrder", folder.blockPullOrder)
                setConfigElement(r, "disableFsync", folder.disableFsync)
                setConfigElement(r, "maxConcurrentWrites", folder.maxConcurrentWrites.toString())
                setConfigElement(r, "maxConflicts", folder.maxConflicts.toString())
                setConfigElement(r, "copyRangeMethod", folder.copyRangeMethod)
                setConfigElement(r, "caseSensitiveFS", folder.caseSensitiveFS)
                setConfigElement(r, "syncOwnership", folder.syncOwnership)
                setConfigElement(r, "sendOwnership", folder.sendOwnership)
                setConfigElement(r, "syncXattrs", folder.syncXattrs)
                setConfigElement(r, "sendXattrs", folder.sendXattrs)
                setConfigElement(r, "blockIndexing", folder.blockIndexing)
                setConfigElement(r, "filesystemType", folder.filesystemType)

                // Update devices that share this folder.
                // Pass 1: Remove all devices below that folder in XML except the local device.
                val nodeDevices = r.getElementsByTagName("device")
                for (j in nodeDevices.length - 1 downTo 0) {
                    val elementDevice = nodeDevices.item(j) as Element
                    if (getAttributeOrDefault(elementDevice, "id", "") != localDeviceIDfromPref) {
                        Log.d(
                            TAG,
                            "updateFolder: nodeDevices: Removing deviceID=" + getAttributeOrDefault(
                                elementDevice,
                                "id",
                                ""
                            )
                        )
                        removeChildElementFromTextNode(r, elementDevice)
                    }
                }

                // Pass 2: Add devices below that folder from the POJO model.
                folder.devices.let {
                    for (device in it) {
                        Log.d(TAG, "updateFolder: nodeDevices: Adding deviceID=" + device.deviceID)
                        val nodeDevice = config?.createElement("device")
                        r.appendChild(nodeDevice)
                        val elementDevice = nodeDevice as Element
                        elementDevice.setAttribute("id", device.deviceID)
                        elementDevice.setAttribute("introducedBy", device.introducedBy)
                        setConfigElement(
                            elementDevice,
                            "encryptionPassword",
                            device.encryptionPassword
                        )
                    }
                }


                // minDiskFree
                folder.minDiskFree?.let {
                    // Pass 1: Remove all minDiskFree nodes from XML (usually one)
                    var elementMinDiskFree =
                        r.getElementsByTagName("minDiskFree").item(0) as Element?
                    if (elementMinDiskFree != null) {
                        Log.d(TAG, "updateFolder: nodeMinDiskFree: Removing minDiskFree node")
                        removeChildElementFromTextNode(r, elementMinDiskFree)
                    }

                    // Pass 2: Add minDiskFree node from the POJO model to XML.
                    val nodeMinDiskFree = config?.createElement("minDiskFree")
                    r.appendChild(nodeMinDiskFree)
                    elementMinDiskFree = nodeMinDiskFree
                    elementMinDiskFree?.setAttribute("unit", it.unit)
                    setConfigElement(r, "minDiskFree", it.value.toString())
                }

                // Versioning
                // Pass 1: Remove all versioning nodes from XML (usually one)
                var elementVersioning = r.getElementsByTagName("versioning").item(0) as Element?
                if (elementVersioning != null) {
                    Log.d(TAG, "updateFolder: nodeVersioning: Removing versioning node")
                    removeChildElementFromTextNode(r, elementVersioning)
                }

                // Pass 2: Add versioning node from the POJO model to XML.
                val nodeVersioning = config?.createElement("versioning")
                r.appendChild(nodeVersioning)
                elementVersioning = nodeVersioning
                if (!folder.versioning?.type.isNullOrEmpty()) {
                    elementVersioning?.setAttribute("type", folder.versioning?.type)
                    setConfigElement(
                        elementVersioning,
                        "cleanupIntervalS",
                        folder.versioning?.cleanupIntervalS.toString()
                    )
                    folder.versioning?.let {
                        setConfigElement(elementVersioning, "fsPath", it.fsPath ?: "")
                        setConfigElement(elementVersioning, "fsType", it.fsType ?: "")
                        for (param in it.params.entries) {
                            Log.d(
                                TAG,
                                "updateFolder: nodeVersioning: Adding param key=" + param.key + ", val=" + param.value
                            )
                            val nodeParam = config?.createElement("param")
                            elementVersioning?.appendChild(nodeParam)
                            val elementParam = nodeParam as Element
                            elementParam.setAttribute("key", param.key)
                            elementParam.setAttribute("val", param.value)
                        }
                    }

                }

                break
            }
        }
    }

    fun removeFolder(folderId: String) {
        val nodeFolders = config?.documentElement?.childNodes?.let { childNodes ->
            (0 until childNodes.length)
                .mapNotNull { childNodes.item(it) as? Element }
                .filter { it.tagName == "folder" }
        } ?: emptyList()
        for (i in nodeFolders.indices.reversed()) {
            val r = nodeFolders[i]
            if (folderId == getAttributeOrDefault(r, "id", "")) {
                // Found folder node to remove.
                Log.d(TAG, "removeFolder: Removing folder node, folderId=$folderId")
                (r.parentNode as? Element)?.let {
                    removeChildElementFromTextNode(it, r)
                }

                break
            }
        }
    }

    fun setFolderPause(folderId: String?, paused: Boolean) {
        val nodeFolders = config?.documentElement?.childNodes?.let { childNodes ->
            (0 until childNodes.length)
                .mapNotNull { childNodes.item(it) as? Element }
                .filter { it.tagName == "folder" }
        } ?: emptyList()
        for (i in nodeFolders.indices) {
            val r = nodeFolders[i]
            if (getAttributeOrDefault(r, "id", "") == folderId) {
                setConfigElement(r, "paused", paused)
                break
            }
        }
    }

    /**
     * Gets ignore list for given folder.
     */
    fun getFolderIgnoreList(folder: Folder, listener: OnResultListener1<FolderIgnoreList?>) {
        val folderIgnoreList = FolderIgnoreList()
        try {
            val file = File(folder.path, Constants.FILENAME_STIGNORE)
            if (file.exists()) {
                FileInputStream(file).use { fileInputStream ->
                    folderIgnoreList.ignore = String(
                        fileInputStream.readBytes(),
                        Charset.forName("UTF-8")
                    ).split("\n".toRegex())
                }

            } else {
                // File not found.
                Log.w(TAG, "getFolderIgnoreList: File missing $file")
                /**
                 * Don't fail as the file might be expectedly missing when users didn't
                 * set ignores in the past storyline of that folder.
                 */
            }
        } catch (e: IOException) {
            Log.e(
                TAG,
                "getFolderIgnoreList: Failed to read '" + folder.path + "/" + Constants.FILENAME_STIGNORE + "' #1",
                e
            )
        }
        listener.onResult(folderIgnoreList)
    }

    /**
     * Stores ignore list for given folder.
     */
    /**
     * @return whether the file was written; see [ConfigRouter.postFolderIgnoreList].
     */
    fun postFolderIgnoreList(folder: Folder, ignore: List<String>): Boolean {
        try {
            val file = File(folder.path, Constants.FILENAME_STIGNORE)
            // Write to a temp file, fsync, then atomically rename into place so a
            // crash mid-write never leaves a partial .stignore behind.
            val tempFile = File.createTempFile("stignore-", ".tmp", file.parentFile)
            try {
                FileOutputStream(tempFile).use {
                    it.write(
                        ignore.filterNotNull().joinToString("\n").toByteArray(Charsets.UTF_8)
                    )
                    it.fd.sync()
                }
                if (!tempFile.renameTo(file)) {
                    throw IOException("Failed to rename temp file to '" + file.path + "'")
                }
            } finally {
                tempFile.delete()
            }
            return true
        } catch (e: IOException) {
            /**
             * This will happen on external storage folders which exist outside the
             * "/Android/data/[package_name]/files" folder on Android 5+.
             */
            Log.w(
                TAG,
                "postFolderIgnoreList: Failed to write '" + folder.path + "/" + Constants.FILENAME_STIGNORE + "' #1",
                e
            )
            return false
        }
    }

    fun getDevices(includeLocal: Boolean): MutableList<Device?> {
        val localDeviceID = this.localDeviceIDfromPref
        val devices = ArrayList<Device?>()

        // Prevent enumerating "<device>" tags below "<defaults>", "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config?.documentElement?.childNodes
        for (i in 0..<(childNodes?.length ?: 0)) {
            val node = childNodes?.item(i)
            if (node?.nodeName != "device") {
                continue
            }
            val r = node as Element
            val device = Device()
            device.compression =
                getAttributeOrDefault(r, "compression", device.compression) ?: device.compression
            device.deviceID = getAttributeOrDefault(r, "id", "") ?: ""
            device.introducedBy =
                getAttributeOrDefault(r, "introducedBy", device.introducedBy) ?: device.introducedBy
            device.introducer =
                getAttributeOrDefault(r, "introducer", device.introducer)
            device.name = getAttributeOrDefault(r, "name", device.name) ?: device.name
            device.autoAcceptFolders = getContentOrDefault(
                r.getElementsByTagName("autoAcceptFolders").item(0),
                device.autoAcceptFolders
            )
            device.maxRecvKbps = getContentOrDefault(
                r.getElementsByTagName("maxRecvKbps").item(0),
                device.maxRecvKbps
            )
            device.maxSendKbps = getContentOrDefault(
                r.getElementsByTagName("maxSendKbps").item(0),
                device.maxSendKbps
            )
            device.paused =
                getContentOrDefault(r.getElementsByTagName("paused").item(0), device.paused)
            device.untrusted =
                getContentOrDefault(r.getElementsByTagName("untrusted").item(0), device.untrusted)
            device.numConnections = getContentOrDefault(
                r.getElementsByTagName("numConnections").item(0),
                device.numConnections
            )

            // Addresses
            /*
            <device ...>
                <address>dynamic</address>
                <address>tcp4://192.168.1.67:2222</address>
            </device>
            */
            device.addresses.clear()
            val nodeAddresses = r.getElementsByTagName("address")
            for (j in 0..<nodeAddresses.length) {
                val address = getContentOrDefault(nodeAddresses.item(j), "")
                device.addresses.add(address)
                // LogV("getDevices: address=" + address);
            }

            // Allowed Networks
            /*
            <device ...>
                <allowedNetwork>192.168.0.0/24</allowedNetwork>
                <allowedNetwork>192.168.1.0/24</allowedNetwork>
            </device>
            */
            device.allowedNetworks.clear()
            val nodeAllowedNetworks = r.getElementsByTagName("allowedNetwork")
            for (j in 0..<nodeAllowedNetworks.length) {
                val allowedNetwork = getContentOrDefault(nodeAllowedNetworks.item(j), "")
                device.allowedNetworks.add(allowedNetwork)
                // LogV("getDevices: allowedNetwork=" + allowedNetwork);
            }

            // ignoredFolders
            device.ignoredFolders = ArrayList<IgnoredFolder>()
            val nodeIgnoredFolders = r.getElementsByTagName("ignoredFolder")
            for (j in 0..<nodeIgnoredFolders.length) {
                val elementIgnoredFolder = nodeIgnoredFolders.item(j) as Element
                val ignoredFolder = IgnoredFolder()
                ignoredFolder.id =
                    getAttributeOrDefault(elementIgnoredFolder, "id", ignoredFolder.id)
                        ?: ignoredFolder.id
                ignoredFolder.label =
                    getAttributeOrDefault(elementIgnoredFolder, "label", ignoredFolder.label)
                        ?: ignoredFolder.label
                ignoredFolder.time =
                    getAttributeOrDefault(elementIgnoredFolder, "time", ignoredFolder.time)
                        ?: ignoredFolder.time

                // LogV("getDevices: ignoredFolder=[id=" + ignoredFolder.id + ", label=" + ignoredFolder.label + ", time=" + ignoredFolder.time + "]");
                device.ignoredFolders?.add(ignoredFolder)
            }

            // For testing purposes only.
            // LogV("getDevices: device.name=" + device.name + "/" +"device.id=" + device.deviceID + "/" + "device.paused=" + device.paused);

            // Exclude self if requested.
            val isLocalDevice = !device.deviceID.isNullOrEmpty() && device.deviceID == localDeviceID
            if (includeLocal || !isLocalDevice) {
                devices.add(device)
            }
        }
        Collections.sort<Device?>(devices, DEVICES_COMPARATOR)
        return devices
    }

    /**
     * Adds or updates a device identified by its device ID.
     */
    fun updateDevice(device: Device) {
        var deviceExists = false

        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        config?.documentElement?.childNodes?.let { childNodes ->
            for (i in 0..<childNodes.length) {
                val node = childNodes.item(i)
                if (node?.nodeName == "device") {
                    val r = node as Element
                    if (device.deviceID == getAttributeOrDefault(r, "id", "")) {
                        deviceExists = true
                        break
                    }
                }
            }
        }

        // If the device does not exist in config, add it.
        if (!deviceExists) {
            Log.d(
                TAG,
                "updateDevice: [addDevice] Adding deviceID='" + device.deviceID + "' to config ..."
            )
            val nodeConfig = config?.documentElement
            val nodeDevice = config?.createElement("device")
            nodeConfig?.appendChild(nodeDevice)
            nodeDevice?.setAttribute("id", device.deviceID)
        }

        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        config?.documentElement?.childNodes?.let { childNodes ->
            for (i in 0..<childNodes.length) {
                val node = childNodes.item(i)
                if (node?.nodeName == "device") {
                    val r = node as Element
                    if (device.deviceID == getAttributeOrDefault(r, "id", "")) {
                        // Found device to update.
                        r.setAttribute("compression", device.compression)
                        r.setAttribute("introducedBy", device.introducedBy)
                        r.setAttribute("introducer", device.introducer.toString())
                        r.setAttribute("name", device.name)

                        setConfigElement(r, "autoAcceptFolders", device.autoAcceptFolders)
                        setConfigElement(r, "paused", device.paused)
                        setConfigElement(r, "untrusted", device.untrusted)
                        setConfigElement(r, "numConnections", device.numConnections.toString())

                        // Addresses
                        // Pass 1: Remove all addresses in XML.
                        val nodeAddresses = r.getElementsByTagName("address")
                        for (j in nodeAddresses.length - 1 downTo 0) {
                            val elementAddress = nodeAddresses.item(j) as Element
                            Log.d(
                                TAG,
                                "updateDevice: nodeAddresses: Removing address=" + getContentOrDefault(
                                    elementAddress,
                                    ""
                                )
                            )
                            removeChildElementFromTextNode(r, elementAddress)
                        }

                        // Pass 2: Add addresses from the POJO model.
                        for (address in device.addresses) {
                            Log.d(TAG, "updateDevice: nodeAddresses: Adding address=$address")
                            val nodeAddress = config?.createElement("address")
                            r.appendChild(nodeAddress)
                            val elementAddress = nodeAddress as Element
                            elementAddress.textContent = address
                        }

                        // Allowed Networks
                        // Pass 1: Remove all allowed networks in XML.
                        val nodeAllowedNetworks = r.getElementsByTagName("allowedNetwork")
                        for (j in nodeAllowedNetworks.length - 1 downTo 0) {
                            val elementAllowedNetwork = nodeAllowedNetworks.item(j) as Element
                            Log.d(
                                TAG,
                                "updateDevice: nodeAllowedNetworks: Removing allowedNetwork=" + getContentOrDefault(
                                    elementAllowedNetwork,
                                    ""
                                )
                            )
                            removeChildElementFromTextNode(r, elementAllowedNetwork)
                        }

                        // Pass 2: Add allowed networks from the POJO model.
                        for (allowedNetwork in device.allowedNetworks) {
                            Log.d(
                                TAG,
                                "updateDevice: nodeAllowedNetworks: Adding allowedNetwork=$allowedNetwork"
                            )
                            val nodeAllowedNetwork = config?.createElement("allowedNetwork")
                            r.appendChild(nodeAllowedNetwork)
                            val elementAllowedNetwork = nodeAllowedNetwork as Element
                            elementAllowedNetwork.textContent = allowedNetwork
                        }

                        break
                    }
                }
            }
        }
    }

    fun removeDevice(deviceID: String) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config?.documentElement?.childNodes
        for (i in 0..<(childNodes?.length ?: 0)) {
            val node = childNodes?.item(i)
            if (node?.nodeName == "device") {
                val r = node as Element
                if (deviceID == getAttributeOrDefault(r, "id", "")) {
                    // Found device to remove.
                    Log.d(TAG, "removeDevice: Removing device node, deviceID=$deviceID")
                    (r.parentNode as? Element)?.let {
                        removeChildElementFromTextNode(it, r)
                    }

                    break
                }
            }
        }
    }

    val gui: Gui
        get() {
            val elementGui = config?.documentElement?.getElementsByTagName("gui")
                ?.item(0) as Element?
            val gui = Gui()
            if (elementGui == null) {
                Log.e(
                    TAG,
                    "getGui: elementGui == null. Returning defaults."
                )
                return gui
            }

            gui.enabled = getAttributeOrDefault(elementGui, "enabled", gui.enabled)
            gui.useTLS = getAttributeOrDefault(elementGui, "tls", gui.useTLS)

            gui.address = getContentOrDefault(
                elementGui.getElementsByTagName("address").item(0),
                gui.address ?: ""
            )
            gui.user =
                getContentOrDefault(elementGui.getElementsByTagName("user").item(0), gui.user ?: "")
            gui.password =
                getContentOrDefault(elementGui.getElementsByTagName("password").item(0), "")
            gui.apiKey = getContentOrDefault(elementGui.getElementsByTagName("apikey").item(0), "")
            gui.theme =
                getContentOrDefault(elementGui.getElementsByTagName("theme").item(0), gui.theme)
            gui.insecureAdminAccess = getContentOrDefault(
                elementGui.getElementsByTagName("insecureAdminAccess").item(0),
                gui.insecureAdminAccess
            )
            gui.insecureAllowFrameLoading = getContentOrDefault(
                elementGui.getElementsByTagName("insecureAllowFrameLoading").item(0),
                gui.insecureAllowFrameLoading
            )
            gui.insecureSkipHostCheck = getContentOrDefault(
                elementGui.getElementsByTagName("insecureSkipHostCheck").item(0),
                gui.insecureSkipHostCheck
            )
            return gui
        }

    fun updateGui(gui: Gui) {
        val elementGui =
            config?.documentElement?.getElementsByTagName("gui")?.item(0) as Element?
        if (elementGui == null) {
            Log.e(TAG, "updateGui: elementGui == null")
            return
        }

        elementGui.setAttribute("enabled", gui.enabled.toString())
        elementGui.setAttribute("tls", gui.useTLS.toString())

        setConfigElement(elementGui, "address", gui.address ?: "")
        setConfigElement(elementGui, "user", gui.user ?: "")
        setConfigElement(elementGui, "password", gui.password ?: "")
        setConfigElement(elementGui, "apikey", gui.apiKey ?: "")
        setConfigElement(elementGui, "theme", gui.theme)
        setConfigElement(elementGui, "insecureAdminAccess", gui.insecureAdminAccess)
        setConfigElement(elementGui, "insecureAllowFrameLoading", gui.insecureAllowFrameLoading)
        setConfigElement(elementGui, "insecureSkipHostCheck", gui.insecureSkipHostCheck)
    }

    val options: Options
        get() {
            val elementOptions = config?.documentElement?.getElementsByTagName("options")
                ?.item(0) as Element?
            val options = Options()
            if (elementOptions == null) {
                Log.e(
                    TAG,
                    "getOptions: elementOptions == null. Returning defaults."
                )
                return options
            }

            // options.listenAddresses
            val listenAddressNodes = elementOptions.getElementsByTagName("listenAddress")
            val listenAddressesList: MutableList<String> =
                ArrayList<String>(listenAddressNodes.length)
            for (i in 0..<listenAddressNodes.length) {
                val addressNode = listenAddressNodes.item(i)
                val addressText = addressNode.textContent.trim { it <= ' ' }
                if (addressText.isNotEmpty()) {
                    listenAddressesList.add(addressText)
                }
            }
            options.listenAddresses = listenAddressesList

            // options.globalAnnounceServers
            options.globalAnnounceEnabled = getContentOrDefault(
                elementOptions.getElementsByTagName("globalAnnounceEnabled").item(0),
                options.globalAnnounceEnabled
            )
            options.localAnnounceEnabled = getContentOrDefault(
                elementOptions.getElementsByTagName("localAnnounceEnabled").item(0),
                options.localAnnounceEnabled
            )
            options.localAnnouncePort = getContentOrDefault(
                elementOptions.getElementsByTagName("localAnnouncePort").item(0),
                options.localAnnouncePort
            )
            options.localAnnounceMCAddr = getContentOrDefault(
                elementOptions.getElementsByTagName("localAnnounceMCAddr").item(0), ""
            )
            options.maxSendKbps = getContentOrDefault(
                elementOptions.getElementsByTagName("maxSendKbps").item(0),
                options.maxSendKbps
            )
            options.maxRecvKbps = getContentOrDefault(
                elementOptions.getElementsByTagName("maxRecvKbps").item(0),
                options.maxRecvKbps
            )
            options.reconnectionIntervalS = getContentOrDefault(
                elementOptions.getElementsByTagName("reconnectionIntervalS").item(0),
                options.reconnectionIntervalS
            )
            options.relaysEnabled = getContentOrDefault(
                elementOptions.getElementsByTagName("relaysEnabled").item(0),
                options.relaysEnabled
            )
            options.relayReconnectIntervalM = getContentOrDefault(
                elementOptions.getElementsByTagName("relayReconnectIntervalM").item(0),
                options.relayReconnectIntervalM
            )
            options.startBrowser = getContentOrDefault(
                elementOptions.getElementsByTagName("startBrowser").item(0),
                options.startBrowser
            )
            options.natEnabled = getContentOrDefault(
                elementOptions.getElementsByTagName("natEnabled").item(0),
                options.natEnabled
            )
            options.natLeaseMinutes = getContentOrDefault(
                elementOptions.getElementsByTagName("natLeaseMinutes").item(0),
                options.natLeaseMinutes
            )
            options.natRenewalMinutes = getContentOrDefault(
                elementOptions.getElementsByTagName("natRenewalMinutes").item(0),
                options.natRenewalMinutes
            )
            options.natTimeoutSeconds = getContentOrDefault(
                elementOptions.getElementsByTagName("natTimeoutSeconds").item(0),
                options.natTimeoutSeconds
            )
            options.urAccepted = getContentOrDefault(
                elementOptions.getElementsByTagName("urAccepted").item(0),
                options.urAccepted
            )
            options.urUniqueId =
                getContentOrDefault(elementOptions.getElementsByTagName("urUniqueId").item(0), "")
            options.urURL = getContentOrDefault(
                elementOptions.getElementsByTagName("urURL").item(0),
                options.urURL
            )
            options.urPostInsecurely = getContentOrDefault(
                elementOptions.getElementsByTagName("urPostInsecurely").item(0),
                options.urPostInsecurely
            )
            options.urInitialDelayS = getContentOrDefault(
                elementOptions.getElementsByTagName("urInitialDelayS").item(0),
                options.urInitialDelayS
            )
            options.autoUpgradeIntervalH = getContentOrDefault(
                elementOptions.getElementsByTagName("autoUpgradeIntervalH").item(0),
                options.autoUpgradeIntervalH
            )
            options.upgradeToPreReleases = getContentOrDefault(
                elementOptions.getElementsByTagName("upgradeToPreReleases").item(0),
                options.upgradeToPreReleases
            )
            options.keepTemporariesH = getContentOrDefault(
                elementOptions.getElementsByTagName("keepTemporariesH").item(0),
                options.keepTemporariesH
            )
            options.cacheIgnoredFiles = getContentOrDefault(
                elementOptions.getElementsByTagName("cacheIgnoredFiles").item(0),
                options.cacheIgnoredFiles
            )
            options.progressUpdateIntervalS = getContentOrDefault(
                elementOptions.getElementsByTagName("progressUpdateIntervalS").item(0),
                options.progressUpdateIntervalS
            )
            options.limitBandwidthInLan = getContentOrDefault(
                elementOptions.getElementsByTagName("limitBandwidthInLan").item(0),
                options.limitBandwidthInLan
            )
            options.releasesURL = getContentOrDefault(
                elementOptions.getElementsByTagName("releasesURL").item(0),
                options.releasesURL
            )
            // alwaysLocalNets
            options.overwriteRemoteDeviceNamesOnConnect = getContentOrDefault(
                elementOptions.getElementsByTagName("overwriteRemoteDeviceNamesOnConnect").item(0),
                options.overwriteRemoteDeviceNamesOnConnect
            )
            options.tempIndexMinBlocks = getContentOrDefault(
                elementOptions.getElementsByTagName("tempIndexMinBlocks").item(0),
                options.tempIndexMinBlocks
            )
            options.setLowPriority = getContentOrDefault(
                elementOptions.getElementsByTagName("setLowPriority").item(0),
                options.setLowPriority
            )
            // minHomeDiskFree
            options.maxFolderConcurrency = getContentOrDefault(
                elementOptions.getElementsByTagName("maxFolderConcurrency").item(0),
                options.maxFolderConcurrency
            )
            options.unackedNotificationID = getContentOrDefault(
                elementOptions.getElementsByTagName("unackedNotificationID").item(0),
                options.unackedNotificationID
            )
            options.crURL = getContentOrDefault(
                elementOptions.getElementsByTagName("crashReportingURL").item(0), options.crURL
            )
            options.crashReportingEnabled = getContentOrDefault(
                elementOptions.getElementsByTagName("crashReportingEnabled").item(0),
                options.crashReportingEnabled
            )
            options.stunKeepaliveStartS = getContentOrDefault(
                elementOptions.getElementsByTagName("stunKeepaliveStartS").item(0),
                options.stunKeepaliveStartS
            )
            options.stunKeepaliveMinS = getContentOrDefault(
                elementOptions.getElementsByTagName("stunKeepaliveMinS").item(0),
                options.stunKeepaliveMinS
            )
            options.stunServer = getContentOrDefault(
                elementOptions.getElementsByTagName("stunServer").item(0),
                options.stunServer
            )
            options.maxConcurrentIncomingRequestKiB = getContentOrDefault(
                elementOptions.getElementsByTagName("maxConcurrentIncomingRequestKiB").item(0),
                options.maxConcurrentIncomingRequestKiB
            )
            options.announceLANAddresses = getContentOrDefault(
                elementOptions.getElementsByTagName("announceLANAddresses").item(0),
                options.announceLANAddresses
            )
            options.sendFullIndexOnUpgrade = getContentOrDefault(
                elementOptions.getElementsByTagName("sendFullIndexOnUpgrade").item(0),
                options.sendFullIndexOnUpgrade
            )
            options.featureFlag = getContentOrDefault(
                elementOptions.getElementsByTagName("featureFlag").item(0),
                options.featureFlag
            )
            options.connectionLimitEnough = getContentOrDefault(
                elementOptions.getElementsByTagName("connectionLimitEnough").item(0),
                options.connectionLimitEnough
            )
            options.connectionLimitMax = getContentOrDefault(
                elementOptions.getElementsByTagName("connectionLimitMax").item(0),
                options.connectionLimitMax
            )
            return options
        }

    fun setDevicePause(deviceId: String?, paused: Boolean) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config?.documentElement?.childNodes
        for (i in 0..<(childNodes?.length ?: 0)) {
            val node = childNodes?.item(i)
            if (node?.nodeName == "device") {
                val r = node as Element
                if (getAttributeOrDefault(r, "id", "") == deviceId) {
                    setConfigElement(r, "paused", paused)
                    break
                }
            }
        }
    }

    /**
     * If an indented child element is removed, whitespace and line break will be left by
     * Element.removeChild().
     * See https://stackoverflow.com/questions/14255064/removechild-how-to-remove-indent-too
     */
    private fun removeChildElementFromTextNode(parentElement: Element, childElement: Element) {
        val prev = childElement.previousSibling
        if (prev != null && prev.nodeType == Node.TEXT_NODE && prev.nodeValue
                .trim { it <= ' ' }.isEmpty()
        ) {
            parentElement.removeChild(prev)
        }
        parentElement.removeChild(childElement)
    }

    private fun setConfigElement(parent: Element, tagName: String?, newValue: Boolean): Boolean {
        return setConfigElement(parent, tagName, newValue.toString())
    }

    private fun setConfigElement(parent: Element?, tagName: String?, textContent: String): Boolean {
        var element = parent?.getElementsByTagName(tagName)?.item(0)
        if (element == null) {
            element = config?.createElement(tagName)
            parent?.appendChild(element)
        }
        if (textContent != element?.textContent) {
            element?.textContent = textContent
            return true
        }
        return false
    }

    private fun setConfigElement(
        parent: Element,
        tagName: String?,
        textArray: List<String>
    ): Boolean {
        val existingNodes = parent.getElementsByTagName(tagName)
        val toRemove: MutableList<Node> = ArrayList<Node>(existingNodes.length)
        for (i in 0..<existingNodes.length) {
            val node = existingNodes.item(i)
            if (node.parentNode === parent) {
                toRemove.add(node)
            }
        }
        for (node in toRemove) {
            parent.removeChild(node)
        }
        for (text in textArray) {
            val newElement = config?.createElement(tagName)
            newElement?.textContent = text
            parent.appendChild(newElement)
        }
        return (toRemove.isNotEmpty() || textArray.isNotEmpty())
    }

    private val guiElement: Element?
        get() = config?.documentElement?.getElementsByTagName("gui")
            ?.item(0) as Element?

    /**
     * Set device model name as device name for Syncthing.
     * We need to iterate through XML nodes manually, as mConfig?.getDocumentElement() will also
     * return nested elements inside folder element. We have to check that we only rename the
     * device corresponding to the local device ID.
     * Returns if changes to the config have been made.
     */
    private fun changeLocalDeviceName(localDeviceID: String?): Boolean {
        val childNodes = config?.documentElement?.childNodes
        for (i in 0..<(childNodes?.length ?: 0)) {
            val node = childNodes?.item(i)
            if (node?.nodeName == "device") {
                if ((node as Element).getAttribute("id") == localDeviceID) {
                    Log.i(
                        TAG,
                        "changeLocalDeviceName: Rename device ID $localDeviceID to ${Build.MODEL}"
                    )
                    node.setAttribute("name", Build.MODEL)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Adds a new folder pointing to the app-specific "Syncthing Camera"
     * directory if it hasn't been added to the config yet.
     * Returns if changes to the config have been made.
     */
    private fun addSyncthingCameraFolder(): Boolean {
        // LogV("addSyncthingCameraFolder: Examining config if folder already exists ...");
        val nodeFolders = config?.documentElement?.getElementsByTagName("folder")
        var folderAlreadyPresentInConfig = false
        for (i in 0..<(nodeFolders?.length ?: 0)) {
            val r = nodeFolders?.item(i) as Element
            val folderId = getAttributeOrDefault(r, "id", "")
            if (!folderId.isNullOrEmpty() && folderId == Constants.syncthingCameraFolderId) {
                folderAlreadyPresentInConfig = true
                break
            }
        }
        if (folderAlreadyPresentInConfig) {
            // LogV("addSyncthingCameraFolder: Folder [" + Constants.syncthingCameraFolderId + "] already present in config.");
            return false
        }

        // Get app specific directory, e.g. "/storage/emulated/0/Android/media/[PACKAGE_NAME]/Pictures".
        val storageDir = FileUtils.getExternalFilesDir(
            context,
            ExternalStorageDirType.INT_MEDIA,
            Environment.DIRECTORY_PICTURES
        )
        if (storageDir == null) {
            Log.e(TAG, "addSyncthingCameraFolder: storageDir == null")
            return false
        }

        // Prepare folder element.
        val folder = Folder()
        folder.minDiskFree = MinDiskFree()
        folder.id = Constants.syncthingCameraFolderId
        folder.label = context.getString(R.string.default_syncthing_camera_folder_label)
        folder.path = storageDir.absolutePath

        // Add versioning.
        folder.versioning = Versioning()
        folder.versioning?.type = "trashcan"
        folder.versioning?.params?.put("cleanoutDays", "14")
        folder.versioning?.cleanupIntervalS = 3600
        folder.versioning?.fsPath = ""
        folder.versioning?.fsType = "basic"

        // Add folder to config.
        LogV("addSyncthingCameraFolder: Adding folder to config [" + folder.path + "]")
        addFolder(folder)
        return true
    }

    /**
     * Writes the parsed config back to disk through a temporary file that is renamed over
     * the original, so a failed write can never truncate a working config.
     *
     * @return true only when the new content is durable and in place. A caller that
     * acknowledges the change to the user must check this instead of assuming the write
     * landed.
     */
    fun saveChanges(): Boolean {
        if (!configFile.canWrite()) {
            Log.w(
                TAG,
                "Failed to save updated config. Cannot change the owner of the config file."
            )
            return false
        }

        Log.i(TAG, "Saving config file")
        /**
         * A private temporary name per write: [ConfigXml] instances that are not shared
         * through [ConfigRouter] (the service's own config, key generation) would
         * otherwise write through the same "config.xml.tmp" and rename a half-written
         * document over each other.
         */
        val mConfigTempFile = try {
            File.createTempFile("config", ".tmp", configFile.parentFile)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create a temporary config file", e)
            return false
        }
        try {
            // Write XML header.
            FileOutputStream(mConfigTempFile).use { fileOutputStream ->
                fileOutputStream.write(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray(
                        Charset.forName("UTF-8")
                    )
                )

                // Prepare Object-to-XML transform.
                val transformerFactory = TransformerFactory.newInstance()
                val transformer = transformerFactory.newTransformer()
                transformer.setOutputProperty(OutputKeys.METHOD, "xml")
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")

                // Output XML body.
                val byteArrayOutputStream = ByteArrayOutputStream()
                val streamResult = StreamResult(
                    OutputStreamWriter(
                        byteArrayOutputStream,
                        Charset.forName("UTF-8")
                    )
                )
                transformer.transform(DOMSource(config), streamResult)
                fileOutputStream.write(byteArrayOutputStream.toByteArray())
                fileOutputStream.fd.sync()
            }

        } catch (e: Exception) {
            when (e) {
                is IOException -> Log.w(TAG, "Failed to save temporary config file, IOException", e)
                is UnsupportedEncodingException -> Log.w(
                    TAG,
                    "Failed to save temporary config file, UnsupportedEncodingException",
                    e
                )

                is FileNotFoundException -> Log.w(
                    TAG,
                    "Failed to save temporary config file, FileNotFoundException",
                    e
                )

                is TransformerException -> Log.w(
                    TAG,
                    "Failed to transform object to xml and save temporary config file",
                    e
                )

                else -> {
                    Log.w(TAG, "Failed to save temporary config file", e)
                }
            }
            mConfigTempFile.delete()
            return false
        }
        return try {
            if (!mConfigTempFile.renameTo(configFile)) {
                Log.w(TAG, "Failed to rename temporary config file to original file")
                mConfigTempFile.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename temporary config file to original file", e)
            mConfigTempFile.delete()
            false
        }
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "ConfigXml"
    }
}
