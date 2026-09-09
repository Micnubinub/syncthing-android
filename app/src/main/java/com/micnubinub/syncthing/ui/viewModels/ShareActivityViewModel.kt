package com.micnubinub.syncthing.ui.viewModels

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.io.Files
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml.OpenConfigException
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

data class CopyResult(
    val isError: Boolean,
    val copied: Int,
    val ignored: Int,
    val folderLabel: String
)

data class ShareActivityState(
    val isLoading: Boolean = false,
    val welcomeWizardRequired: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val selectedFolderIndex: Int = 0,
    val subDirectory: String = "",
    val fileName: String = "",
    val isFileNameEditable: Boolean = true,
    val isSharing: Boolean = false,
    val copyResult: CopyResult? = null
)

sealed interface ShareActivityAction {
    data object LoadData : ShareActivityAction
    data class SelectFolder(val index: Int) : ShareActivityAction
    data class UpdateFileName(val name: String) : ShareActivityAction
    data class SubDirectoryPicked(val directory: String?) : ShareActivityAction
    data object StartCopy : ShareActivityAction
    data object CancelCopy : ShareActivityAction
}

class ShareActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(ShareActivityState())
    val state: StateFlow<ShareActivityState> = _state.asStateFlow()

    private val files = LinkedHashMap<Uri, String?>()

    private var contentResolver: ContentResolver? = null
    private var preferences: SharedPreferences? = null
    private var configRouter: ConfigRouter? = null
    private var service: SyncthingService? = null
    private var copyJob: Job? = null

    fun setContentResolver(contentResolver: ContentResolver?) {
        this.contentResolver = contentResolver
    }

    fun setPreferences(preferences: SharedPreferences?) {
        this.preferences = preferences
    }

    fun setConfigRouter(configRouter: ConfigRouter?) {
        this.configRouter = configRouter
    }

    fun setService(service: SyncthingService?) {
        this.service = service
    }

    fun setInitialFiles(initialFiles: Map<Uri, String?>) {
        files.clear()
        files.putAll(initialFiles)
        _state.update {
            it.copy(
                fileName = files.values.joinToString("\n"),
                isFileNameEditable = files.size == 1
            )
        }
    }

    fun onServiceStateChange(currentState: SyncthingService.State) {
        onAction(ShareActivityAction.LoadData)
    }

    fun onAction(action: ShareActivityAction) {
        when (action) {
            ShareActivityAction.LoadData -> loadFolders()
            is ShareActivityAction.SelectFolder -> selectFolder(action.index)
            is ShareActivityAction.UpdateFileName -> updateFileName(action.name)
            is ShareActivityAction.SubDirectoryPicked -> onSubDirectoryPicked(action.directory)
            ShareActivityAction.StartCopy -> startCopy()
            ShareActivityAction.CancelCopy -> {
                copyJob?.cancel()
                _state.update { it.copy(isSharing = false) }
            }
        }
    }

    /**
     * Persists the currently selected folder so it can be restored the next time.
     */
    fun saveSelectedFolder() {
        val current = _state.value
        val selectedFolder = current.folders.getOrNull(current.selectedFolderIndex) ?: return
        preferences?.edit {
            putString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, selectedFolder.id)
        }
    }

    private fun loadFolders() {
        val prefs = preferences
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val folders = try {
                withContext(Dispatchers.IO) {
                    configRouter?.getFolders(service?.api).orEmpty()
                }
            } catch (e: OpenConfigException) {
                Log.e(TAG, "Could not load folders", e)
                _state.update { it.copy(isLoading = false, welcomeWizardRequired = true) }
                return@launch
            }

            // Get the index of the previously selected folder.
            var folderIndex = 0
            val savedFolderId = prefs?.getString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, "")
            folders.forEachIndexed { index, folder ->
                if (folder.id == savedFolderId) {
                    folderIndex = index
                }
            }

            val selectedFolder = folders.getOrNull(folderIndex)
            val subDirectory = selectedFolder?.let {
                prefs?.getString(PREF_FOLDER_SAVED_SUBDIRECTORY + it.id, "")
            } ?: ""

            _state.update {
                it.copy(
                    isLoading = false,
                    folders = folders,
                    selectedFolderIndex = folderIndex,
                    subDirectory = subDirectory
                )
            }
        }
    }

    private fun selectFolder(index: Int) {
        val selectedFolder = _state.value.folders.getOrNull(index) ?: return
        val subDirectory = preferences?.getString(
            PREF_FOLDER_SAVED_SUBDIRECTORY + selectedFolder.id, ""
        ) ?: ""
        _state.update {
            it.copy(
                selectedFolderIndex = index,
                subDirectory = subDirectory
            )
        }
    }

    private fun updateFileName(name: String) {
        _state.update { it.copy(fileName = name) }
    }

    private fun onSubDirectoryPicked(directory: String?) {
        val current = _state.value
        val selectedFolder = current.folders.getOrNull(current.selectedFolderIndex) ?: return
        // Remove the parent directory from the string, so it is only the Sub directory
        // that is displayed to the user.
        Util.formatPath(selectedFolder.path ?: "").let { parentPath ->
            val subDirectory = directory?.replace(parentPath, "")
            _state.update { it.copy(subDirectory = subDirectory ?: "") }
            preferences?.edit {
                putString(PREF_FOLDER_SAVED_SUBDIRECTORY + selectedFolder.id, subDirectory)
            }
        }
    }

    private fun startCopy() {
        val current = _state.value
        if (current.isSharing) {
            return
        }
        val folder = current.folders.getOrNull(current.selectedFolderIndex)
        if (folder == null) {
            Log.e(TAG, "startCopy: folder == null")
            return
        }
        val folderPath = folder.path
        if (folderPath == null) {
            Log.e(TAG, "startCopy: folder.path == null")
            return
        }
        if (current.isFileNameEditable) {
            files.entries.firstOrNull()?.let { entry ->
                files[entry.key] = current.fileName
            }
        }
        val resolver = contentResolver
        if (resolver == null) {
            Log.e(TAG, "startCopy: contentResolver == null")
            return
        }

        val directory = File(folderPath, current.subDirectory)
        val allowOverwrite =
            preferences?.getBoolean(Constants.PREF_ALLOW_OVERWRITE_FILES, false) ?: false
        val folderLabel = folder.label
        // Snapshot the entries to avoid mutating the map while iterating.
        val entries = files.toList()

        _state.update { it.copy(isSharing = true) }
        copyJob = viewModelScope.launch(Dispatchers.IO) {
            val directoryCanonicalPath = try {
                directory.canonicalPath
            } catch (e: IOException) {
                Log.e(TAG, "startCopy: failed to resolve target directory", e)
                _state.update {
                    it.copy(
                        isSharing = false,
                        copyResult = CopyResult(
                            isError = true,
                            copied = 0,
                            ignored = 0,
                            folderLabel = folderLabel
                        )
                    )
                }
                return@launch
            }
            var isError = false
            var copied = 0
            var ignored = 0
            for (entry in entries) {
                ensureActive()
                try {
                    val fileName = entry.second ?: continue
                    val outFile = File(directory, fileName)
                    // Guard against path traversal via crafted file names: the
                    // resolved target must stay inside the chosen directory.
                    val outFileCanonicalPath = outFile.canonicalPath
                    if (outFileCanonicalPath != directoryCanonicalPath &&
                        !outFileCanonicalPath.startsWith(directoryCanonicalPath + File.separator)
                    ) {
                        Log.e(TAG, "Refusing to write outside target directory: $fileName")
                        isError = true
                        continue
                    }
                    if (outFile.isFile && !allowOverwrite) {
                        ignored++
                        continue
                    }
                    // Write to a temp file in the target directory first, then
                    // atomically rename into place, so an interrupted/failed copy
                    // never leaves a partial or corrupt output file behind.
                    val tempFile = File.createTempFile("share-tmp-", ".tmp", directory)
                    try {
                        entry.first.let { key ->
                            // A revoked grant or gone provider yields a null stream: copying
                            // "nothing" must fail rather than rename a 0-byte temp over the
                            // target (which would truncate the existing file and every synced
                            // copy when overwrite is allowed). A non-null stream that is
                            // genuinely empty is a legitimate 0-byte file and is copied as-is
                            // below.
                            val inputStream = resolver.openInputStream(key)
                                ?: throw IOException("Unable to open content stream for $key")
                            inputStream.use { raw ->
                                Files.asByteSink(tempFile).writeFrom(raw)
                            }
                        }
                        if (!tempFile.renameTo(outFile)) {
                            throw IOException("Failed to move temp file into place: $outFile")
                        }
                        copied++
                    } finally {
                        tempFile.delete()
                    }
                } catch (e: FileNotFoundException) {
                    Log.e(
                        TAG, String.format(
                            "Can't find input file \"%s\" to copy",
                            entry.first
                        ), e
                    )
                    isError = true
                } catch (e: IOException) {
                    Log.e(
                        TAG, String.format(
                            "IO exception during file \"%s\" sharing",
                            entry.first
                        ), e
                    )
                    isError = true
                }
            }
            _state.update {
                it.copy(
                    isSharing = false,
                    copyResult = CopyResult(
                        isError = isError,
                        copied = copied,
                        ignored = ignored,
                        folderLabel = folderLabel
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "ShareActivityViewModel"

        private const val PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER =
            "previously_selected_syncthing_folder"

        private const val PREF_FOLDER_SAVED_SUBDIRECTORY = "saved_sub_directory_"
    }
}
