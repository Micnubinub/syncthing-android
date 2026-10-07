package com.micnubinub.syncthing.activities

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.ui.NavDisplay
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RunConditionBus
import com.micnubinub.syncthing.service.RunConditionEvent
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.service.SyncthingService.OnServiceStateChangeListener
import com.micnubinub.syncthing.service.SyncthingServiceBinder
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.screens.FolderPickerActivityScreen
import com.micnubinub.syncthing.ui.screens.ShareActivityScreen
import com.micnubinub.syncthing.ui.viewModels.CopyResult
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.ShareActivityAction
import com.micnubinub.syncthing.ui.viewModels.ShareActivityViewModel
import com.micnubinub.syncthing.util.ConfigRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Screens of the share flow. The share screen hosts a folder picker destination for choosing
 * the sub directory the incoming files are copied into.
 */
@Serializable
private sealed interface ShareRoute : NavKey {
    @Serializable
    data object Share : ShareRoute

    @Serializable
    data class FolderPicker(
        val initialDirectory: String? = null,
        val rootDirectory: String? = null,
    ) : ShareRoute
}

/**
 * Shares incoming files to syncthing folders.
 * 
 * 
 * [.getDisplayNameForUri] and [.getDisplayNameFromContentResolver] are taken from
 * ownCloud Android {@see https://github.com/owncloud/android/blob/master/owncloudApp/src/main/java/com/owncloud/android/utils/UriUtils.java}
 */
class ShareActivity : SyncthingActivity(), OnServiceStateChangeListener {
    private val viewModel by viewModels<ShareActivityViewModel>()

    /**
     * True once [onStart] has explicitly started the service, i.e. once it is a started
     * service with a [com.micnubinub.syncthing.service.RunConditionMonitor] listening on
     * [RunConditionBus].
     */
    private var syncthingServiceStarted = false

    @Inject
    lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        viewModel.setPreferences(preferences)
        viewModel.setConfigRouter(ConfigRouter(this))
        viewModel.setContentResolver(contentResolver)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val intent = intent
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { parseShareIntent(intent) }
            if (files.isEmpty()) {
                Toast.makeText(
                    this@ShareActivity,
                    getString(R.string.nothing_share),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            viewModel.setInitialFiles(files)

            setContent {
                ApplicationTheme {
                    val backStack = rememberSerializable(
                        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer()),
                    ) { NavBackStack<ShareRoute>(ShareRoute.Share) }
                    val pendingResults = remember { mutableMapOf<ShareRoute, (Any?) -> Unit>() }

                    fun navigateTo(route: ShareRoute, onResult: ((Any?) -> Unit)? = null) {
                        if (onResult != null) {
                            pendingResults[route] = onResult
                        }
                        backStack.add(route)
                    }

                    fun navigateBack() {
                        if (backStack.size > 1) {
                            pendingResults.remove(backStack.removeAt(backStack.lastIndex))
                        } else {
                            finish()
                        }
                    }

                    fun deliverResult(route: ShareRoute, result: Any?) {
                        val callback = pendingResults.remove(route)
                        while (backStack.isNotEmpty()) {
                            if (backStack.removeAt(backStack.lastIndex) == route) {
                                break
                            }
                        }
                        if (backStack.isEmpty()) {
                            backStack.add(ShareRoute.Share)
                        }
                        callback?.invoke(result)
                    }

                    NavDisplay(
                        backStack = backStack,
                        onBack = { navigateBack() },
                        entryProvider = entryProvider {
                            entry<ShareRoute.Share> {
                                ShareActivityScreen(
                                    viewModel = viewModel,
                                    onCopyFinished = ::onCopyFinished,
                                    onWelcomeWizardRequired = ::onWelcomeWizardRequired,
                                    onBrowseSubDirectory = { initialDirectory, rootDirectory ->
                                        navigateTo(
                                            ShareRoute.FolderPicker(initialDirectory, rootDirectory)
                                        ) { result ->
                                            viewModel.onAction(
                                                ShareActivityAction.SubDirectoryPicked(result as? String)
                                            )
                                        }
                                    },
                                    onCancel = ::finish
                                )
                            }
                            entry<ShareRoute.FolderPicker> { route ->
                                val activity = LocalContext.current
                                val pickerViewModel = viewModel<FolderPickerActivityViewModel>()

                                LaunchedEffect(route) {
                                    pickerViewModel.setExternalFilesDirs(
                                        activity.getExternalFilesDirs(
                                            null
                                        )
                                    )
                                    pickerViewModel.setExternalFilesDir(
                                        activity.getExternalFilesDir(
                                            null
                                        )
                                    )
                                    pickerViewModel.setInitialDirectory(route.initialDirectory)
                                    pickerViewModel.setRootDirectory(route.rootDirectory)
                                    pickerViewModel.onAction(FolderPickerActivityAction.LoadData)
                                }

                                FolderPickerActivityScreen(
                                    viewModel = pickerViewModel,
                                    onSelectDirectory = { path -> deliverResult(route, path) },
                                    onNavigateUp = { navigateBack() },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
        super.onServiceConnected(componentName, iBinder)
        val syncthingService = (iBinder as SyncthingServiceBinder).service
        viewModel.setService(syncthingService)
        syncthingService.registerOnServiceStateChangeListener(this)
    }

    override fun onStart() {
        super.onStart()
        // The service is only ever bound (see [SyncthingActivity.onResume]). A service
        // created by BIND_AUTO_CREATE never receives onStartCommand, so no
        // RunConditionMonitor is instantiated and the immediate-sync trigger in
        // [afterCopyFilesTask] would have no listener at all. Start it explicitly while
        // this activity is visible, so the trigger is observed.
        startService(Intent(this, SyncthingService::class.java))
        // Only now is the service known to be a started service that will evaluate run
        // conditions, so only now may the sync trigger be considered deliverable.
        syncthingServiceStarted = true
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        viewModel.onServiceStateChange(currentState)
    }

    override fun onPause() {
        viewModel.saveSelectedFolder()
        super.onPause()
    }

    override fun onDestroy() {
        service?.unregisterOnServiceStateChangeListener(this)
        super.onDestroy()
    }

    private fun onWelcomeWizardRequired() {
        Toast.makeText(
            this,
            getString(R.string.complete_welcome_wizard_first),
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun onCopyFinished(copyResult: CopyResult) {
        if (copyResult.isError) {
            Toast.makeText(
                this, getString(R.string.copy_exception),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        } else {
            Toast.makeText(
                this, if (copyResult.ignored > 0) getResources().getQuantityString(
                    R.plurals.copy_success_partially, copyResult.copied,
                    copyResult.copied, copyResult.folderLabel, copyResult.ignored
                ) else getResources().getQuantityString(
                    R.plurals.copy_success, copyResult.copied,
                    copyResult.copied, copyResult.folderLabel
                ),
                Toast.LENGTH_LONG
            ).show()
            afterCopyFilesTask()
        }
    }

    private fun afterCopyFilesTask() {
        if (preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
            if (syncthingServiceStarted) {
                Log.v(TAG, "prefRunOnTimeSchedule=true, notifying RunConditionMonitor")
                RunConditionBus.tryEmit(
                    RunConditionEvent.SyncTriggerFired(true)
                )
            } else {
                Log.w(
                    TAG,
                    "prefRunOnTimeSchedule=true, but SyncthingService was never started, " +
                            "skipping immediate sync trigger"
                )
            }
        }

        finish()
    }

    /**
     * Extracts the URIs to share from the intent and maps each of them to a
     * display file name.
     */
    private fun parseShareIntent(intent: Intent): Map<Uri, String?> {
        var extrasToCopy: ArrayList<Uri>? = ArrayList<Uri>()
        intent.action?.let { action ->
            if (action == Intent.ACTION_SEND) {
                try {
                    val uri =
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        }

                    uri?.let {
                        extrasToCopy?.add(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Intent.ACTION_SEND: Ignored malformed intent.")
                }
            } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                try {
                    extrasToCopy =
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                            @Suppress("DEPRECATION")
                            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        } else {
                            intent.getParcelableArrayListExtra<Uri>(
                                Intent.EXTRA_STREAM,
                                Uri::class.java
                            )
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Intent.ACTION_SEND_MULTIPLE: Ignored malformed intent.")
                }
            }
        }

        if (extrasToCopy.isNullOrEmpty()) {
            return emptyMap()
        }

        val files: MutableMap<Uri, String?> = LinkedHashMap()
        for (sourceUri in extrasToCopy) {
            files[sourceUri] = getDisplayNameForUri(sourceUri) ?: generateDisplayName()
        }
        return files
    }

    /**
     * Generate file name for new file.
     */
    private fun generateDisplayName(): String {
        val date = Date(System.currentTimeMillis())
        val df = DateFormat.getDateTimeInstance()
        return String.format(
            getResources().getString(R.string.file_name_template),
            df.format(date)
        )
    }

    /**
     * Get file name from uri.
     */
    private fun getDisplayNameForUri(uri: Uri): String? {
        val displayName = if (ContentResolver.SCHEME_CONTENT != uri.scheme) {
            uri.lastPathSegment
        } else {
            var tmpDisplayName = getDisplayNameFromContentResolver(uri)
                ?: uri.lastPathSegment?.replace("\\s".toRegex(), "")

            // Add best possible extension
            val index = tmpDisplayName?.lastIndexOf(".") ?: -1
            if (index == -1 || MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(tmpDisplayName?.substring(index + 1)) == null
            ) {
                val mimeType = this.contentResolver.getType(uri)
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (extension != null && tmpDisplayName != null) {
                    tmpDisplayName = "$tmpDisplayName.$extension"
                }
            }
            tmpDisplayName
        }

        // Replace path separator characters to avoid inconsistent paths, then strip
        // any remaining directory components so a crafted display name cannot
        // escape the target folder (path traversal). Returns null for unusable
        // names so the caller falls back to a generated name.
        return displayName
            ?.replace("[/\\\\]".toRegex(), "-")
            ?.let { File(it).name }
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    /**
     * Get file name from content uri (content://).
     */
    private fun getDisplayNameFromContentResolver(uri: Uri): String? {
        var displayName: String? = null
        contentResolver.getType(uri)?.let { mimeType ->
            val displayNameColumn = if (mimeType.startsWith("image/")) {
                MediaStore.Images.ImageColumns.DISPLAY_NAME
            } else if (mimeType.startsWith("video/")) {
                MediaStore.Video.VideoColumns.DISPLAY_NAME
            } else if (mimeType.startsWith("audio/")) {
                MediaStore.Audio.AudioColumns.DISPLAY_NAME
            } else {
                MediaStore.Files.FileColumns.DISPLAY_NAME
            }

            try {
                contentResolver.query(
                    uri,
                    arrayOf<String>(displayNameColumn),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName =
                            cursor.getString(cursor.getColumnIndexOrThrow(displayNameColumn))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not retrieve display name for $uri")
                // nothing else, displayName keeps null
            }
        }
        return displayName
    }

    companion object {
        const val PREF_FOLDER_SAVED_SUBDIRECTORY: String = "saved_sub_directory_"
    }
}
