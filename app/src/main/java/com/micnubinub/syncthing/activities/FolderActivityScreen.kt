package com.micnubinub.syncthing.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.components.ID
import com.micnubinub.syncthing.ui.components.SwitchRow
import com.micnubinub.syncthing.ui.viewModels.FolderActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderActivityEvent
import com.micnubinub.syncthing.ui.viewModels.FolderActivityState
import com.micnubinub.syncthing.ui.viewModels.FolderActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderDeviceShareState
import java.util.concurrent.TimeUnit

@Preview(showSystemUi = true)
@Composable
fun FolderActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderActivityViewModel = FolderActivityViewModel(),
    onFinish: (Int) -> Unit = {},
    onPickFolder: () -> Unit = {},
    onPickFolderAdvanced: () -> Unit = {},
    onShowFolderTypeDialog: (currentType: String?) -> Unit = {},
    onShowPullOrderDialog: (currentOrder: String?) -> Unit = {},
    onShowVersioningDialog: (type: String, params: Map<String, String>) -> Unit = { _, _ -> },
    onAddDevice: () -> Unit = {},
    onEditSyncConditions: (objectPrefixAndId: String?, readableName: String?) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FolderActivityEvent.Finish -> onFinish(event.resultCode)
                is FolderActivityEvent.ShowToast ->
                    Toast.makeText(context, event.message, event.length).show()

                FolderActivityEvent.PickFolder -> onPickFolder()
                FolderActivityEvent.PickFolderAdvanced -> onPickFolderAdvanced()
                is FolderActivityEvent.ShowFolderTypeDialog ->
                    onShowFolderTypeDialog(event.currentType)

                is FolderActivityEvent.ShowPullOrderDialog ->
                    onShowPullOrderDialog(event.currentOrder)

                is FolderActivityEvent.ShowVersioningDialog ->
                    onShowVersioningDialog(event.type, event.params)

                FolderActivityEvent.AddDevice -> onAddDevice()
                is FolderActivityEvent.EditSyncConditions ->
                    onEditSyncConditions(event.objectPrefixAndId, event.readableName)
            }
        }
    }

    FolderActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActivityContent(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onAction(FolderActivityAction.BackPressed) }

    ApplicationTheme {
        Box(modifier = modifier) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(
                                    if (state.isCreateMode) R.string.create_folder
                                    else R.string.edit_folder
                                )
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { onAction(FolderActivityAction.BackPressed) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { onAction(FolderActivityAction.Save) },
                                enabled = !state.isSaving
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = stringResource(
                                        if (state.isCreateMode) R.string.create
                                        else R.string.save_title
                                    )
                                )
                            }
                            if (!state.isCreateMode) {
                                IconButton(
                                    onClick = { onAction(FolderActivityAction.DeleteClicked) },
                                    enabled = !state.isSaving
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.delete_folder)
                                    )
                                }
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    FolderDetailsSection(state, onAction)
                    DevicesSection(state, onAction)
                    FolderTypeSection(state, onAction)

                    SwitchRow(
                        icon = Icons.Default.Autorenew,
                        title = stringResource(R.string.folder_fileWatcher),
                        checked = state.fsWatcherEnabled,
                        onCheckedChange = {
                            onAction(FolderActivityAction.FileWatcherChanged(it))
                        },
                        caption = stringResource(R.string.folder_fileWatcherDescription)
                    )
                    SwitchRow(
                        icon = Icons.Default.PauseCircleOutline,
                        title = stringResource(R.string.folder_pause),
                        checked = state.paused,
                        onCheckedChange = {
                            onAction(FolderActivityAction.PausedChanged(it))
                        }
                    )

                    if (!state.isCreateMode) {
                        CustomSyncConditionsSection(state, onAction)
                    }

                    if (state.expertMode && state.folderType != Constants.FOLDER_TYPE_SEND_ONLY) {
                        PullOrderSection(state, onAction)
                    }

                    VersioningSection(state, onAction)

                    if (state.expertMode) {
                        SwitchRow(
                            icon = Icons.Default.DeleteForever,
                            title = stringResource(R.string.folder_ignore_delete_caption),
                            checked = state.ignoreDelete,
                            onCheckedChange = {
                                onAction(FolderActivityAction.IgnoreDeleteChanged(it))
                            },
                            caption = stringResource(R.string.folder_ignore_delete_description)
                        )
                        SwitchRow(
                            icon = Icons.Default.Terminal,
                            title = stringResource(R.string.folder_run_script_caption),
                            checked = state.runScript,
                            onCheckedChange = {
                                onAction(FolderActivityAction.RunScriptChanged(it))
                            },
                            caption = stringResource(R.string.folder_run_script_description)
                        )
                    }

                    if (!state.isCreateMode) {
                        IgnorePatternsSection(state, onAction)
                    }
                }
            }

            if (state.isSaving) {
                SavingOverlay()
            }
        }

        FolderActivityDialogs(state, onAction)
    }
}

@Composable
private fun FolderDetailsSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    FieldRow(
        icon = Icons.Default.SortByAlpha,
        value = state.label,
        onValueChange = { onAction(FolderActivityAction.LabelChanged(it)) },
        hint = R.string.folder_label,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        focusRequester = focusRequester
    )
    FieldRow(
        icon = ID,
        value = state.folderId,
        onValueChange = { onAction(FolderActivityAction.FolderIdChanged(it)) },
        hint = R.string.folder_id,
        enabled = state.isCreateMode,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        )
    )

    // Directory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.isCreateMode) {
                onAction(FolderActivityAction.PathClicked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = state.path.ifEmpty { stringResource(R.string.directory) },
            color = if (state.path.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Color.Unspecified
            },
            modifier = Modifier.weight(1f)
        )
        if (state.isCreateMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            IconButton(onClick = { onAction(FolderActivityAction.AdvancedDirectoryClicked) }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.advanced_directory_selection)
                )
            }
        }
    }
}

@Composable
private fun DevicesSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Devices,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.devices))
    }

    if (state.devices.isEmpty()) {
        Text(
            text = stringResource(R.string.devices_list_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAction(FolderActivityAction.EmptyDeviceListClicked) }
                .padding(horizontal = 72.dp, vertical = 16.dp)
        )
    } else {
        state.devices.forEach { deviceState ->
            DeviceShareRow(
                deviceState = deviceState,
                onSharedChanged = {
                    onAction(
                        FolderActivityAction.DeviceShareChanged(deviceState.deviceId, it)
                    )
                },
                onPasswordChanged = {
                    onAction(
                        FolderActivityAction.EncryptionPasswordChanged(deviceState.deviceId, it)
                    )
                }
            )
        }
    }
}

@Composable
private fun DeviceShareRow(
    deviceState: FolderDeviceShareState,
    onSharedChanged: (Boolean) -> Unit,
    onPasswordChanged: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSharedChanged(!deviceState.shared) }
                .padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deviceState.displayName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = deviceState.shared,
                onCheckedChange = onSharedChanged
            )
        }
        if (deviceState.shared) {
            OutlinedTextField(
                value = deviceState.encryptionPassword,
                onValueChange = onPasswordChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.deviceEncryptionPasswordHint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password
                ),
                singleLine = true
            )
        }
    }
}

@Composable
private fun FolderTypeSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    val (typeName, typeDescription, typeIcon) = when (state.folderType) {
        Constants.FOLDER_TYPE_SEND_ONLY -> Triple(
            stringResource(R.string.folder_type_sendonly),
            stringResource(R.string.folder_type_sendonly_description),
            Icons.Default.Upload
        )

        Constants.FOLDER_TYPE_RECEIVE_ONLY -> Triple(
            stringResource(R.string.folder_type_receiveonly),
            stringResource(R.string.folder_type_receiveonly_description),
            Icons.Default.Download
        )

        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Triple(
            stringResource(R.string.folder_type_receive_encrypted),
            stringResource(R.string.folder_type_receive_encrypted_description),
            Icons.Default.EnhancedEncryption
        )

        else -> Triple(
            stringResource(R.string.folder_type_sendreceive),
            stringResource(R.string.folder_type_sendreceive_description),
            Icons.Default.Folder
        )
    }

    ValueRow(
        icon = typeIcon,
        title = stringResource(R.string.folder_type),
        captions = listOfNotNull(
            typeName,
            typeDescription,
            if (state.path.isNotEmpty()) {
                stringResource(
                    if (state.canWriteToPath) R.string.folder_path_readwrite
                    else R.string.folder_path_readonly
                )
            } else {
                null
            }
        ),
        enabled = state.folderType != Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED,
        onClick = { onAction(FolderActivityAction.FolderTypeClicked) }
    )
}

@Composable
private fun PullOrderSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    val (orderName, orderDescription) = when (state.pullOrder) {
        "alphabetic" -> Pair(
            stringResource(R.string.pull_order_type_alphabetic),
            stringResource(R.string.pull_order_type_alphabetic_description)
        )

        "smallestFirst" -> Pair(
            stringResource(R.string.pull_order_type_smallestFirst),
            stringResource(R.string.pull_order_type_smallestFirst_description)
        )

        "largestFirst" -> Pair(
            stringResource(R.string.pull_order_type_largestFirst),
            stringResource(R.string.pull_order_type_largestFirst_description)
        )

        "oldestFirst" -> Pair(
            stringResource(R.string.pull_order_type_oldestFirst),
            stringResource(R.string.pull_order_type_oldestFirst_description)
        )

        "newestFirst" -> Pair(
            stringResource(R.string.pull_order_type_newestFirst),
            stringResource(R.string.pull_order_type_newestFirst_description)
        )

        else -> Pair(
            stringResource(R.string.pull_order_type_random),
            stringResource(R.string.pull_order_type_random_description)
        )
    }

    ValueRow(
        icon = Icons.Default.ContentCopy,
        title = stringResource(R.string.pull_order),
        captions = listOf(orderName, orderDescription),
        onClick = { onAction(FolderActivityAction.PullOrderClicked) }
    )
}

@Composable
private fun VersioningSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    val (typeName, typeDescription) = when (state.versioningType) {
        "simple" -> Pair(
            stringResource(R.string.type_simple),
            stringResource(
                R.string.simple_versioning_info,
                state.versioningParams["keep"].orEmpty()
            )
        )

        "trashcan" -> Pair(
            stringResource(R.string.type_trashcan),
            stringResource(
                R.string.trashcan_versioning_info,
                state.versioningParams["cleanoutDays"].orEmpty()
            )
        )

        "staggered" -> {
            val maxAge = TimeUnit.SECONDS.toDays(
                state.versioningParams["maxAge"]?.toLongOrNull() ?: 0
            ).toInt()
            Pair(
                stringResource(R.string.type_staggered),
                stringResource(
                    R.string.staggered_versioning_info,
                    maxAge,
                    state.versioningParams["versionsPath"].orEmpty()
                )
            )
        }

        "external" -> Pair(
            stringResource(R.string.type_external),
            stringResource(
                R.string.external_versioning_info,
                state.versioningParams["command"].orEmpty()
            )
        )

        else -> Pair(stringResource(R.string.none), "")
    }

    ValueRow(
        icon = Icons.Default.Restore,
        title = stringResource(R.string.file_versioning),
        captions = listOf(
            stringResource(R.string.file_versioning_generic_description),
            typeName,
            typeDescription
        ).filter { it.isNotEmpty() },
        onClick = { onAction(FolderActivityAction.VersioningClicked) }
    )
}

@Composable
private fun CustomSyncConditionsSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    SwitchRow(
        icon = Icons.Default.Autorenew,
        title = stringResource(R.string.custom_sync_conditions_title),
        checked = state.customSyncConditions,
        onCheckedChange = {
            onAction(FolderActivityAction.CustomSyncConditionsChanged(it))
        },
        caption = stringResource(R.string.custom_sync_conditions_description)
    )
    Text(
        text = stringResource(R.string.custom_sync_conditions_dialog),
        color = if (state.customSyncConditions) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.customSyncConditions) {
                onAction(FolderActivityAction.CustomSyncConditionsClicked)
            }
            .padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    )
}

@Composable
private fun IgnorePatternsSection(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.ignore_patterns),
            color = if (state.canWriteToPath) {
                Color.Unspecified
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
    }
    OutlinedTextField(
        value = state.ignoreList,
        onValueChange = { onAction(FolderActivityAction.IgnoreListChanged(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
        enabled = state.canWriteToPath,
        placeholder = { Text(stringResource(R.string.ignore_patterns)) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        minLines = 5
    )
}

@Composable
private fun ValueRow(
    icon: ImageVector,
    title: String,
    captions: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                color = if (enabled) {
                    Color.Unspecified
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            captions.forEach { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes hint: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    focusRequester: FocusRequester? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        enabled = enabled,
        placeholder = { Text(stringResource(hint)) },
        leadingIcon = {
            Icon(icon, contentDescription = null)
        },
        keyboardOptions = keyboardOptions,
        singleLine = true
    )
}


@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.state_saving),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun FolderActivityDialogs(
    state: FolderActivityState,
    onAction: (FolderActivityAction) -> Unit
) {
    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onAction(FolderActivityAction.DismissDialogs) },
            text = { Text(stringResource(R.string.remove_folder_confirm)) },
            confirmButton = {
                TextButton(onClick = { onAction(FolderActivityAction.DeleteConfirmed) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(FolderActivityAction.DismissDialogs) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { onAction(FolderActivityAction.DismissDialogs) },
            text = { Text(stringResource(R.string.dialog_discard_changes)) },
            confirmButton = {
                TextButton(onClick = { onAction(FolderActivityAction.DiscardConfirmed) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(FolderActivityAction.DismissDialogs) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
