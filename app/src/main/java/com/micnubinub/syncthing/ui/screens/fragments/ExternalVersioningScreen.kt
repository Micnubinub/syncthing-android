package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.micnubinub.syncthing.R

/**
 * Contains the configuration options for external file versioning.
 */
@Composable
fun ExternalVersioningScreen(
    command: String,
    onCommandChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        VersioningSectionTitle(stringResource(R.string.command))
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            label = { Text(stringResource(R.string.command)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DescriptionText(stringResource(R.string.external_versioning_description))
    }
}
