package com.micnubinub.syncthing.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityState
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
fun FolderPickerActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderPickerActivityViewModel = FolderPickerActivityViewModel(),
    onSelectDirectory: (String) -> Unit = {},
    onNavigateUp: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val currentOnSelectDirectory by rememberUpdatedState(onSelectDirectory)
    LaunchedEffect(state.selectedDirectory) {
        state.selectedDirectory?.let { currentOnSelectDirectory(it) }
    }

    val context = LocalContext.current
    val currentOnFolderCreationFailed by rememberUpdatedState {
        Toast.makeText(context, R.string.create_folder_failed, Toast.LENGTH_SHORT).show()
        viewModel.onAction(FolderPickerActivityAction.CreateFolderErrorShown)
    }
    LaunchedEffect(state.folderCreationFailed) {
        if (state.folderCreationFailed) {
            currentOnFolderCreationFailed()
        }
    }

    FolderPickerActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        onNavigateUp = onNavigateUp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerActivityContent(
    state: FolderPickerActivityState,
    onAction: (FolderPickerActivityAction) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit
) {
    ApplicationTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.directory)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        if (!state.isShowingRoots) {
                            IconButton(
                                onClick = { onAction(FolderPickerActivityAction.SelectCurrentDirectory) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = stringResource(R.string.select_folder)
                                )
                            }
                            IconButton(onClick = { onAction(FolderPickerActivityAction.GoUp) }) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowUpward,
                                    contentDescription = stringResource(R.string.folder_go_up)
                                )
                            }
                            IconButton(
                                onClick = { onAction(FolderPickerActivityAction.ShowCreateFolderDialog) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CreateNewFolder,
                                    contentDescription = stringResource(R.string.create_fs_folder)
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
            ) {
                CurrentPathHeader(
                    state = state,
                    modifier = Modifier.fillMaxWidth()
                )
                FolderContentList(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (state.showCreateFolderDialog) {
            CreateFolderDialog(
                onConfirm = { name -> onAction(FolderPickerActivityAction.CreateFolder(name)) },
                onDismiss = { onAction(FolderPickerActivityAction.DismissCreateFolderDialog) }
            )
        }
    }
}

@Composable
private fun CurrentPathHeader(
    state: FolderPickerActivityState,
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Text(
            text = if (state.isShowingRoots) {
                stringResource(R.string.advanced_storage_path_overview)
            } else {
                stringResource(R.string.current_path, state.location?.absolutePath.orEmpty())
            },
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun FolderContentList(
    state: FolderPickerActivityState,
    onAction: (FolderPickerActivityAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.directory_empty))
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.items, key = { it.absolutePath }) { file ->
                FileListItem(
                    file = file,
                    showPath = state.isShowingRoots,
                    onClick = { onAction(FolderPickerActivityAction.EnterDirectory(file)) }
                )
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: File,
    showPath: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) {
                Icons.Filled.Folder
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (file.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = if (showPath) file.absolutePath else file.name,
            fontStyle = if (file.isFile) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_folder)) },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(folderName) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
