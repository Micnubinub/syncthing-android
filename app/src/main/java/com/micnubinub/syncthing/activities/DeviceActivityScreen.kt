package com.micnubinub.syncthing.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PermIdentity
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.components.ID
import com.micnubinub.syncthing.ui.components.SwitchRow
import com.micnubinub.syncthing.ui.screens.fragments.DeviceIdDialog
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityAction
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityEvent
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityState
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.DiscoveredDeviceState
import com.micnubinub.syncthing.ui.viewModels.FolderShareState
import com.micnubinub.syncthing.util.Compression

@Preview(showSystemUi = true)
@Composable
fun DeviceActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: DeviceActivityViewModel = DeviceActivityViewModel(),
    onFinish: (Int) -> Unit = {},
    onScanQrCode: () -> Unit = {},
    onAddFolder: () -> Unit = {},
    onEditSyncConditions: (objectPrefixAndId: String?, readableName: String?) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DeviceActivityEvent.Finish -> onFinish(event.resultCode)
                is DeviceActivityEvent.ShowToast ->
                    Toast.makeText(context, event.message, event.length).show()

                DeviceActivityEvent.ScanQrCode -> onScanQrCode()
                DeviceActivityEvent.AddFolder -> onAddFolder()
                is DeviceActivityEvent.EditSyncConditions ->
                    onEditSyncConditions(event.objectPrefixAndId, event.readableName)
            }
        }
    }

    DeviceActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceActivityContent(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onAction(DeviceActivityAction.BackPressed) }

    ApplicationTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (state.isCreateMode) R.string.add_device
                                else R.string.edit_device
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onAction(DeviceActivityAction.BackPressed) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { onAction(DeviceActivityAction.Save) }) {
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
                                onClick = { onAction(DeviceActivityAction.DeleteClicked) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete_device)
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
                if (state.isCreateMode) {
                    DeviceIdInputSection(state, onAction)
                    DiscoveredDevicesSection(state, onAction)
                } else {
                    DeviceIdDisplaySection(state, onAction)
                }

                DeviceDetailsSection(state, onAction)
                FolderDeviceSection(state, onAction)

                if (state.expertMode) {
                    CompressionSection(state, onAction)
                }

                SwitchRow(
                    icon = Icons.Default.Phonelink,
                    title = stringResource(R.string.introducer),
                    checked = state.introducer,
                    onCheckedChange = {
                        onAction(DeviceActivityAction.IntroducerChanged(it))
                    }
                )
                SwitchRow(
                    icon = Icons.Default.AutoFixNormal,
                    title = stringResource(R.string.autoAcceptFolders),
                    checked = state.autoAcceptFolders,
                    onCheckedChange = {
                        onAction(DeviceActivityAction.AutoAcceptFoldersChanged(it))
                    }
                )
                SwitchRow(
                    icon = Icons.Default.PauseCircleOutline,
                    title = stringResource(R.string.pause_device),
                    checked = state.paused,
                    onCheckedChange = {
                        onAction(DeviceActivityAction.PausedChanged(it))
                    }
                )
                SwitchRow(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.untrusted_device),
                    checked = state.untrusted,
                    onCheckedChange = {
                        onAction(DeviceActivityAction.UntrustedChanged(it))
                    },
                    caption = stringResource(R.string.untrusted_device_explanation)
                )

                if (!state.isCreateMode) {
                    CustomSyncConditionsSection(state, onAction)
                }

                ConnectionInfoSection(state)
            }
        }

        DeviceActivityDialogs(state, onAction)
    }
}

@Composable
private fun DeviceIdInputSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    FieldRow(
        icon = ID,
        value = state.deviceId,
        onValueChange = { onAction(DeviceActivityAction.DeviceIdChanged(it)) },
        hint = R.string.device_id,
        contentDescription = stringResource(R.string.device_id),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        focusRequester = focusRequester,
        trailing = {
            if (state.showQrScannerButton) {
                IconButton(onClick = { onAction(DeviceActivityAction.QrScannerClicked) }) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = stringResource(R.string.scan_qr_code_description)
                    )
                }
            }
        }
    )
}

@Composable
private fun DeviceIdDisplaySection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    FieldRow(
        icon = Icons.Default.PermIdentity,
        value = state.deviceId,
        onValueChange = {},
        hint = R.string.device_id,
        enabled = false,
        trailing = {
            IconButton(onClick = { onAction(DeviceActivityAction.ShowQrClicked) }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.share_device_id)
                )
            }
        }
    )
}

@Composable
private fun DiscoveredDevicesSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    val discoveredDevices = state.discoveredDevices ?: return
    if (!state.deviceId.isBlank()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(DeviceActivityAction.RefreshDiscoveredDevices) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DeviceHub,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.discovered_devices_title),
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.Refresh,
            contentDescription = null
        )
    }

    if (discoveredDevices.isEmpty()) {
        Text(
            text = if (state.localDiscoveryEnabled) {
                stringResource(
                    R.string.discovered_device_list_empty,
                    stringResource(R.string.url_syncthing_homepage)
                )
            } else {
                stringResource(R.string.local_discovery_disabled)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 72.dp, vertical = 8.dp)
        )
    } else {
        discoveredDevices.forEach { discovered ->
            DiscoveredDeviceRow(
                discovered = discovered,
                onClick = {
                    onAction(DeviceActivityAction.DiscoveredDeviceSelected(discovered.deviceId))
                }
            )
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    discovered: DiscoveredDeviceState,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 72.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = discovered.caption,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceDetailsSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    FieldRow(
        icon = Icons.Default.SortByAlpha,
        value = state.name,
        onValueChange = { onAction(DeviceActivityAction.NameChanged(it)) },
        hint = R.string.name,
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
            imeAction = ImeAction.Next
        )
    )
    FieldRow(
        icon = Icons.Default.Link,
        value = state.addresses,
        onValueChange = { onAction(DeviceActivityAction.AddressesChanged(it)) },
        hint = R.string.addresses,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
    )
}


@Composable
private fun CompressionSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    val compressionEntries = stringArrayResource(R.array.compress_entries)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(DeviceActivityAction.CompressionClicked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FolderZip,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.compression),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = compressionEntries.getOrElse(state.compression.index) { "" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CustomSyncConditionsSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    SwitchRow(
        icon = Icons.Default.Autorenew,
        title = stringResource(R.string.custom_sync_conditions_title),
        checked = state.customSyncConditions,
        onCheckedChange = {
            onAction(DeviceActivityAction.CustomSyncConditionsChanged(it))
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
                onAction(DeviceActivityAction.CustomSyncConditionsClicked)
            }
            .padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    )
}

@Composable
private fun ConnectionInfoSection(state: DeviceActivityState) {
    state.currentAddress?.let { address ->
        InfoRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.current_address),
            value = address
        )
    }
    state.clientVersion?.let { version ->
        InfoRow(
            icon = null,
            label = stringResource(R.string.syncthing_version_title),
            value = version
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector?,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = value)
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
    contentDescription: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null
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
            Icon(icon, contentDescription = contentDescription)
        },
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation
    )
}

@Composable
private fun DeviceActivityDialogs(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onAction(DeviceActivityAction.DismissDialogs) },
            text = { Text(stringResource(R.string.remove_device_confirm)) },
            confirmButton = {
                TextButton(onClick = { onAction(DeviceActivityAction.DeleteConfirmed) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(DeviceActivityAction.DismissDialogs) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { onAction(DeviceActivityAction.DismissDialogs) },
            text = { Text(stringResource(R.string.dialog_discard_changes)) },
            confirmButton = {
                TextButton(onClick = { onAction(DeviceActivityAction.DiscardConfirmed) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(DeviceActivityAction.DismissDialogs) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (state.showCompressionDialog) {
        val compressionEntries = stringArrayResource(R.array.compress_entries)
        AlertDialog(
            onDismissRequest = { onAction(DeviceActivityAction.DismissDialogs) },
            title = { Text(stringResource(R.string.compression)) },
            text = {
                Column {
                    compressionEntries.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAction(
                                        DeviceActivityAction.CompressionSelected(
                                            Compression.fromIndex(index)
                                        )
                                    )
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.compression.index == index,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(entry)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (state.showQrDialog) {
        DeviceIdDialog(
            deviceId = state.deviceId,
            deviceName = state.name.ifBlank { state.deviceId.take(7) },
            isCurrentDevice = false,
            onDismiss = { onAction(DeviceActivityAction.DismissDialogs) }
        )
    }
}


@Composable
fun FolderShareRow(
    folderState: FolderShareState,
    onSharedChanged: (Boolean) -> Unit,
    onPasswordChanged: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSharedChanged(!folderState.shared) }
                .padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = folderState.folder.toString(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = folderState.shared,
                onCheckedChange = onSharedChanged
            )
        }
        if (folderState.shared) {
            OutlinedTextField(
                value = folderState.encryptionPassword,
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


@Preview
@Composable
fun FolderDeviceSection(
    state: DeviceActivityState,
    onAction: (DeviceActivityAction) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null
        )
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.devices))
    }

    if (state.folders.isEmpty()) {
        Text(
            text = stringResource(R.string.folders_list_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAction(DeviceActivityAction.EmptyFolderListClicked) }
                .padding(horizontal = 72.dp, vertical = 16.dp)
        )
    } else {
        state.folders.forEach { folderState ->
            FolderShareRow(
                folderState = folderState,
                onSharedChanged = {
                    onAction(
                        DeviceActivityAction.FolderShareChanged(folderState.folder.id, it)
                    )
                },
                onPasswordChanged = {
                    onAction(
                        DeviceActivityAction.EncryptionPasswordChanged(folderState.folder.id, it)
                    )
                }
            )
        }
    }
}

