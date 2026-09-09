package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.micnubinub.syncthing.R

/**
 * Contains the configuration options for staggered file versioning.
 *
 * The maxAge parameter is displayed in days but stored in seconds since Syncthing needs it in
 * seconds, so the conversion happens at the call site.
 */
@Composable
fun StaggeredVersioningScreen(
    maxAgeInDays: Int,
    onMaxAgeInDaysChange: (Int) -> Unit,
    versionsPath: String,
    onPickPath: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        DescriptionText(stringResource(R.string.staggered_versioning_description))
        VersioningSectionTitle(stringResource(R.string.maximum_age))
        NumberPicker(
            value = maxAgeInDays,
            range = 0..100,
            onValueChange = onMaxAgeInDaysChange,
            modifier = Modifier.fillMaxWidth()
        )
        DescriptionText(stringResource(R.string.maximum_age_description))
        VersioningSectionTitle(stringResource(R.string.versions_path))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickPath)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = versionsPath.ifEmpty { stringResource(R.string.directory) },
                color = if (versionsPath.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    LocalContentColor.current
                }
            )
        }
        DescriptionText(stringResource(R.string.versions_path_description))
    }
}
