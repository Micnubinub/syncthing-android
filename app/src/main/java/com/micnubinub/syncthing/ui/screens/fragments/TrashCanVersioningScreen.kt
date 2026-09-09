package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.micnubinub.syncthing.R

/**
 * Contains the configuration options for trashcan file versioning.
 */
@Composable
fun TrashCanVersioningScreen(
    cleanoutDays: Int,
    onCleanoutDaysChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        DescriptionText(stringResource(R.string.trashcan_versioning_description))
        VersioningSectionTitle(stringResource(R.string.clean_out_after))
        NumberPicker(
            value = cleanoutDays,
            range = 0..100,
            onValueChange = onCleanoutDaysChange,
            modifier = Modifier.fillMaxWidth()
        )
        DescriptionText(stringResource(R.string.cleanout_after_description))
    }
}

@Composable
internal fun VersioningSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun DescriptionText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}
