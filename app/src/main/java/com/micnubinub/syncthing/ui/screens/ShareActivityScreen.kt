package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.viewModels.CopyResult
import com.micnubinub.syncthing.ui.viewModels.ShareActivityAction
import com.micnubinub.syncthing.ui.viewModels.ShareActivityState
import com.micnubinub.syncthing.ui.viewModels.ShareActivityViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
fun ShareActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: ShareActivityViewModel = ShareActivityViewModel(),
    onCopyFinished: (CopyResult) -> Unit = {},
    onWelcomeWizardRequired: () -> Unit = {},
    onBrowseSubDirectory: (String?, String?) -> Unit = { _, _ -> },
    onCancel: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val currentOnCopyFinished by rememberUpdatedState(onCopyFinished)
    LaunchedEffect(state.copyResult) {
        state.copyResult?.let { currentOnCopyFinished(it) }
    }

    val currentOnWelcomeWizardRequired by rememberUpdatedState(onWelcomeWizardRequired)
    LaunchedEffect(state.welcomeWizardRequired) {
        if (state.welcomeWizardRequired) {
            currentOnWelcomeWizardRequired()
        }
    }

    ShareActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        onBrowseSubDirectory = onBrowseSubDirectory,
        onCancel = onCancel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareActivityContent(
    state: ShareActivityState,
    onAction: (ShareActivityAction) -> Unit,
    modifier: Modifier = Modifier,
    onBrowseSubDirectory: (String?, String?) -> Unit,
    onCancel: () -> Unit
) {
    val selectedFolder = state.folders.getOrNull(state.selectedFolderIndex)
    val selectedFolderPath = selectedFolder?.path
    val titleCount = if (state.isFileNameEditable) 1 else 2

    ApplicationTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.share_activity_title)) }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = pluralStringResource(R.plurals.file_name_title, titleCount, titleCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                OutlinedTextField(
                    value = state.fileName,
                    onValueChange = { onAction(ShareActivityAction.UpdateFileName(it)) },
                    label = { Text(stringResource(R.string.folder_label)) },
                    readOnly = !state.isFileNameEditable,
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                Text(
                    text = stringResource(R.string.folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 7.dp)
                )
                FolderDropdown(
                    folders = state.folders,
                    selectedIndex = state.selectedFolderIndex,
                    onSelected = { onAction(ShareActivityAction.SelectFolder(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                Text(
                    text = stringResource(R.string.sub_folder),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.subDirectory.ifEmpty {
                                stringResource(R.string.no_sub_folder_is_selected)
                            },
                            color = if (state.subDirectory.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                LocalContentColor.current
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Button(
                        onClick = {
                            if (selectedFolderPath != null) {
                                val initialDirectory =
                                    File(selectedFolderPath, state.subDirectory)
                                onBrowseSubDirectory(
                                    initialDirectory.absolutePath,
                                    selectedFolderPath
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.browse))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel_title))
                    }
                    Button(onClick = { onAction(ShareActivityAction.StartCopy) }) {
                        Text(stringResource(R.string.save_title))
                    }
                }
            }
        }

        if (state.isSharing) {
            CopyProgressDialog(onCancel = { onAction(ShareActivityAction.CancelCopy) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDropdown(
    folders: List<Folder>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = folders.getOrNull(selectedIndex)?.toString().orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.folder_title)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            folders.forEachIndexed { index, folder ->
                DropdownMenuItem(
                    text = { Text(folder.toString()) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun CopyProgressDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.copy_progress)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel_title))
            }
        }
    )
}
