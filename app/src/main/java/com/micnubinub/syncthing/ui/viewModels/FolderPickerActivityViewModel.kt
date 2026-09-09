package com.micnubinub.syncthing.ui.viewModels

import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FolderPickerActivityState(
    val roots: List<File> = emptyList(),
    val location: File? = null,
    val items: List<File> = emptyList(),
    val showCreateFolderDialog: Boolean = false,
    val folderCreationFailed: Boolean = false,
    val selectedDirectory: String? = null
) {
    /**
     * A null location means that the list of roots is displayed.
     */
    val isShowingRoots: Boolean
        get() = location == null
}

sealed interface FolderPickerActivityAction {
    data object LoadData : FolderPickerActivityAction
    data class EnterDirectory(val directory: File) : FolderPickerActivityAction
    data object GoUp : FolderPickerActivityAction
    data object ShowCreateFolderDialog : FolderPickerActivityAction
    data object DismissCreateFolderDialog : FolderPickerActivityAction
    data class CreateFolder(val name: String) : FolderPickerActivityAction
    data object CreateFolderErrorShown : FolderPickerActivityAction
    data object SelectCurrentDirectory : FolderPickerActivityAction
}

class FolderPickerActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(FolderPickerActivityState())
    val state: StateFlow<FolderPickerActivityState> = _state.asStateFlow()

    private var initialDirectory: String? = null
    private var rootDirectory: String? = null
    private var externalFilesDirs: Array<File>? = null
    private var externalFilesDir: File? = null

    fun setExternalFilesDirs(externalFilesDirs: Array<File>) {
        this.externalFilesDirs = externalFilesDirs
    }

    fun setExternalFilesDir(externalFilesDir: File?) {
        this.externalFilesDir = externalFilesDir
    }

    fun setInitialDirectory(initialDirectory: String?) {
        this.initialDirectory = initialDirectory
    }

    fun setRootDirectory(rootDirectory: String?) {
        this.rootDirectory = rootDirectory
    }

    fun onAction(action: FolderPickerActivityAction) {
        when (action) {
            FolderPickerActivityAction.LoadData -> loadData()
            is FolderPickerActivityAction.EnterDirectory -> enterDirectory(action.directory)
            FolderPickerActivityAction.GoUp -> goUpToParentDir()
            FolderPickerActivityAction.ShowCreateFolderDialog ->
                _state.update { it.copy(showCreateFolderDialog = true) }

            FolderPickerActivityAction.DismissCreateFolderDialog ->
                _state.update { it.copy(showCreateFolderDialog = false) }

            is FolderPickerActivityAction.CreateFolder -> createFolder(action.name)
            FolderPickerActivityAction.CreateFolderErrorShown ->
                _state.update { it.copy(folderCreationFailed = false) }

            FolderPickerActivityAction.SelectCurrentDirectory -> selectCurrentDirectory()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            val roots = withContext(Dispatchers.IO) { populateRoots() }
            _state.update { it.copy(roots = roots) }
            if (!initialDirectory.isNullOrEmpty()) {
                initialDirectory?.let { displayFolder(File(it)) }
            } else {
                displayRoot()
            }
        }
    }

    /**
     * If a root directory is specified it is the only root, otherwise all
     * available storage devices/folders from various APIs are used as roots.
     */
    private fun populateRoots(): List<File> {
        val roots = mutableListOf<File>()
        if (!rootDirectory.isNullOrEmpty()) {
            rootDirectory?.let { roots.add(File(it)) }
        } else {
            // getExternalFilesDirs may return null for an ejected SDcard.
            externalFilesDirs?.let { roots.addAll(it.filterNotNull()) }
            externalFilesDir?.let { roots.remove(it) }
            roots.add(Environment.getExternalStorageDirectory())
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))

            // Add paths where we might have read-only access.
            val mountedStoragePaths = FileUtils.mountedStoragePathsAsFileArray
            roots.addAll(mountedStoragePaths)
            roots.add(File("/"))
        }
        // Remove any invalid directories.
        val it = roots.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (!f.exists() || !f.isDirectory) {
                it.remove()
            }
        }
        return roots.distinct().sortedBy { it.absolutePath }
    }

    private fun enterDirectory(directory: File) {
        viewModelScope.launch {
            val isDir = withContext(Dispatchers.IO) { directory.isDirectory }
            if (isDir) {
                displayFolder(directory)
            }
        }
    }

    private fun goUpToParentDir() {
        when {
            canGoUpToSubDir() -> displayFolder(_state.value.location?.parentFile)
            canGoUpToRootDir() -> displayRoot()
            else -> Log.e(TAG, "goUpToParentDir: Cannot go up.")
        }
    }

    private fun canGoUpToSubDir(): Boolean {
        val state = _state.value
        return state.location != null && state.roots.contains(state.location) != true
    }

    private fun canGoUpToRootDir(): Boolean {
        val state = _state.value
        return state.roots.contains(state.location) == true && state.roots.size > 1
    }

    /**
     * Creates a new folder with the given name and enters it.
     */
    private fun createFolder(name: String) {
        val trimmed = name.trim()
        // Reject blank names and any name that could traverse outside the picker
        // location (path separators or parent-directory references).
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\') ||
            trimmed == "." || trimmed == ".."
        ) {
            _state.update {
                it.copy(showCreateFolderDialog = false, folderCreationFailed = true)
            }
            return
        }
        viewModelScope.launch {
            val location = _state.value.location ?: return@launch
            val newFolder = File(location, trimmed)
            val success = withContext(Dispatchers.IO) { newFolder.mkdir() }
            if (success) {
                displayFolder(newFolder)
            } else {
                _state.update {
                    it.copy(showCreateFolderDialog = false, folderCreationFailed = true)
                }
            }
        }
    }

    /**
     * Displays a list of all available roots, or if there is only one root, the
     * contents of that folder.
     */
    private fun displayRoot() {
        val roots = _state.value.roots
        if (roots.size == 1) {
            displayFolder(roots[0])
        } else {
            _state.update {
                it.copy(location = null, items = roots, showCreateFolderDialog = false)
            }
        }
    }

    /**
     * Shows the contents of the folder in [folder].
     */
    private fun displayFolder(folder: File?) {
        viewModelScope.launch {
            val contents = withContext(Dispatchers.IO) {
                (folder?.listFiles() ?: emptyArray()).sortedWith { f1, f2 ->
                    when {
                        f1.isDirectory && f2.isFile -> -1
                        f1.isFile && f2.isDirectory -> 1
                        else -> f1.name.compareTo(f2.name, ignoreCase = true)
                    }
                }
            }
            _state.update {
                it.copy(location = folder, items = contents, showCreateFolderDialog = false)
            }
        }
    }

    private fun selectCurrentDirectory() {
        _state.value.location?.absolutePath?.let {
            _state.update { s -> s.copy(selectedDirectory = Util.formatPath(it)) }
        }
    }

    companion object {
        private const val TAG = "FolderPickerActivityViewModel"
    }
}
