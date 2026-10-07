package com.micnubinub.syncthing.settings

import android.content.ClipData
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.navigation3.runtime.EntryProviderScope
import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.common.base.Splitter
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.navigation.LocalServiceUpdateTick
import com.micnubinub.syncthing.navigation.LocalSyncthingService
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.FileUtils.deleteDirectoryContents
import com.micnubinub.syncthing.util.FileUtils.isWithinDirectory
import com.micnubinub.syncthing.util.LocalActivityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.MapPreferences
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceFlow
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import me.zhanghai.compose.preference.rememberPreferenceState
import java.io.File
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SettingsSyncthingOptionsScreen"

fun EntryProviderScope<SettingsRoute>.settingsSyncthingOptionsEntry() {
    entry<SettingsRoute.SyncthingOptions> {
        SettingsSyncthingOptionsScreen()
    }
}

private object Keys {
    const val DEVICE_NAME = "device_name"
    const val API_KEY = "api_key"
    const val USAGE_REPORTING = "usage_reporting"

    const val LISTEN_ADDRESSES = "listen_addresses"
    const val INCOMING_RATE_LIMIT = "incoming_rate_limit"
    const val OUTGOING_RATE_LIMIT = "outgoing_rate_limit"
    const val NAT_TRAVERSAL = "nat_traversal"
    const val LOCAL_DISCOVERY = "local_discovery"
    const val GLOBAL_DISCOVERY = "global_discovery"
    const val RELAYING = "relaying"
    const val GLOBAL_SERVERS = "global_servers"

    const val WEB_GUI_PORT = "web_gui_port"
    const val WEB_GUI_REMOTE_ACCESS = "web_gui_remote_access"
    const val WEB_GUI_USERNAME = "web_gui_username"
    const val WEB_GUI_PASSWORD = Constants.PREF_WEBUI_PASSWORD

    const val CRASH_REPORTING = "crash_reporting"
}

private const val BIND_ALL = "0.0.0.0"
private const val BIND_LOCALHOST = "127.0.0.1"


@OptIn(FlowPreview::class)
@Composable
fun SettingsSyncthingOptionsScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val stService = LocalSyncthingService.current
    val stServiceTick = LocalServiceUpdateTick.current
    val scope = LocalActivityScope.current
    val navigator = LocalSettingsNavigator.current

    val sharedPrefWebGuiPassword = rememberPreferenceState(Constants.PREF_WEBUI_PASSWORD, "")

    val isStServiceActive by remember(stService, stServiceTick) {
        derivedStateOf {
            stService?.currentState == SyncthingService.State.ACTIVE && stService.api?.isConfigLoaded == true
        }
    }
    val apiPrefFlow: MutableStateFlow<Preferences> = remember {
        MutableStateFlow(stService?.api?.preferences ?: MapPreferences())
    }

    LaunchedEffect(stService?.api, isStServiceActive) {
        stService?.api?.let { currentApi ->
            if (isStServiceActive) {
                apiPrefFlow.value = currentApi.preferences
                apiPrefFlow
                    .drop(1)
                    .debounce(0.5.seconds)
                    .collect {
                        if (currentApi.applySyncthingOptions(it) != RestApi.ConfigSaveResult.SAVED) {
                            // The core never saw the edit, so put the last known good
                            // values back on screen instead of leaving them showing.
                            apiPrefFlow.value = currentApi.preferences
                            Toast.makeText(
                                context,
                                R.string.config_save_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        }
    }


    ProvidePreferenceFlow(apiPrefFlow) {
        val deviceName = rememberPreferenceState(Keys.DEVICE_NAME, "")
        val apiKey by rememberPreferenceState(Keys.API_KEY, "")
        val usageReporting = rememberPreferenceState(Keys.USAGE_REPORTING, false)

        val listenAddresses = rememberPreferenceState(Keys.LISTEN_ADDRESSES, "default")
        val incomingRateLimit = rememberPreferenceState(Keys.INCOMING_RATE_LIMIT, 0)
        val outgoingRateLimit = rememberPreferenceState(Keys.OUTGOING_RATE_LIMIT, 0)
        val natTraversal = rememberPreferenceState(Keys.NAT_TRAVERSAL, false)
        val localDiscovery = rememberPreferenceState(Keys.LOCAL_DISCOVERY, false)
        val globalDiscovery = rememberPreferenceState(Keys.GLOBAL_DISCOVERY, false)
        val relaying = rememberPreferenceState(Keys.RELAYING, false)
        val globalServers = rememberPreferenceState(Keys.GLOBAL_SERVERS, "default")

        val webGuiPort =
            rememberPreferenceState(Keys.WEB_GUI_PORT, Constants.DEFAULT_WEBGUI_TCP_PORT)
        val webGuiRemoteAccess = rememberPreferenceState(Keys.WEB_GUI_REMOTE_ACCESS, false)
        val webGuiUsername = rememberPreferenceState(Keys.WEB_GUI_USERNAME, "syncthing")
        val webGuiPassword =
            rememberPreferenceState(Keys.WEB_GUI_PASSWORD, sharedPrefWebGuiPassword.value)

        val crashReporting = rememberPreferenceState(Keys.CRASH_REPORTING, true)


        SettingsScaffold(
            title = stringResource(R.string.category_syncthing_options),
            description = stringResource(R.string.category_syncthing_options_summary),
        ) {
            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.category_general)) },
                )
            }
            item {
                TextFieldPreference(
                    title = { Text(stringResource(R.string.device_name)) },
                    summary = { Text(deviceName.value) },
                    state = deviceName,
                    textToValue = { it },
                    enabled = isStServiceActive,
                )
            }
            item {
                val apiKeyTitle = stringResource(R.string.syncthing_api_key)
                Preference(
                    title = { Text(apiKeyTitle) },
                    summary = { Text(apiKey) },
                    modifier = Modifier.semantics(true) {
                        isSensitiveData = true
                        password()
                    },
                    onClick = {
                        val clipData = ClipData.newPlainText(apiKeyTitle, apiKey).toClipEntry()
                        scope.launch {
                            clipboard.setClipEntry(clipData)
                            // Android 13+ shows a system confirmation automatically
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(
                                    context,
                                    R.string.api_key_copied_to_clipboard,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.usage_reporting)) },
                    state = usageReporting,
                    enabled = isStServiceActive,
                )
            }

            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.category_connections)) },
                )
            }
            item {
                TextFieldPreference(
                    title = { Text(stringResource(R.string.listen_address)) },
                    summary = { Text(listenAddresses.value) },
                    state = listenAddresses,
                    textToValue = { it },
                    enabled = isStServiceActive,
                )
            }
            item {
                val rateLimitError =
                    stringResource(R.string.invalid_integer_value, 0, Int.MAX_VALUE)
                TextFieldPreference(
                    title = { Text(stringResource(R.string.max_recv_kbps)) },
                    summary = { Text(incomingRateLimit.value.toString()) },
                    state = incomingRateLimit,
                    textToValue = {
                        val newVal = it.toIntOrNull()
                        if (newVal == null) {
                            Toast.makeText(context, rateLimitError, Toast.LENGTH_LONG).show()
                            null
                        } else {
                            newVal
                        }
                    },
                    enabled = isStServiceActive,
                )
            }
            item {
                val rateLimitError =
                    stringResource(R.string.invalid_integer_value, 0, Int.MAX_VALUE)
                TextFieldPreference(
                    title = { Text(stringResource(R.string.max_send_kbps)) },
                    summary = { Text(outgoingRateLimit.value.toString()) },
                    state = outgoingRateLimit,
                    textToValue = {
                        val newVal = it.toIntOrNull()
                        if (newVal == null) {
                            Toast.makeText(context, rateLimitError, Toast.LENGTH_LONG).show()
                            null
                        } else {
                            newVal
                        }
                    },
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_nat_traversal)) },
                    state = natTraversal,
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.local_announce_enabled)) },
                    state = localDiscovery,
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.global_announce_enabled)) },
                    state = globalDiscovery,
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.enable_relaying)) },
                    state = relaying,
                    enabled = isStServiceActive,
                )
            }
            item {
                TextFieldPreference(
                    title = { Text(stringResource(R.string.global_announce_server)) },
                    summary = { Text(globalServers.value) },
                    state = globalServers,
                    textToValue = { it },
                    enabled = isStServiceActive,
                )
            }

            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.web_gui_title)) },
                )
            }
            item {
                val portError = stringResource(R.string.invalid_port_number, 1024, 65535)
                TextFieldPreference(
                    title = { Text(stringResource(R.string.webui_tcp_port_title)) },
                    summary = { Text(webGuiPort.value.toString()) },
                    state = webGuiPort,
                    textToValue = {
                        val newValue = it.toIntOrNull()
                        if (newValue == null || newValue !in 1024..65535) {
                            Toast.makeText(context, portError, Toast.LENGTH_LONG).show()
                            null
                        } else {
                            newValue
                        }
                    },
                    enabled = isStServiceActive,
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.webui_remote_access_title)) },
                    summary = { Text(stringResource(R.string.webui_remote_access_summary)) },
                    state = webGuiRemoteAccess,
                    enabled = isStServiceActive,
                )
            }
            item {
                TextFieldPreference(
                    title = { Text(stringResource(R.string.webui_username_title)) },
                    summary = { Text(webGuiUsername.value) },
                    state = webGuiUsername,
                    textToValue = { it },
                    enabled = isStServiceActive,
                )
            }
            item {
                TextFieldPreference(
                    title = { Text(stringResource(R.string.webui_password_title)) },
                    value = webGuiPassword.value,
                    onValueChange = {
                        webGuiPassword.value = it
                        sharedPrefWebGuiPassword.value = it
                    },
                    textToValue = { it },
                    enabled = isStServiceActive,
                )
            }

            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.category_advanced)) },
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.crash_reporting)) },
                    state = crashReporting,
                    enabled = isStServiceActive,
                )
            }
            item {
                Preference(
                    title = { Text(stringResource(R.string.webui_custom_cert_title)) },
                    summary = { Text(stringResource(R.string.webui_custom_cert_summary)) },
                    onClick = { navigator.navigateTo(SettingsRoute.CustomCertificate) },
                    // The custom certificate only matters when the GUI is served over HTTPS.
                    enabled = stService != null,
                )
            }
            item {
                ClearStVersionPreference(
                    stService = stService,
                    enabled = isStServiceActive,
                )
            }
            item {
                SupportBundlePreference(
                    stService = stService,
                    enabled = isStServiceActive,
                )
            }
            item {
                UndoIgnoredDevicesFoldersPreference(
                    stService = stService,
                    enabled = isStServiceActive,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearStVersionPreference(
    stService: SyncthingService?,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val scope = LocalActivityScope.current
    var showAlert by rememberSaveable { mutableStateOf(false) }

    Preference(
        title = { Text(stringResource(R.string.clear_stversions_title)) },
        summary = { Text(stringResource(R.string.clear_stversions_summary)) },
        onClick = { showAlert = true },
        enabled = enabled,
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.clear_stversions_title)) },
            text = { Text(stringResource(R.string.clear_stversions_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlert = false
                        stService?.api?.let { api ->
                            scope.launch(Dispatchers.IO) {
                                val folders = api.folders
                                val cleared = clearStVersions(folders)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (cleared) R.string.clear_stversions_done
                                        else R.string.clear_stversions_failed,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UndoIgnoredDevicesFoldersPreference(
    stService: SyncthingService?,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val scope = LocalActivityScope.current
    var showAlert by rememberSaveable { mutableStateOf(false) }

    Preference(
        title = { Text(stringResource(R.string.undo_ignored_devices_folders_title)) },
        onClick = { showAlert = true },
        enabled = enabled,
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.undo_ignored_devices_folders_title)) },
            text = { Text(stringResource(R.string.undo_ignored_devices_folders_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlert = false
                        stService?.api?.let { api ->
                            scope.launch(Dispatchers.IO) {
                                val undone = api.undoIgnoredDevicesAndFolders()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (undone == RestApi.ConfigSaveResult.SAVED) {
                                            R.string.undo_ignored_devices_folders_done
                                        } else {
                                            R.string.undo_ignored_devices_folders_failed
                                        },
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}


@Composable
private fun SupportBundlePreference(
    stService: SyncthingService?,
    enabled: Boolean,
) {
    val scope = LocalActivityScope.current
    var supportBundleState by remember { mutableStateOf(SupportBundleDownloadState.INIT) }
    var supportBundleFileName by remember { mutableStateOf("") }

    Preference(
        title = { Text(stringResource(R.string.download_support_bundle_title)) },
        summary = {
            when (supportBundleState) {
                SupportBundleDownloadState.INIT -> {}
                SupportBundleDownloadState.DOWNLOADING -> {
                    Text(stringResource(R.string.download_support_bundle_in_progress))
                }

                SupportBundleDownloadState.SUCCESS -> {
                    Text(
                        stringResource(
                            R.string.download_support_bundle_succeeded,
                            supportBundleFileName
                        )
                    )
                }

                SupportBundleDownloadState.FAILED -> {
                    Text(stringResource(R.string.download_support_bundle_failed))
                }
            }
        },
        onClick = {
            stService?.api?.let { api ->
                supportBundleState = SupportBundleDownloadState.DOWNLOADING
                scope.launch(Dispatchers.IO) {
                    val deviceName = api.localDevice?.displayName ?: "untitled"
                    val safeName = deviceName.filter { it != '/' && it != '\\' }.replace("..", "_")
                    val downloadDir = FileUtils.externalStorageDownloadsDirectory
                    val targetFile = File(downloadDir, "syncthing-support-bundle_$safeName.zip")
                    withContext(Dispatchers.Main) {
                        supportBundleFileName = targetFile.absolutePath
                    }
                    api.downloadSupportBundle(targetFile) { success ->
                        scope.launch {
                            supportBundleState = if (success == true)
                                SupportBundleDownloadState.SUCCESS
                            else
                                SupportBundleDownloadState.FAILED
                        }
                    }
                }
            }
        },
        enabled = enabled && supportBundleState != SupportBundleDownloadState.DOWNLOADING,
    )
}

private enum class SupportBundleDownloadState {
    INIT, DOWNLOADING, SUCCESS, FAILED
}

private val RestApi.preferences: Preferences
    get() {
        if (!isConfigLoaded) return MapPreferences()

        val values = mutableMapOf<String, Any>()
        values[Keys.DEVICE_NAME] = localDevice?.name.orEmpty()
        values[Keys.API_KEY] = this.apiKey.orEmpty()
        values[Keys.USAGE_REPORTING] = isUsageReportingAccepted

        options?.let { options ->
            values[Keys.LISTEN_ADDRESSES] = options.listenAddresses?.joinToString().orEmpty()
            values[Keys.INCOMING_RATE_LIMIT] = options.maxRecvKbps
            values[Keys.OUTGOING_RATE_LIMIT] = options.maxSendKbps
            values[Keys.NAT_TRAVERSAL] = options.natEnabled
            values[Keys.LOCAL_DISCOVERY] = options.localAnnounceEnabled
            values[Keys.GLOBAL_DISCOVERY] = options.globalAnnounceEnabled
            values[Keys.RELAYING] = options.relaysEnabled
            values[Keys.GLOBAL_SERVERS] = options.globalAnnounceServers?.joinToString().orEmpty()
            values[Keys.CRASH_REPORTING] = options.crashReportingEnabled
        }

        gui?.let { gui ->
            values[Keys.WEB_GUI_PORT] =
                gui.bindPort.toIntOrNull() ?: Constants.DEFAULT_WEBGUI_TCP_PORT
            values[Keys.WEB_GUI_REMOTE_ACCESS] = gui.bindAddress != BIND_LOCALHOST
            values[Keys.WEB_GUI_USERNAME] = gui.user.orEmpty()
            // use password saved in shared preference only
            // values[Keys.WEB_GUI_PASSWORD] = gui.password
        }

        return MapPreferences(values)
    }

/**
 * Applies [value] to the Syncthing options/GUI and the local device name in one config write,
 * and reports what the core made of it.
 */
private suspend fun RestApi.applySyncthingOptions(value: Preferences): RestApi.ConfigSaveResult {
    val splitter = Splitter.on(",").trimResults().omitEmptyStrings()
    val valueMap = value.asMap()

    // update calls that change internal state of RestApi
    (valueMap[Keys.USAGE_REPORTING] as? Boolean)?.let { setUsageReporting(it) }

    // assignments to options and gui
    val options = options
    val gui = gui
    for ((key, mapValue) in valueMap) {
        when (key) {
            Keys.LISTEN_ADDRESSES -> {
                val addresses = (mapValue as? String) ?: continue
                options?.listenAddresses = splitter.splitToList(addresses)
            }

            Keys.INCOMING_RATE_LIMIT -> {
                options?.maxRecvKbps = (mapValue as? Int) ?: continue
            }

            Keys.OUTGOING_RATE_LIMIT -> {
                options?.maxSendKbps = (mapValue as? Int) ?: continue
            }

            Keys.NAT_TRAVERSAL -> {
                options?.natEnabled = (mapValue as? Boolean) ?: continue
            }

            Keys.LOCAL_DISCOVERY -> {
                options?.localAnnounceEnabled = (mapValue as? Boolean) ?: continue
            }

            Keys.GLOBAL_DISCOVERY -> {
                options?.globalAnnounceEnabled = (mapValue as? Boolean) ?: continue
            }

            Keys.RELAYING -> {
                options?.relaysEnabled = (mapValue as? Boolean) ?: continue
            }

            Keys.GLOBAL_SERVERS -> {
                val servers = (mapValue as? String) ?: continue
                options?.globalAnnounceServers = splitter.splitToList(servers)
            }

            Keys.CRASH_REPORTING -> {
                options?.crashReportingEnabled = (mapValue as? Boolean) ?: continue
            }

            Keys.WEB_GUI_REMOTE_ACCESS -> {
                val boolVal = mapValue as? Boolean ?: continue
                val address = if (boolVal) BIND_ALL else BIND_LOCALHOST
                val port =
                    (valueMap[Keys.WEB_GUI_PORT] as? Int) ?: Constants.DEFAULT_WEBGUI_TCP_PORT
                gui?.address = "$address:$port"
            }

            Keys.WEB_GUI_PORT -> {/* Ignore here, handled in WEB_GUI_REMOTE_ACCESS */
            }

            Keys.WEB_GUI_USERNAME -> {
                gui?.user = (mapValue as? String) ?: continue
            }

            Keys.WEB_GUI_PASSWORD -> {
                val password = (mapValue as? String) ?: continue
                val hashed =
                    BCrypt.withDefaults().hashToString(4, password.toCharArray())
                gui?.password = hashed
            }
        }
    }
    // One config write has to carry the settings edits and the device rename, otherwise
    // the rename can be posted without the settings it was part of.
    val device = localDevice
    return editSettings(
        gui,
        options,
        device?.also {
            it.name = (valueMap[Keys.DEVICE_NAME] as? String) ?: it.name
        }
    )
}

private fun clearStVersions(folders: List<Folder>): Boolean {
    var allDeleted = true
    for (folder in folders) {
        val folderRoot = File(folder.path.orEmpty())
        val dir = File(folderRoot, Constants.FOLDER_NAME_STVERSIONS)
        if (!dir.exists() || !dir.isDirectory) {
            continue
        }
        // .stversions itself may be a symlink; clearing "through" it would wipe
        // whatever it points at, so refuse rather than follow it.
        if (!isWithinDirectory(folderRoot, dir)) {
            Log.w(
                TAG,
                "clearStVersions: '$dir' is not inside '${folderRoot.absolutePath}', skipping"
            )
            allDeleted = false
            continue
        }
        Log.d(TAG, "Delete contents of: $dir")
        allDeleted = deleteDirectoryContents(dir) && allDeleted
    }
    return allDeleted
}
