package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.micnubinub.syncthing.R

/**
 * Contains the configuration options for simple file versioning.
 */
@Composable
fun SimpleVersioningScreen(
    keep: Int,
    onKeepChange: (Int) -> Unit,
    cleanoutDays: Int,
    onCleanoutDaysChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        DescriptionText(stringResource(R.string.simple_file_versioning_description))
        VersioningSectionTitle(stringResource(R.string.keep_versions))
        NumberPicker(
            value = keep,
            range = 1..100000,
            onValueChange = onKeepChange,
            modifier = Modifier.fillMaxWidth()
        )
        DescriptionText(stringResource(R.string.keep_versions_description))
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
