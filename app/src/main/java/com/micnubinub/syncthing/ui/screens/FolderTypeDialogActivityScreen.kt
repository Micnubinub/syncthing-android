package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.viewModels.FOLDER_TYPES
import com.micnubinub.syncthing.ui.viewModels.FolderTypeDialogActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderTypeDialogActivityState
import com.micnubinub.syncthing.ui.viewModels.FolderTypeDialogActivityViewModel

@Preview(showSystemUi = true)
@Composable
fun FolderTypeDialogActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderTypeDialogActivityViewModel = FolderTypeDialogActivityViewModel(),
    onSave: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    FolderTypeDialogActivityContent(
        state = state,
        onTypeSelected = {
            viewModel.onAction(FolderTypeDialogActivityAction.SelectFolderType(it))
        },
        onSave = onSave,
        modifier = modifier
    )
}

@Composable
private fun FolderTypeDialogActivityContent(
    state: FolderTypeDialogActivityState,
    onTypeSelected: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    ApplicationTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            FolderTypeDropdown(
                selectedType = state.selectedType,
                onTypeSelected = onTypeSelected
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = folderTypeDescription(state.selectedType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.finish))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = folderTypeLabel(selectedType),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.folder_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            FOLDER_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(folderTypeLabel(type)) },
                    onClick = {
                        expanded = false
                        onTypeSelected(type)
                    }
                )
            }
        }
    }
}

@Composable
private fun folderTypeLabel(type: String): String = stringResource(
    when (type) {
        Constants.FOLDER_TYPE_SEND_ONLY -> R.string.folder_type_sendonly
        Constants.FOLDER_TYPE_RECEIVE_ONLY -> R.string.folder_type_receiveonly
        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> R.string.folder_type_receive_encrypted
        else -> R.string.folder_type_sendreceive
    }
)

@Composable
private fun folderTypeDescription(type: String): String = stringResource(
    when (type) {
        Constants.FOLDER_TYPE_SEND_ONLY -> R.string.folder_type_sendonly_description
        Constants.FOLDER_TYPE_RECEIVE_ONLY -> R.string.folder_type_receiveonly_description
        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> R.string.folder_type_receive_encrypted_description
        else -> R.string.folder_type_sendreceive_description
    }
)
