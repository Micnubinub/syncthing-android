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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.screens.fragments.ExternalVersioningScreen
import com.micnubinub.syncthing.ui.screens.fragments.NoVersioningScreen
import com.micnubinub.syncthing.ui.screens.fragments.SimpleVersioningScreen
import com.micnubinub.syncthing.ui.screens.fragments.StaggeredVersioningScreen
import com.micnubinub.syncthing.ui.screens.fragments.TrashCanVersioningScreen
import com.micnubinub.syncthing.ui.viewModels.VERSIONING_TYPES
import com.micnubinub.syncthing.ui.viewModels.VersioningDialogActivityAction
import com.micnubinub.syncthing.ui.viewModels.VersioningDialogActivityState
import com.micnubinub.syncthing.ui.viewModels.VersioningDialogActivityViewModel
import java.util.concurrent.TimeUnit

@Preview(showSystemUi = true)
@Composable
fun VersioningDialogActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: VersioningDialogActivityViewModel = VersioningDialogActivityViewModel(),
    onSave: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VersioningDialogActivityContent(
        state = state,
        onTypeSelected = {
            viewModel.onAction(VersioningDialogActivityAction.SelectVersioningType(it))
        },
        onParameterChanged = { key, value ->
            viewModel.onAction(VersioningDialogActivityAction.UpdateParameter(key, value))
        },
        onSave = onSave,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersioningDialogActivityContent(
    state: VersioningDialogActivityState,
    onTypeSelected: (String) -> Unit,
    onParameterChanged: (String, String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navigator = com.micnubinub.syncthing.navigation.LocalRootNavigator.current

    ApplicationTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            VersioningTypeDropdown(
                selectedType = state.selectedType,
                onTypeSelected = onTypeSelected
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (state.selectedType) {
                    "none" -> NoVersioningScreen()
                    "trashcan" -> TrashCanVersioningScreen(
                        cleanoutDays = state.parameters["cleanoutDays"]?.toIntOrNull() ?: 0,
                        onCleanoutDaysChange = { onParameterChanged("cleanoutDays", it.toString()) }
                    )

                    "simple" -> SimpleVersioningScreen(
                        keep = state.parameters["keep"]?.toIntOrNull() ?: 0,
                        onKeepChange = { onParameterChanged("keep", it.toString()) },
                        cleanoutDays = state.parameters["cleanoutDays"]?.toIntOrNull() ?: 0,
                        onCleanoutDaysChange = { onParameterChanged("cleanoutDays", it.toString()) }
                    )

                    "staggered" -> StaggeredVersioningScreen(
                        maxAgeInDays = TimeUnit.SECONDS.toDays(
                            state.parameters["maxAge"]?.toLongOrNull() ?: 0L
                        ).toInt(),
                        onMaxAgeInDaysChange = { days ->
                            onParameterChanged(
                                "maxAge",
                                TimeUnit.DAYS.toSeconds(days.toLong()).toString()
                            )
                        },
                        versionsPath = state.parameters["versionsPath"] ?: "",
                        onPickPath = {
                            navigator.navigateTo(
                                com.micnubinub.syncthing.navigation.RootRoute.FolderPicker(
                                    state.parameters["versionsPath"],
                                    null
                                )
                            ) { result ->
                                (result as? String)?.let {
                                    onParameterChanged("versionsPath", it)
                                }
                            }
                        }
                    )

                    "external" -> ExternalVersioningScreen(
                        command = state.parameters["command"] ?: "",
                        onCommandChange = { onParameterChanged("command", it) }
                    )
                }
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
private fun VersioningTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val labels = stringArrayResource(R.array.versioning_types)
    val selectedIndex = VERSIONING_TYPES.indexOf(selectedType).coerceAtLeast(0)

    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = labels[selectedIndex],
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.file_versioning)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            VERSIONING_TYPES.forEachIndexed { index, type ->
                DropdownMenuItem(
                    text = { Text(labels[index]) },
                    onClick = {
                        expanded = false
                        onTypeSelected(type)
                    }
                )
            }
        }
    }
}
