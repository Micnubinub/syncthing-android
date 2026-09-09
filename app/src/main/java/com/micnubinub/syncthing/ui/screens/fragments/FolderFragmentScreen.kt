package com.micnubinub.syncthing.ui.screens.fragments

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.navigation.LocalRootNavigator
import com.micnubinub.syncthing.navigation.RootRoute
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.ui.viewModels.FolderFragmentAction
import com.micnubinub.syncthing.ui.viewModels.FolderFragmentState
import com.micnubinub.syncthing.ui.viewModels.FolderFragmentViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderItemStatusUi
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Preview(showSystemUi = true)
@Composable
fun FolderFragmentScreen(
    modifier: Modifier = Modifier,
    serviceFlow: StateFlow<SyncthingService?> = MutableStateFlow(null),
    serviceStateFlow: StateFlow<SyncthingService.State> = MutableStateFlow(SyncthingService.State.INIT),
    configRouter: ConfigRouter? = null,
    viewModel: FolderFragmentViewModel = remember { FolderFragmentViewModel() }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle()
    val service by serviceFlow.collectAsStateWithLifecycle()

    LaunchedEffect(service) {
        viewModel.setService(service)
    }

    LaunchedEffect(configRouter) {
        configRouter?.let { viewModel.setConfigRouter(configRouter) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAction(FolderFragmentAction.LoadData)
                Lifecycle.Event.ON_STOP -> viewModel.onAction(FolderFragmentAction.StopPolling)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(FolderFragmentAction.StopPolling)
        }
    }

    FolderActivityContent(
        state = state,
        modifier = modifier,
        serviceState = serviceState,
        viewModel = viewModel
    )
}

@Composable
private fun FolderActivityContent(
    state: FolderFragmentState,
    serviceState: SyncthingService.State,
    viewModel: FolderFragmentViewModel,
    modifier: Modifier = Modifier
) {
    val navigator = LocalRootNavigator.current
    when {
        state.isLoading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        state.folders.isEmpty() -> FolderEmptyState(
            onClick = {
                navigator.navigateTo(RootRoute.Folder(isCreate = true)) { result ->
                    if (result == Activity.RESULT_OK) {
                        viewModel.onAction(FolderFragmentAction.LoadData)
                    }
                }
            },
            modifier = modifier
        )

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize()
            ) {
                items(
                    items = state.folders,
                    key = { it.id ?: it.label }
                ) { folder ->
                    FolderItem(
                        folder = folder,
                        status = state.folderStatuses[folder.id],
                        onClick = {
                            navigator.navigateTo(
                                RootRoute.Folder(
                                    isCreate = false,
                                    folderId = folder.id,
                                    folderLabel = folder.label
                                )
                            )
                        },
                        onOverrideChanges = { viewModel.overrideChanges(folder.id) },
                        onRevertLocalChanges = { viewModel.revertLocalChanges(folder.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderEmptyState(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.no_folders_found),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_folder))
            }
        }
    }
}

@Composable
private fun FolderItem(
    folder: Folder,
    status: FolderItemStatusUi?,
    onClick: () -> Unit,
    onOverrideChanges: () -> Unit,
    onRevertLocalChanges: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showOverrideDialog by remember { mutableStateOf(false) }
    var showRevertDialog by remember { mutableStateOf(false) }

    val uiState = remember(folder, status) {
        computeFolderStatusUiState(folder, status)
    }
    val statusText = uiState.rawText
        ?: stringResource(uiState.textResId, *uiState.formatArgs.toTypedArray())
    val statusColor = colorResource(uiState.colorResId)

    val conflicts = status?.conflicts.orEmpty()
    val lastItemAction = status?.lastItemFinishedAction.orEmpty()
    val lastItemName = status?.lastItemFinishedItem.orEmpty()
    val globalFiles = status?.globalFiles ?: 0
    val inSyncFiles = status?.inSyncFiles ?: 0
    val shortPath = remember(folder.path, context.packageName) {
        getShortPathForUI(context.packageName, folder.path)
    }

    if (showOverrideDialog) {
        AlertDialog(
            onDismissRequest = { showOverrideDialog = false },
            title = { Text(stringResource(R.string.override_changes)) },
            text = { Text(stringResource(R.string.override_changes_question)) },
            confirmButton = {
                TextButton(onClick = {
                    onOverrideChanges()
                    showOverrideDialog = false
                }) {
                    Text(stringResource(R.string.override_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverrideDialog = false }) {
                    Text(stringResource(R.string.cancel_title))
                }
            }
        )
    }

    if (showRevertDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false },
            title = { Text(stringResource(R.string.revert_local_changes)) },
            text = { Text(stringResource(R.string.revert_local_changes_question)) },
            confirmButton = {
                TextButton(onClick = {
                    onRevertLocalChanges()
                    showRevertDialog = false
                }) {
                    Text(stringResource(R.string.revert_local_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false }) {
                    Text(stringResource(R.string.cancel_title))
                }
            }
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {

            Text(
                text = folder.label.ifEmpty { folder.id.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 18.sp,
                modifier = modifier.padding(bottom = 2.dp)
            )

            Text(
                text = shortPath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (uiState.showProgressBar) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colorResource(R.color.text_blue)
                )
            }
            if (globalFiles > 0) {
                val filesText = pluralStringResource(
                    R.plurals.files, globalFiles.toInt(), inSyncFiles.toInt(), globalFiles.toInt()
                )
                Text(
                    text = filesText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (conflicts.isNotEmpty()) {
                val conflictsText = pluralStringResource(
                    R.plurals.conflicts, conflicts.size, conflicts.size
                )
                Text(
                    text = conflictsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.text_orange)
                )
            }
            if (lastItemName.isNotEmpty()) {
                val lastItemText = "$lastItemAction $lastItemName"
                Text(
                    text = lastItemText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (uiState.showOverrideButton) {
                TextButton(onClick = { showOverrideDialog = true }) {
                    Text(stringResource(R.string.override_changes))
                }
            }
            if (uiState.showRevertButton) {
                TextButton(onClick = { showRevertDialog = true }) {
                    Text(stringResource(R.string.revert_local_changes))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontSize = 14.sp
            )
            IconButton(
                onClick = { FileUtils.openFolder(context, folder.path) }
            ) {
                Icon(
                    imageVector = when (folder.type) {
                        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Icons.Outlined.Lock
                        Constants.FOLDER_TYPE_RECEIVE_ONLY -> Icons.Outlined.FileDownload
                        Constants.FOLDER_TYPE_SEND_ONLY -> Icons.Outlined.FileUpload
                        else -> Icons.Outlined.Folder
                    },
                    modifier = modifier.size(26.dp),
                    contentDescription = stringResource(R.string.open_file_manager)
                )
            }
        }
    }
}

/**
 * Precomputed, resource-agnostic representation of a folder item's status row.
 */
private data class FolderStatusUiState(
    val textResId: Int = R.string.state_unknown,
    val rawText: String? = null,
    val formatArgs: List<Any> = emptyList(),
    val colorResId: Int = R.color.text_red,
    val showProgressBar: Boolean = false,
    val progress: Float = 0f,
    val showOverrideButton: Boolean = false,
    val showRevertButton: Boolean = false,
)

private fun computeFolderStatusUiState(
    folder: Folder,
    status: FolderItemStatusUi?
): FolderStatusUiState {
    val stateString = status?.state.orEmpty()
    val completion = status?.completion ?: 100.0
    val errors = status?.errors ?: 0
    val needTotalItems = status?.needTotalItems ?: 0
    val receiveOnlyTotalItems = status?.receiveOnlyTotalItems ?: 0

    val isSendOnly = folder.type == Constants.FOLDER_TYPE_SEND_ONLY
    val isReceiveOnly = folder.type == Constants.FOLDER_TYPE_RECEIVE_ONLY
    val isReceiveEncrypted = folder.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED

    val showOverrideButton = isSendOnly &&
            stateString == "idle" &&
            needTotalItems > 0
    val showRevertButton = (isReceiveOnly && receiveOnlyTotalItems > 0) ||
            (isReceiveEncrypted && receiveOnlyTotalItems > 0)

    val baseState = when {
        folder.invalid?.isNotEmpty() == true -> FolderStatusUiState(
            rawText = folder.invalid.orEmpty()
        )

        stateString == "error" -> FolderStatusUiState(
            textResId = R.string.state_error
        )

        errors > 0 -> FolderStatusUiState(
            textResId = R.string.state_failed_items,
            formatArgs = listOf(errors.toInt())
        )

        folder.paused -> FolderStatusUiState(
            textResId = R.string.state_paused,
            colorResId = R.color.text_purple
        )

        stateString == "idle" && needTotalItems > 0 -> FolderStatusUiState(
            textResId = R.string.status_outofsync
        )

        stateString == "clean-waiting" -> FolderStatusUiState(
            textResId = R.string.state_clean_waiting,
            colorResId = R.color.text_orange
        )

        stateString == "cleaning" -> FolderStatusUiState(
            textResId = R.string.state_cleaning,
            colorResId = R.color.text_blue
        )

        stateString == "idle" -> FolderStatusUiState(
            textResId = when {
                folder.deviceCount < 1 -> R.string.state_unshared
                showRevertButton -> R.string.state_local_additions
                else -> R.string.state_up_to_date
            },
            colorResId = when {
                folder.deviceCount < 1 -> R.color.text_orange
                else -> R.color.text_green
            }
        )

        stateString == "scan-waiting" -> FolderStatusUiState(
            textResId = R.string.state_scan_waiting,
            colorResId = R.color.text_orange
        )

        stateString == "scanning" -> FolderStatusUiState(
            textResId = R.string.state_scanning,
            colorResId = R.color.text_blue
        )

        stateString == "starting" -> FolderStatusUiState(
            textResId = R.string.state_sync_preparing,
            colorResId = R.color.text_orange
        )


        stateString == "sync-waiting" -> FolderStatusUiState(
            textResId = R.string.state_sync_waiting,
            colorResId = R.color.text_orange
        )

        stateString == "syncing" -> FolderStatusUiState(
            textResId = R.string.state_syncing,
            formatArgs = listOf(completion.toInt()),
            colorResId = R.color.text_blue,
            showProgressBar = true,
            progress = (completion / 100.0).toFloat()
        )

        stateString == "sync-preparing" -> FolderStatusUiState(
            textResId = R.string.state_sync_preparing,
            colorResId = R.color.text_blue
        )

        stateString == "unknown" -> FolderStatusUiState(
            textResId = R.string.state_unknown
        )

        else -> FolderStatusUiState(
            rawText = status?.state
        )
    }

    return baseState.copy(
        showOverrideButton = showOverrideButton,
        showRevertButton = showRevertButton
    )
}

private fun getShortPathForUI(packageName: String, path: String?): String {
    var shortenedPath = path?.replaceFirst("/storage/emulated/0".toRegex(), "[int]")
    shortenedPath =
        shortenedPath?.replaceFirst("/storage/[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}".toRegex(), "[ext]")
    shortenedPath =
        shortenedPath?.replaceFirst(("/" + Regex.escape(packageName)).toRegex(), "/[app]")
    return "\u2756 " + Util.getPathEllipsis(shortenedPath)
}
