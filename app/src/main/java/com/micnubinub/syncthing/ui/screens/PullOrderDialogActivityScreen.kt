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
import com.micnubinub.syncthing.ui.viewModels.PULL_ORDER_TYPES
import com.micnubinub.syncthing.ui.viewModels.PullOrderDialogActivityAction
import com.micnubinub.syncthing.ui.viewModels.PullOrderDialogActivityState
import com.micnubinub.syncthing.ui.viewModels.PullOrderDialogActivityViewModel

@Preview(showSystemUi = true)
@Composable
fun PullOrderDialogActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: PullOrderDialogActivityViewModel = PullOrderDialogActivityViewModel(),
    onSave: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PullOrderDialogActivityContent(
        state = state,
        onTypeSelected = {
            viewModel.onAction(PullOrderDialogActivityAction.SelectPullOrderType(it))
        },
        onSave = onSave,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullOrderDialogActivityContent(
    state: PullOrderDialogActivityState,
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
            PullOrderTypeDropdown(
                selectedType = state.selectedType,
                onTypeSelected = onTypeSelected
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = pullOrderTypeDescription(state.selectedType),
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
private fun PullOrderTypeDropdown(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val labels = stringArrayResource(R.array.pull_order_types)
    val selectedIndex = PULL_ORDER_TYPES.indexOf(selectedType).coerceAtLeast(0)

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
            label = { Text(stringResource(R.string.pull_order)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            PULL_ORDER_TYPES.forEachIndexed { index, type ->
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

@Composable
private fun pullOrderTypeDescription(type: String): String = stringResource(
    when (type) {
        "random" -> R.string.pull_order_type_random_description
        "alphabetic" -> R.string.pull_order_type_alphabetic_description
        "smallestFirst" -> R.string.pull_order_type_smallestFirst_description
        "largestFirst" -> R.string.pull_order_type_largestFirst_description
        "oldestFirst" -> R.string.pull_order_type_oldestFirst_description
        else -> R.string.pull_order_type_newestFirst_description
    }
)
